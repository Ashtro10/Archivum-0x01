/*
 * clu_voice: CLU's bridge from the JVM host to the two native libraries its voice needs.
 *
 *  - LiveKit's official Rust SDK through its C FFI (livekit_ffi): requests and events are protobuf messages
 *    (livekit/rust-sdks, livekit-ffi/protocol). This file only moves bytes; the protocol lives in Kotlin.
 *  - sherpa-onnx's C API: voice activity detection (Silero), streaming speech recognition (transducer),
 *    Whisper, text-to-speech (VITS/Piper, Kitten, Kokoro) and keyword spotting.
 *
 * Every handle returned to Java is an opaque pointer; Java owns its lifetime and calls the matching destroy.
 * Build: scripts/build_voice_native.sh (gcc for Linux, MinGW-w64 for Windows), linked against the libraries'
 * own shared objects, which the host loads first.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include "sherpa-onnx/c-api/c-api.h"

typedef uint64_t FfiHandleId;
extern FfiHandleId livekit_ffi_request(const uint8_t *data, size_t len, const uint8_t **res_ptr, size_t *res_len);
extern bool livekit_ffi_drop_handle(FfiHandleId handle_id);
extern void livekit_ffi_initialize(void (*cb)(const uint8_t *, size_t), bool capture_logs, const char *sdk, const char *sdk_version);
extern void livekit_ffi_dispose(void);

#define J(name) Java_ai_clu_desktop_voice_NativeVoice_##name

static JavaVM *g_vm;
static jclass g_cls;
static jmethodID g_onEvent;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) { (void)reserved; g_vm = vm; return JNI_VERSION_1_8; }

/* Called by the Rust runtime on its own threads with one serialized FfiEvent. */
static void on_ffi_event(const uint8_t *data, size_t len) {
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_8) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThreadAsDaemon(g_vm, (void **)&env, NULL) != JNI_OK) return;
    }
    jbyteArray arr = (*env)->NewByteArray(env, (jsize)len);
    if (!arr) { (*env)->ExceptionClear(env); return; }
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize)len, (const jbyte *)data);
    (*env)->CallStaticVoidMethod(env, g_cls, g_onEvent, arr);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, arr);
}

/* ------------------------------------------------------------------------------------------------ LiveKit FFI */

/* Starts LiveKit's runtime; every event it produces arrives in NativeVoice.onFfiEvent(byte[]). Call once. */
JNIEXPORT void JNICALL J(lkInit)(JNIEnv *env, jclass cls, jstring version) {
    if (!g_cls) { g_cls = (*env)->NewGlobalRef(env, cls); g_onEvent = (*env)->GetStaticMethodID(env, cls, "onFfiEvent", "([B)V"); }
    const char *v = (*env)->GetStringUTFChars(env, version, NULL);
    livekit_ffi_initialize(&on_ffi_event, false, "clu-kotlin", v);
    (*env)->ReleaseStringUTFChars(env, version, v);
}
JNIEXPORT void JNICALL J(lkDispose)(JNIEnv *env, jclass cls) { (void)env; (void)cls; livekit_ffi_dispose(); }

JNIEXPORT jbyteArray JNICALL J(lkRequest)(JNIEnv *env, jclass cls, jbyteArray req) {
    (void)cls;
    jsize n = (*env)->GetArrayLength(env, req);
    jbyte *buf = (*env)->GetByteArrayElements(env, req, NULL);
    const uint8_t *res = NULL; size_t res_len = 0;
    FfiHandleId h = livekit_ffi_request((const uint8_t *)buf, (size_t)n, &res, &res_len);
    (*env)->ReleaseByteArrayElements(env, req, buf, JNI_ABORT);
    jbyteArray out = (*env)->NewByteArray(env, (jsize)res_len);
    if (out && res_len) (*env)->SetByteArrayRegion(env, out, 0, (jsize)res_len, (const jbyte *)res);
    if (h) livekit_ffi_drop_handle(h);
    return out;
}

JNIEXPORT jboolean JNICALL J(lkDropHandle)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; return livekit_ffi_drop_handle((FfiHandleId)h) ? JNI_TRUE : JNI_FALSE; }

/* Copies n 16-bit samples from memory owned by the Rust side (an audio frame it handed us). */
JNIEXPORT jshortArray JNICALL J(pcmRead)(JNIEnv *env, jclass cls, jlong ptr, jint n) {
    (void)cls;
    jshortArray out = (*env)->NewShortArray(env, n);
    if (out && n > 0 && ptr) (*env)->SetShortArrayRegion(env, out, 0, n, (const jshort *)(intptr_t)ptr);
    return out;
}

