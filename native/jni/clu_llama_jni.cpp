// JNI bridge between Clu (ai.clu.core.llm.LlamaBridge) and llama.cpp.
//
// Design notes:
//  * One Session = one llama_context. The session remembers the tokens currently in the
//    KV cache, so a follow-up turn only decodes the new suffix of the prompt (prefix reuse).
//  * Generated text is delivered as raw UTF-8 bytes; Kotlin decodes incrementally. (JNI's
//    NewStringUTF uses modified UTF-8 and cannot carry 4-byte sequences safely.)
//  * Cancellation is an atomic flag checked between tokens and inside ggml via the abort callback.
#include <jni.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#ifdef __ANDROID__
#include <android/log.h>
#define CLU_LOG(...) __android_log_print(ANDROID_LOG_INFO, "CluLlama", __VA_ARGS__)
#else
#define CLU_LOG(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

namespace {

std::mutex g_log_mutex;
std::string g_last_log;  // tail of llama.cpp log output, exposed for diagnostics
thread_local std::string t_last_error;

void log_cb(ggml_log_level level, const char* text, void*) {
    if (level < GGML_LOG_LEVEL_WARN && level != GGML_LOG_LEVEL_CONT) return;
    std::lock_guard<std::mutex> lock(g_log_mutex);
    g_last_log += text;
    if (g_last_log.size() > 8192) g_last_log.erase(0, g_last_log.size() - 8192);
#ifdef __ANDROID__
    __android_log_print(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "llama.cpp", "%s", text);
#endif
}

struct Session {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    std::vector<llama_token> cached;  // tokens whose KV entries are in seq 0
    std::atomic<bool> cancel{false};
};

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Decode UTF-8 bytes passed as byte[] (avoids modified-UTF-8 issues for prompts with emoji).
std::string jbytes(JNIEnv* env, jbyteArray a) {
    if (!a) return {};
    jsize n = env->GetArrayLength(a);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

jbyteArray to_jbytes(JNIEnv* env, const std::string& s) {
    jbyteArray a = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(a, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte*>(s.data()));
    return a;
}

std::vector<llama_token> tokenize(const llama_vocab* vocab, const std::string& text, bool add_special) {
    int n = -llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), nullptr, 0, add_special, true);
    std::vector<llama_token> toks(static_cast<size_t>(n));
    if (n > 0 && llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()), toks.data(), n, add_special, true) < 0) {
        toks.clear();
    }
    return toks;
}

std::string piece(const llama_vocab* vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(static_cast<size_t>(-n), '\0');
        n = llama_token_to_piece(vocab, t, big.data(), -n, 0, false);
        return n > 0 ? big.substr(0, static_cast<size_t>(n)) : std::string();
    }
    return std::string(buf, static_cast<size_t>(n));
}

std::string json_escape(const std::string& s) {
    std::string o;
    for (unsigned char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += "\\n"; break;
            case '\r': o += "\\r"; break;
            case '\t': o += "\\t"; break;
            default:
                if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); o += b; }
                else o += static_cast<char>(c);
        }
    }
    return o;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeInit(JNIEnv*, jclass) {
    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(log_cb, nullptr);
        llama_backend_init();
    });
}

JNIEXPORT jstring JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeSystemInfo(JNIEnv* env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jstring JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeLastLog(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_log_mutex);
    std::string safe;
    for (unsigned char c : g_last_log) safe += (c < 0x80) ? static_cast<char>(c) : '?';
    return env->NewStringUTF(safe.c_str());
}

JNIEXPORT jstring JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeLastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(t_last_error.c_str());
}

