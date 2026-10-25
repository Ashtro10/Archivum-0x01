// JNI bridge between Clu (ai.clu.core.stt.WhisperBridge) and whisper.cpp.
//
//  * One handle = one whisper_context (model weights + state). Calls on a handle are serialised by the caller.
//  * Text is returned as raw UTF-8 bytes (JNI's modified UTF-8 cannot carry 4-byte sequences safely).
//  * nativeAbort sets a flag checked inside ggml via the abort callback, so a transcription can be cancelled.
//  * Built as its own shared library with ggml linked statically and all non-JNI symbols hidden, so its ggml copy
//    never clashes with the one inside libclu_llama.so.
#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <string>
#include <thread>

#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define CLU_LOG(...) __android_log_print(ANDROID_LOG_INFO, "CluWhisper", __VA_ARGS__)
#else
#define CLU_LOG(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

namespace {

struct Handle {
    whisper_context* ctx = nullptr;
    std::atomic<bool> abort{false};
    std::string lastLang;
    double lastEncodeMs = 0, lastTotalMs = 0;
};

std::mutex g_log_mutex;
std::string g_last_log;

void log_cb(ggml_log_level level, const char* text, void*) {
    if (level < GGML_LOG_LEVEL_WARN && level != GGML_LOG_LEVEL_CONT) return;
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_last_log += text;
    if (g_last_log.size() > 8192) g_last_log.erase(0, g_last_log.size() - 8192);
}

bool abort_cb(void* user) { return static_cast<Handle*>(user)->abort.load(); }

jbyteArray bytes(JNIEnv* env, const std::string& s) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(a, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data()));
    return a;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_ai_clu_core_stt_WhisperBridge_nativeInit(JNIEnv* env, jclass, jstring jpath, jboolean flashAttn) {
    whisper_log_set(log_cb, nullptr);
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = flashAttn;
    whisper_context* ctx = whisper_init_from_file_with_params(path, cp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!ctx) return 0;
    auto* h = new Handle();
    h->ctx = ctx;
    return reinterpret_cast<jlong>(h);
}

JNIEXPORT void JNICALL Java_ai_clu_core_stt_WhisperBridge_nativeFree(JNIEnv*, jclass, jlong handle) {
    auto* h = reinterpret_cast<Handle*>(handle);
    if (!h) return;
    whisper_free(h->ctx);
    delete h;
}

JNIEXPORT void JNICALL Java_ai_clu_core_stt_WhisperBridge_nativeAbort(JNIEnv*, jclass, jlong handle) {
    auto* h = reinterpret_cast<Handle*>(handle);
    if (h) h->abort.store(true);
}

// Transcribes 16 kHz mono float PCM. lang: "en", "hi", ... or "auto" (detect). audioCtx: encoder context in frames
// (0 = full 30 s window; smaller = faster for short utterances). Returns UTF-8 text, or null on failure/abort.
// Note: whisper.cpp checks the abort flag between graph computations, so an encoder pass that already started runs
// to its end (up to ~1 s on a phone with a short audio context); decoding stops at the next token.
JNIEXPORT jbyteArray JNICALL Java_ai_clu_core_stt_WhisperBridge_nativeTranscribe(JNIEnv* env, jclass, jlong handle, jfloatArray jpcm,
                                                                               jstring jlang, jint threads, jint audioCtx, jstring jprompt,
                                                                               jint maxTokens, jboolean fallback) {
    auto* h = reinterpret_cast<Handle*>(handle);
    if (!h) return nullptr;
    h->abort.store(false);
    const char* lang = env->GetStringUTFChars(jlang, nullptr);
    std::string language(lang);
    env->ReleaseStringUTFChars(jlang, lang);
    std::string prompt;
    if (jprompt) { const char* p = env->GetStringUTFChars(jprompt, nullptr); prompt = p; env->ReleaseStringUTFChars(jprompt, p); }

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = threads > 0 ? threads : std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
    p.translate = false;
    p.no_context = true;
    p.no_timestamps = true;
    p.single_segment = true;
    p.print_special = p.print_progress = p.print_realtime = p.print_timestamps = false;
    p.suppress_blank = true;
    p.language = language == "auto" ? "auto" : language.c_str();
    p.detect_language = false;
    p.audio_ctx = audioCtx;
    p.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
    p.max_tokens = maxTokens;                    // bounds a runaway (repeating) decode
    if (!fallback) p.temperature_inc = 0.0f;     // no temperature-fallback re-decodes: bounded latency for commands
    p.abort_callback = abort_cb;
    p.abort_callback_user_data = h;

    jsize n = env->GetArrayLength(jpcm);
    float* pcm = env->GetFloatArrayElements(jpcm, nullptr);
    auto t0 = std::chrono::steady_clock::now();
    whisper_reset_timings(h->ctx);
    int rc = whisper_full(h->ctx, p, pcm, n);
    env->ReleaseFloatArrayElements(jpcm, pcm, JNI_ABORT);
    h->lastTotalMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    if (rc != 0 || h->abort.load()) return nullptr;
    whisper_timings* t = whisper_get_timings(h->ctx);
    h->lastEncodeMs = t ? t->encode_ms : 0;
    int lid = whisper_full_lang_id(h->ctx);
    h->lastLang = lid >= 0 ? whisper_lang_str(lid) : "";
    std::string text;
    for (int i = 0; i < whisper_full_n_segments(h->ctx); i++) text += whisper_full_get_segment_text(h->ctx, i);
    return bytes(env, text);
}

JNIEXPORT jstring JNICALL Java_ai_clu_core_stt_WhisperBridge_nativeLastInfo(JNIEnv* env, jclass, jlong handle) {
    auto* h = reinterpret_cast<Handle*>(handle);
    if (!h) return env->NewStringUTF("");
    char buf[160];
    snprintf(buf, sizeof buf, "%s %.1f %.1f", h->lastLang.c_str(), h->lastEncodeMs, h->lastTotalMs);
    return env->NewStringUTF(buf);
}

JNIEXPORT jstring JNICALL Java_ai_clu_core_stt_WhisperBridge_nativeSystemInfo(JNIEnv* env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jstring JNICALL Java_ai_clu_core_stt_WhisperBridge_nativeLastLog(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    return env->NewStringUTF(g_last_log.c_str());
}

}  // extern "C"