/* Native copy of samples for a capture request (freed with memFree once the request's callback arrived). */
JNIEXPORT jlong JNICALL J(pcmAlloc)(JNIEnv *env, jclass cls, jshortArray pcm) {
    (void)cls;
    jsize n = (*env)->GetArrayLength(env, pcm);
    int16_t *p = (int16_t *)malloc((size_t)n * sizeof(int16_t) + 2);
    if (!p) return 0;
    (*env)->GetShortArrayRegion(env, pcm, 0, n, (jshort *)p);
    return (jlong)(intptr_t)p;
}

JNIEXPORT void JNICALL J(memFree)(JNIEnv *env, jclass cls, jlong p) { (void)env; (void)cls; free((void *)(intptr_t)p); }

/* ------------------------------------------------------------------------------------------------ helpers */

typedef struct { const char *s[12]; jstring j[12]; int n; } Strs;
static const char *str(JNIEnv *env, Strs *st, jstring js) {
    if (!js) return NULL;
    const char *c = (*env)->GetStringUTFChars(env, js, NULL);
    st->s[st->n] = c; st->j[st->n] = js; st->n++;
    return c;
}
static void release(JNIEnv *env, Strs *st) { for (int i = 0; i < st->n; i++) (*env)->ReleaseStringUTFChars(env, st->j[i], st->s[i]); st->n = 0; }
static jfloatArray floats(JNIEnv *env, const float *p, int32_t n) {
    jfloatArray a = (*env)->NewFloatArray(env, n);
    if (a && n > 0) (*env)->SetFloatArrayRegion(env, a, 0, n, p);
    return a;
}

/* ------------------------------------------------------------------------------------------------ VAD (Silero) */

JNIEXPORT jlong JNICALL J(vadCreate)(JNIEnv *env, jclass cls, jstring model, jfloat threshold, jfloat minSilence, jfloat minSpeech, jfloat maxSpeech, jint threads) {
    (void)cls; Strs st = {0};
    SherpaOnnxVadModelConfig c; memset(&c, 0, sizeof c);
    c.silero_vad.model = str(env, &st, model); c.silero_vad.threshold = threshold; c.silero_vad.min_silence_duration = minSilence;
    c.silero_vad.min_speech_duration = minSpeech; c.silero_vad.window_size = 512; c.silero_vad.max_speech_duration = maxSpeech;
    c.sample_rate = 16000; c.num_threads = threads; c.provider = "cpu";
    const SherpaOnnxVoiceActivityDetector *v = SherpaOnnxCreateVoiceActivityDetector(&c, 60.0f);
    release(env, &st);
    return (jlong)(intptr_t)v;
}
JNIEXPORT void JNICALL J(vadAccept)(JNIEnv *env, jclass cls, jlong v, jfloatArray samples) {
    (void)cls; jsize n = (*env)->GetArrayLength(env, samples); jfloat *p = (*env)->GetFloatArrayElements(env, samples, NULL);
    SherpaOnnxVoiceActivityDetectorAcceptWaveform((const SherpaOnnxVoiceActivityDetector *)(intptr_t)v, p, n);
    (*env)->ReleaseFloatArrayElements(env, samples, p, JNI_ABORT);
}
JNIEXPORT jboolean JNICALL J(vadDetected)(JNIEnv *env, jclass cls, jlong v) { (void)env; (void)cls; return SherpaOnnxVoiceActivityDetectorDetected((const SherpaOnnxVoiceActivityDetector *)(intptr_t)v) ? JNI_TRUE : JNI_FALSE; }
/* Next finished speech segment (and removes it), or null when there is none. */
JNIEXPORT jfloatArray JNICALL J(vadPop)(JNIEnv *env, jclass cls, jlong v) {
    (void)cls; const SherpaOnnxVoiceActivityDetector *d = (const SherpaOnnxVoiceActivityDetector *)(intptr_t)v;
    if (SherpaOnnxVoiceActivityDetectorEmpty(d)) return NULL;
    const SherpaOnnxSpeechSegment *s = SherpaOnnxVoiceActivityDetectorFront(d);
    jfloatArray a = floats(env, s->samples, s->n);
    SherpaOnnxDestroySpeechSegment(s); SherpaOnnxVoiceActivityDetectorPop(d);
    return a;
}
JNIEXPORT void JNICALL J(vadReset)(JNIEnv *env, jclass cls, jlong v) { (void)env; (void)cls; SherpaOnnxVoiceActivityDetectorReset((const SherpaOnnxVoiceActivityDetector *)(intptr_t)v); }
JNIEXPORT void JNICALL J(vadFlush)(JNIEnv *env, jclass cls, jlong v) { (void)env; (void)cls; SherpaOnnxVoiceActivityDetectorFlush((const SherpaOnnxVoiceActivityDetector *)(intptr_t)v); }
JNIEXPORT void JNICALL J(vadDestroy)(JNIEnv *env, jclass cls, jlong v) { (void)env; (void)cls; if (v) SherpaOnnxDestroyVoiceActivityDetector((const SherpaOnnxVoiceActivityDetector *)(intptr_t)v); }