JNIEXPORT jlong JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeLoadModel(JNIEnv* env, jclass, jstring jpath, jint n_gpu_layers, jboolean use_mmap) {
    std::string path = jstr(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    mp.load_mode = use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    llama_model* m = llama_model_load_from_file(path.c_str(), mp);
    if (!m) {
        t_last_error = "failed to load model: " + path;
        return 0;
    }
    return reinterpret_cast<jlong>(m);
}

JNIEXPORT void JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeFreeModel(JNIEnv*, jclass, jlong h) {
    if (h) llama_model_free(reinterpret_cast<llama_model*>(h));
}

JNIEXPORT jstring JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeModelInfo(JNIEnv* env, jclass, jlong h) {
    auto* m = reinterpret_cast<llama_model*>(h);
    char desc[256] = {0};
    llama_model_desc(m, desc, sizeof desc);
    const char* tmpl = llama_model_chat_template(m, nullptr);
    std::string json = "{\"desc\":\"" + json_escape(desc) + "\"" +
        ",\"sizeBytes\":" + std::to_string(llama_model_size(m)) +
        ",\"params\":" + std::to_string(llama_model_n_params(m)) +
        ",\"ctxTrain\":" + std::to_string(llama_model_n_ctx_train(m)) +
        ",\"vocab\":" + std::to_string(llama_vocab_n_tokens(llama_model_get_vocab(m))) +
        ",\"hasChatTemplate\":" + (tmpl ? "true" : "false") + "}";
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT jlong JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeNewSession(JNIEnv*, jclass, jlong model, jint n_ctx, jint n_batch, jint n_threads) {
    auto* m = reinterpret_cast<llama_model*>(model);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = static_cast<uint32_t>(n_ctx);
    cp.n_batch = static_cast<uint32_t>(n_batch);
    cp.n_ubatch = static_cast<uint32_t>(n_batch);
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    auto* s = new Session();
    s->model = m;
    s->ctx = llama_init_from_model(m, cp);
    if (!s->ctx) {
        t_last_error = "failed to create context (n_ctx=" + std::to_string(n_ctx) + ")";
        delete s;
        return 0;
    }
    llama_set_abort_callback(s->ctx, [](void* data) -> bool {
        return static_cast<Session*>(data)->cancel.load();
    }, s);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeFreeSession(JNIEnv*, jclass, jlong h) {
    auto* s = reinterpret_cast<Session*>(h);
    if (!s) return;
    if (s->ctx) llama_free(s->ctx);
    delete s;
}

JNIEXPORT void JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeCancel(JNIEnv*, jclass, jlong h) {
    auto* s = reinterpret_cast<Session*>(h);
    if (s) s->cancel.store(true);
}

// Threads for single-token decoding and for prompt batches; applies from the next decode step.
JNIEXPORT void JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeSetThreads(JNIEnv*, jclass, jlong h, jint n_threads, jint n_threads_batch) {
    auto* s = reinterpret_cast<Session*>(h);
    if (s && s->ctx) llama_set_n_threads(s->ctx, n_threads, n_threads_batch);
}

JNIEXPORT void JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeResetSession(JNIEnv*, jclass, jlong h) {
    auto* s = reinterpret_cast<Session*>(h);
    if (!s) return;
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->cached.clear();
}

// Formats chat messages with the model's built-in template (or a named fallback template).
// Returns null if the template is not supported.
JNIEXPORT jbyteArray JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeApplyTemplate(
        JNIEnv* env, jclass, jlong model, jstring jtemplate_override, jobjectArray roles, jobjectArray contents, jboolean add_assistant) {
    auto* m = reinterpret_cast<llama_model*>(model);
    std::string override_tmpl = jstr(env, jtemplate_override);
    const char* tmpl = override_tmpl.empty() ? llama_model_chat_template(m, nullptr) : override_tmpl.c_str();
    if (!tmpl) tmpl = "chatml";
    jsize n = env->GetArrayLength(roles);
    std::vector<std::string> r(static_cast<size_t>(n)), c(static_cast<size_t>(n));
    std::vector<llama_chat_message> msgs(static_cast<size_t>(n));
    size_t total = 0;
    for (jsize i = 0; i < n; i++) {
        r[i] = jstr(env, static_cast<jstring>(env->GetObjectArrayElement(roles, i)));
        c[i] = jbytes(env, static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i)));
        total += c[i].size() + r[i].size();
    }
    for (jsize i = 0; i < n; i++) msgs[i] = {r[i].c_str(), c[i].c_str()};
    std::vector<char> buf(total * 2 + 1024);
    int32_t len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, buf.data(), static_cast<int32_t>(buf.size()));
    if (len > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(len) + 1);
        len = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (len < 0) return nullptr;
    return to_jbytes(env, std::string(buf.data(), static_cast<size_t>(len)));
}

JNIEXPORT jint JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeCountTokens(JNIEnv* env, jclass, jlong model, jbyteArray text) {
    auto* m = reinterpret_cast<llama_model*>(model);
    return static_cast<jint>(tokenize(llama_model_get_vocab(m), jbytes(env, text), true).size());
}

JNIEXPORT jint JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeContextSize(JNIEnv*, jclass, jlong h) {
    auto* s = reinterpret_cast<Session*>(h);
    return static_cast<jint>(llama_n_ctx(s->ctx));
}

// After a failed llama_decode. An abort (rc 2: Stop, barge-in, a newer request) leaves every completed step in the
// cache, so only what was not confirmed (s->cached is extended only after a successful step) is removed and the
// conversation stays cached for the next reply. Clearing everything here made the next reply re-read the whole
// conversation. Any other failure clears the cache as before.
static void forget_uncommitted(Session* s, llama_memory_t mem, int rc) {
    if (rc == 2 && llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(s->cached.size()), -1)) return;
    llama_memory_clear(mem, true);
    s->cached.clear();
}