/* ------------------------------------------------------------------------------------------------ streaming ASR (transducer) */

JNIEXPORT jlong JNICALL J(asrCreate)(JNIEnv *env, jclass cls, jstring encoder, jstring decoder, jstring joiner, jstring tokens, jint threads) {
    (void)cls; Strs st = {0};
    SherpaOnnxOnlineRecognizerConfig c; memset(&c, 0, sizeof c);
    c.feat_config.sample_rate = 16000; c.feat_config.feature_dim = 80;
    c.model_config.transducer.encoder = str(env, &st, encoder); c.model_config.transducer.decoder = str(env, &st, decoder); c.model_config.transducer.joiner = str(env, &st, joiner);
    c.model_config.tokens = str(env, &st, tokens); c.model_config.num_threads = threads; c.model_config.provider = "cpu";
    c.decoding_method = "greedy_search"; c.enable_endpoint = 0;
    const SherpaOnnxOnlineRecognizer *r = SherpaOnnxCreateOnlineRecognizer(&c);
    release(env, &st);
    return (jlong)(intptr_t)r;
}
JNIEXPORT jlong JNICALL J(asrStream)(JNIEnv *env, jclass cls, jlong r) { (void)env; (void)cls; return (jlong)(intptr_t)SherpaOnnxCreateOnlineStream((const SherpaOnnxOnlineRecognizer *)(intptr_t)r); }
JNIEXPORT void JNICALL J(asrAccept)(JNIEnv *env, jclass cls, jlong s, jint rate, jfloatArray samples) {
    (void)cls; jsize n = (*env)->GetArrayLength(env, samples); jfloat *p = (*env)->GetFloatArrayElements(env, samples, NULL);
    SherpaOnnxOnlineStreamAcceptWaveform((const SherpaOnnxOnlineStream *)(intptr_t)s, rate, p, n);
    (*env)->ReleaseFloatArrayElements(env, samples, p, JNI_ABORT);
}
/* Decodes what is ready and returns the text recognised so far in this stream. */
JNIEXPORT jstring JNICALL J(asrDecode)(JNIEnv *env, jclass cls, jlong r, jlong s) {
    (void)cls; const SherpaOnnxOnlineRecognizer *rec = (const SherpaOnnxOnlineRecognizer *)(intptr_t)r; const SherpaOnnxOnlineStream *str_ = (const SherpaOnnxOnlineStream *)(intptr_t)s;
    while (SherpaOnnxIsOnlineStreamReady(rec, str_)) SherpaOnnxDecodeOnlineStream(rec, str_);
    const SherpaOnnxOnlineRecognizerResult *res = SherpaOnnxGetOnlineStreamResult(rec, str_);
    jstring t = (*env)->NewStringUTF(env, res && res->text ? res->text : "");
    if (res) SherpaOnnxDestroyOnlineRecognizerResult(res);
    return t;
}
JNIEXPORT void JNICALL J(asrInputFinished)(JNIEnv *env, jclass cls, jlong s) { (void)env; (void)cls; SherpaOnnxOnlineStreamInputFinished((const SherpaOnnxOnlineStream *)(intptr_t)s); }
JNIEXPORT void JNICALL J(asrStreamDestroy)(JNIEnv *env, jclass cls, jlong s) { (void)env; (void)cls; if (s) SherpaOnnxDestroyOnlineStream((const SherpaOnnxOnlineStream *)(intptr_t)s); }
JNIEXPORT void JNICALL J(asrDestroy)(JNIEnv *env, jclass cls, jlong r) { (void)env; (void)cls; if (r) SherpaOnnxDestroyOnlineRecognizer((const SherpaOnnxOnlineRecognizer *)(intptr_t)r); }

/* ------------------------------------------------------------------------------------------------ Whisper (multilingual) */