// Generates a completion for `prompt`. For each decoded piece, calls callback.onBytes(byte[]) -> boolean
// (false = stop). Returns a JSON stats string, or null on error (see nativeLastError).
JNIEXPORT jstring JNICALL Java_ai_clu_core_llm_LlamaBridge_nativeGenerate(
        JNIEnv* env, jclass, jlong h, jbyteArray jprompt, jint max_tokens, jfloat temperature, jint top_k,
        jfloat top_p, jfloat min_p, jfloat repeat_penalty, jint seed, jstring jgrammar, jobject callback) {
    using clock = std::chrono::steady_clock;
    auto* s = reinterpret_cast<Session*>(h);
    if (!s) { t_last_error = "no session"; return nullptr; }
    s->cancel.store(false);
    const llama_vocab* vocab = llama_model_get_vocab(s->model);
    const auto t0 = clock::now();

    std::string prompt = jbytes(env, jprompt);
    std::vector<llama_token> toks = tokenize(vocab, prompt, true);
    if (toks.empty()) { t_last_error = "prompt tokenized to zero tokens"; return nullptr; }
    const int n_ctx = static_cast<int>(llama_n_ctx(s->ctx));
    if (static_cast<int>(toks.size()) + 8 > n_ctx) {
        t_last_error = "prompt too long: " + std::to_string(toks.size()) + " tokens > context " + std::to_string(n_ctx);
        return nullptr;
    }

    // Reuse the longest common prefix already in the KV cache.
    const size_t cached_before = s->cached.size();
    size_t keep = 0;
    while (keep < s->cached.size() && keep < toks.size() && s->cached[keep] == toks[keep]) keep++;
    if (keep == toks.size()) keep--;  // must decode at least one token to get logits
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(keep), -1)) {
        llama_memory_clear(mem, true);
        keep = 0;
    }
    s->cached.resize(keep);

    const int n_batch = static_cast<int>(llama_n_batch(s->ctx));
    for (size_t i = keep; i < toks.size(); i += static_cast<size_t>(n_batch)) {
        int n = static_cast<int>(std::min<size_t>(static_cast<size_t>(n_batch), toks.size() - i));
        llama_batch batch = llama_batch_get_one(toks.data() + i, n);
        int rc = llama_decode(s->ctx, batch);
        if (rc != 0) {
            forget_uncommitted(s, mem, rc);
            t_last_error = rc == 2 ? "cancelled" : "llama_decode failed during prompt (rc=" + std::to_string(rc) + ")";
            return nullptr;
        }
        s->cached.insert(s->cached.end(), toks.begin() + static_cast<long>(i), toks.begin() + static_cast<long>(i) + n);
    }
    const auto t_prompt = clock::now();

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sp);
    std::string grammar = jstr(env, jgrammar);
    if (!grammar.empty()) {
        llama_sampler* g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!g) { llama_sampler_free(smpl); t_last_error = "invalid grammar"; return nullptr; }
        llama_sampler_chain_add(smpl, g);
    }
    if (repeat_penalty > 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, repeat_penalty, 0.0f, 0.0f));
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (top_k > 0) llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        if (top_p < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        if (min_p > 0.0f) llama_sampler_chain_add(smpl, llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(seed)));
    }

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onBytes = env->GetMethodID(cbClass, "onBytes", "([B)Z");

    int generated = 0;
    std::string stop_reason = "max_tokens";
    double first_token_ms = -1;
    for (; generated < max_tokens; generated++) {
        if (s->cancel.load()) { stop_reason = "cancelled"; break; }
        if (static_cast<int>(s->cached.size()) >= n_ctx - 1) { stop_reason = "context_full"; break; }
        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) { stop_reason = "eog"; break; }
        if (first_token_ms < 0) first_token_ms = std::chrono::duration<double, std::milli>(clock::now() - t0).count();
        std::string p = piece(vocab, id);
        jbyteArray arr = to_jbytes(env, p);
        jboolean cont = env->CallBooleanMethod(callback, onBytes, arr);
        env->DeleteLocalRef(arr);
        if (env->ExceptionCheck()) { stop_reason = "callback_exception"; break; }
        llama_batch batch = llama_batch_get_one(&id, 1);
        int rc = llama_decode(s->ctx, batch);
        if (rc != 0) {
            stop_reason = rc == 2 ? "cancelled" : "decode_error";
            forget_uncommitted(s, mem, rc);
            break;
        }
        s->cached.push_back(id);
        if (!cont) { stop_reason = "stopped"; generated++; break; }
    }
    llama_sampler_free(smpl);
    const auto t_end = clock::now();
    double prompt_ms = std::chrono::duration<double, std::milli>(t_prompt - t0).count();
    double gen_ms = std::chrono::duration<double, std::milli>(t_end - t_prompt).count();
    std::string json = "{\"promptTokens\":" + std::to_string(toks.size()) +
        ",\"reusedTokens\":" + std::to_string(keep) +
        ",\"cachedTokens\":" + std::to_string(cached_before) +
        ",\"generatedTokens\":" + std::to_string(generated) +
        ",\"promptMs\":" + std::to_string(prompt_ms) +
        ",\"generateMs\":" + std::to_string(gen_ms) +
        ",\"firstTokenMs\":" + std::to_string(first_token_ms) +
        ",\"stopReason\":\"" + stop_reason + "\"}";
    return env->NewStringUTF(json.c_str());
}

}  // extern "C"