JNIEXPORT jlong JNICALL J(whisperCreate)(JNIEnv *env, jclass cls, jstring encoder, jstring decoder, jstring tokens, jstring language, jint threads) {
    (void)cls; Strs st = {0};
    SherpaOnnxOfflineRecognizerConfig c; memset(&c, 0, sizeof c);
    c.feat_config.sample_rate = 16000; c.feat_config.feature_dim = 80;
    c.model_config.whisper.encoder = str(env, &st, encoder); c.model_config.whisper.decoder = str(env, &st, decoder);
    c.model_config.whisper.language = language ? str(env, &st, language) : ""; c.model_config.whisper.task = "transcribe"; c.model_config.whisper.tail_paddings = -1;
    c.model_config.tokens = str(env, &st, tokens); c.model_config.num_threads = threads; c.model_config.provider = "cpu";
    c.decoding_method = "greedy_search";
    const SherpaOnnxOfflineRecognizer *r = SherpaOnnxCreateOfflineRecognizer(&c);
    release(env, &st);
    return (jlong)(intptr_t)r;
}
/* Transcribes one utterance; returns sherpa's JSON result (text, lang). */
JNIEXPORT jstring JNICALL J(whisperDecode)(JNIEnv *env, jclass cls, jlong r, jfloatArray samples) {
    (void)cls; const SherpaOnnxOfflineRecognizer *rec = (const SherpaOnnxOfflineRecognizer *)(intptr_t)r;
    const SherpaOnnxOfflineStream *s = SherpaOnnxCreateOfflineStream(rec);
    jsize n = (*env)->GetArrayLength(env, samples); jfloat *p = (*env)->GetFloatArrayElements(env, samples, NULL);
    SherpaOnnxAcceptWaveformOffline(s, 16000, p, n);
    (*env)->ReleaseFloatArrayElements(env, samples, p, JNI_ABORT);
    SherpaOnnxDecodeOfflineStream(rec, s);
    const char *json = SherpaOnnxGetOfflineStreamResultAsJson(s);
    jstring t = (*env)->NewStringUTF(env, json ? json : "{}");
    if (json) SherpaOnnxDestroyOfflineStreamResultJson(json);
    SherpaOnnxDestroyOfflineStream(s);
    return t;
}
JNIEXPORT void JNICALL J(whisperDestroy)(JNIEnv *env, jclass cls, jlong r) { (void)env; (void)cls; if (r) SherpaOnnxDestroyOfflineRecognizer((const SherpaOnnxOfflineRecognizer *)(intptr_t)r); }

/* ------------------------------------------------------------------------------------------------ TTS */

/* kind: 0 = VITS (Piper), 1 = Kitten, 2 = Kokoro. */
JNIEXPORT jlong JNICALL J(ttsCreate)(JNIEnv *env, jclass cls, jint kind, jstring model, jstring voices, jstring tokens, jstring dataDir, jint threads) {
    (void)cls; Strs st = {0};
    SherpaOnnxOfflineTtsConfig c; memset(&c, 0, sizeof c);
    c.model.num_threads = threads; c.model.provider = "cpu"; c.max_num_sentences = 1; c.silence_scale = 0.2f;
    if (kind == 1) { c.model.kitten.model = str(env, &st, model); c.model.kitten.voices = str(env, &st, voices); c.model.kitten.tokens = str(env, &st, tokens); c.model.kitten.data_dir = str(env, &st, dataDir); c.model.kitten.length_scale = 1.0f; }
    else if (kind == 2) { c.model.kokoro.model = str(env, &st, model); c.model.kokoro.voices = str(env, &st, voices); c.model.kokoro.tokens = str(env, &st, tokens); c.model.kokoro.data_dir = str(env, &st, dataDir); c.model.kokoro.length_scale = 1.0f; }
    else { c.model.vits.model = str(env, &st, model); c.model.vits.tokens = str(env, &st, tokens); c.model.vits.data_dir = str(env, &st, dataDir);
           c.model.vits.noise_scale = 0.667f; c.model.vits.noise_scale_w = 0.8f; c.model.vits.length_scale = 1.0f; }
    const SherpaOnnxOfflineTts *t = SherpaOnnxCreateOfflineTts(&c);
    release(env, &st);
    return (jlong)(intptr_t)t;
}
JNIEXPORT jint JNICALL J(ttsSampleRate)(JNIEnv *env, jclass cls, jlong t) { (void)env; (void)cls; return SherpaOnnxOfflineTtsSampleRate((const SherpaOnnxOfflineTts *)(intptr_t)t); }
JNIEXPORT jint JNICALL J(ttsNumSpeakers)(JNIEnv *env, jclass cls, jlong t) { (void)env; (void)cls; return SherpaOnnxOfflineTtsNumSpeakers((const SherpaOnnxOfflineTts *)(intptr_t)t); }
/* Synthesises text with speaker sid at speed (1.0 = normal); returns the samples in [-1, 1]. */
JNIEXPORT jfloatArray JNICALL J(ttsGenerate)(JNIEnv *env, jclass cls, jlong t, jstring text, jint sid, jfloat speed) {
    (void)cls; Strs st = {0};
    const SherpaOnnxGeneratedAudio *a = SherpaOnnxOfflineTtsGenerate((const SherpaOnnxOfflineTts *)(intptr_t)t, str(env, &st, text), sid, speed);
    release(env, &st);
    if (!a) return NULL;
    jfloatArray out = floats(env, a->samples, a->n);
    SherpaOnnxDestroyOfflineTtsGeneratedAudio(a);
    return out;
}
JNIEXPORT void JNICALL J(ttsDestroy)(JNIEnv *env, jclass cls, jlong t) { (void)env; (void)cls; if (t) SherpaOnnxDestroyOfflineTts((const SherpaOnnxOfflineTts *)(intptr_t)t); }

/* ------------------------------------------------------------------------------------------------ keyword spotting (wake words) */

JNIEXPORT jlong JNICALL J(kwsCreate)(JNIEnv *env, jclass cls, jstring encoder, jstring decoder, jstring joiner, jstring tokens, jstring keywords, jfloat threshold, jfloat score, jint threads) {
    (void)cls; Strs st = {0};
    SherpaOnnxKeywordSpotterConfig c; memset(&c, 0, sizeof c);
    c.feat_config.sample_rate = 16000; c.feat_config.feature_dim = 80;
    c.model_config.transducer.encoder = str(env, &st, encoder); c.model_config.transducer.decoder = str(env, &st, decoder); c.model_config.transducer.joiner = str(env, &st, joiner);
    c.model_config.tokens = str(env, &st, tokens); c.model_config.num_threads = threads; c.model_config.provider = "cpu";
    c.max_active_paths = 4; c.num_trailing_blanks = 1; c.keywords_score = score; c.keywords_threshold = threshold;
    const char *kw = str(env, &st, keywords); c.keywords_buf = kw; c.keywords_buf_size = (int32_t)strlen(kw);
    const SherpaOnnxKeywordSpotter *k = SherpaOnnxCreateKeywordSpotter(&c);
    release(env, &st);
    return (jlong)(intptr_t)k;
}
JNIEXPORT jlong JNICALL J(kwsStream)(JNIEnv *env, jclass cls, jlong k) { (void)env; (void)cls; return (jlong)(intptr_t)SherpaOnnxCreateKeywordStream((const SherpaOnnxKeywordSpotter *)(intptr_t)k); }
/* Feeds samples and returns the keyword spotted in them, or "" (the stream is reset after a hit). */
JNIEXPORT jstring JNICALL J(kwsAccept)(JNIEnv *env, jclass cls, jlong k, jlong s, jfloatArray samples) {
    (void)cls; const SherpaOnnxKeywordSpotter *sp = (const SherpaOnnxKeywordSpotter *)(intptr_t)k; const SherpaOnnxOnlineStream *strm = (const SherpaOnnxOnlineStream *)(intptr_t)s;
    jsize n = (*env)->GetArrayLength(env, samples); jfloat *p = (*env)->GetFloatArrayElements(env, samples, NULL);
    SherpaOnnxOnlineStreamAcceptWaveform(strm, 16000, p, n);
    (*env)->ReleaseFloatArrayElements(env, samples, p, JNI_ABORT);
    char hit[256]; hit[0] = 0;
    while (SherpaOnnxIsKeywordStreamReady(sp, strm)) {
        SherpaOnnxDecodeKeywordStream(sp, strm);
        const SherpaOnnxKeywordResult *r = SherpaOnnxGetKeywordResult(sp, strm);
        if (r && r->keyword && r->keyword[0]) { strncpy(hit, r->keyword, sizeof hit - 1); hit[sizeof hit - 1] = 0; SherpaOnnxResetKeywordStream(sp, strm); }
        if (r) SherpaOnnxDestroyKeywordResult(r);
    }
    return (*env)->NewStringUTF(env, hit);
}
JNIEXPORT void JNICALL J(kwsDestroy)(JNIEnv *env, jclass cls, jlong k, jlong s) { (void)env; (void)cls; if (s) SherpaOnnxDestroyOnlineStream((const SherpaOnnxOnlineStream *)(intptr_t)s); if (k) SherpaOnnxDestroyKeywordSpotter((const SherpaOnnxKeywordSpotter *)(intptr_t)k); }
