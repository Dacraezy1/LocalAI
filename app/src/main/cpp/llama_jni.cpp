/**
 * llama_jni.cpp
 * JNI bridge between Java/Kotlin and llama.cpp
 * Optimized for ARM64 (Unisoc T610)
 */

#include <jni.h>
#include <string>
#include <android/log.h>
#include "llama.h"
#include "common/common.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ─── Context holder ──────────────────────────────────────────────────────────

struct LlamaContext {
    llama_model   * model   = nullptr;
    llama_context * ctx     = nullptr;
    bool            aborted = false;

    ~LlamaContext() {
        if (ctx)   { llama_free(ctx);         ctx   = nullptr; }
        if (model) { llama_free_model(model); model = nullptr; }
    }
};

// ─── JNI Helpers ─────────────────────────────────────────────────────────────

static inline LlamaContext* toCtx(jlong ptr) {
    return reinterpret_cast<LlamaContext*>(ptr);
}

static jmethodID g_onToken_method = nullptr;

// ─── JNI Implementations ─────────────────────────────────────────────────────

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_localai_llm_LlamaWrapper_nativeInit(JNIEnv* env, jobject) {
    llama_backend_init(false);  // no NUMA
    auto* lc = new LlamaContext();
    LOGI("LlamaContext created: %p", lc);
    return reinterpret_cast<jlong>(lc);
}

JNIEXPORT jboolean JNICALL
Java_com_localai_llm_LlamaWrapper_nativeLoadModel(
        JNIEnv* env, jobject,
        jlong ptr,
        jstring jModelPath,
        jint nCtx,
        jint nThreads,
        jint nGpuLayers,
        jboolean useMmap,
        jboolean useMlock)
{
    auto* lc = toCtx(ptr);
    if (!lc) return JNI_FALSE;

    const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);

    // Free any previously loaded model
    if (lc->ctx)   { llama_free(lc->ctx);         lc->ctx   = nullptr; }
    if (lc->model) { llama_free_model(lc->model); lc->model = nullptr; }

    // Model params
    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = nGpuLayers;
    mparams.use_mmap     = useMmap;
    mparams.use_mlock    = useMlock;

    lc->model = llama_load_model_from_file(modelPath, mparams);
    env->ReleaseStringUTFChars(jModelPath, modelPath);

    if (!lc->model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    // Context params
    auto cparams = llama_context_default_params();
    cparams.n_ctx     = (uint32_t)nCtx;
    cparams.n_threads = (uint32_t)nThreads;
    cparams.n_threads_batch = (uint32_t)nThreads;

    lc->ctx = llama_new_context_with_model(lc->model, cparams);
    if (!lc->ctx) {
        LOGE("Failed to create context");
        llama_free_model(lc->model);
        lc->model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded. n_ctx=%d, n_threads=%d", nCtx, nThreads);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_localai_llm_LlamaWrapper_nativeGenerate(
        JNIEnv* env, jobject,
        jlong ptr,
        jstring jPrompt,
        jint maxTokens,
        jfloat temperature,
        jfloat topP,
        jint topK,
        jfloat repeatPenalty,
        jobject callback)
{
    auto* lc = toCtx(ptr);
    if (!lc || !lc->ctx || !lc->model) {
        return env->NewStringUTF("[ERROR: no model]");
    }
    lc->aborted = false;

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);

    // Tokenize
    std::vector<llama_token> tokens_list;
    tokens_list.resize(llama_n_ctx(lc->ctx));
    int n_tokens = llama_tokenize(
        lc->model,
        prompt,
        (int)strlen(prompt),
        tokens_list.data(),
        (int)tokens_list.size(),
        true,   // add BOS
        false   // special tokens
    );
    env->ReleaseStringUTFChars(jPrompt, prompt);

    if (n_tokens < 0) {
        LOGE("Tokenize failed: %d", n_tokens);
        return env->NewStringUTF("[ERROR: tokenize failed]");
    }
    tokens_list.resize(n_tokens);

    // Sampling params
    auto sparams = llama_sampling_default_params(); // from common
    sparams.temp         = temperature;
    sparams.top_p        = topP;
    sparams.top_k        = topK;
    sparams.penalty_repeat = repeatPenalty;

    auto* sampler = llama_sampling_init(sparams);

    // Get callback method if not cached
    if (!g_onToken_method) {
        jclass cbClass = env->GetObjectClass(callback);
        g_onToken_method = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)Z");
    }

    // Decode prompt tokens in batch
    llama_batch batch = llama_batch_get_one(tokens_list.data(), n_tokens, 0, 0);
    if (llama_decode(lc->ctx, batch)) {
        llama_sampling_free(sampler);
        return env->NewStringUTF("[ERROR: initial decode failed]");
    }

    std::string result;
    result.reserve(1024);

    int n_cur = n_tokens;
    int n_max = std::min(maxTokens, (int)(llama_n_ctx(lc->ctx) - n_tokens));

    while (n_cur < n_tokens + n_max && !lc->aborted) {
        // Sample next token
        llama_token new_token_id = llama_sampling_sample(sampler, lc->ctx, nullptr);
        llama_sampling_accept(sampler, lc->ctx, new_token_id, true);

        // Check for EOS
        if (llama_token_is_eog(lc->model, new_token_id)) break;

        // Decode token to string
        char token_buf[256];
        int token_len = llama_token_to_piece(lc->model, new_token_id, token_buf, sizeof(token_buf), false);
        if (token_len < 0) break;
        token_buf[token_len] = '\0';

        result.append(token_buf, token_len);

        // Call Java callback
        jstring jtoken = env->NewStringUTF(token_buf);
        jboolean cont = env->CallBooleanMethod(callback, g_onToken_method, jtoken);
        env->DeleteLocalRef(jtoken);

        if (!cont || lc->aborted) break;

        // Prepare next batch
        llama_batch next_batch = llama_batch_get_one(&new_token_id, 1, n_cur, 0);
        if (llama_decode(lc->ctx, next_batch)) break;

        n_cur++;
    }

    llama_sampling_free(sampler);
    llama_kv_cache_clear(lc->ctx);  // clear KV cache after each generation to save memory

    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_localai_llm_LlamaWrapper_nativeFreeModel(JNIEnv*, jobject, jlong ptr) {
    auto* lc = toCtx(ptr);
    if (lc) {
        delete lc;
        LOGI("LlamaContext freed");
    }
}

JNIEXPORT jstring JNICALL
Java_com_localai_llm_LlamaWrapper_nativeGetModelInfo(JNIEnv* env, jobject, jlong ptr) {
    auto* lc = toCtx(ptr);
    if (!lc || !lc->model) return env->NewStringUTF("{}");

    char buf[512];
    snprintf(buf, sizeof(buf),
        "{\"name\":\"%s\",\"arch\":\"%s\",\"n_params\":%lld,\"context_size\":%d,\"vocab_size\":%d}",
        llama_model_desc(lc->model),
        "gguf",
        (long long)llama_model_n_params(lc->model),
        llama_n_ctx(lc->ctx),
        llama_n_vocab(lc->model)
    );
    return env->NewStringUTF(buf);
}

JNIEXPORT void JNICALL
Java_com_localai_llm_LlamaWrapper_nativeAbort(JNIEnv*, jobject, jlong ptr) {
    auto* lc = toCtx(ptr);
    if (lc) lc->aborted = true;
}

JNIEXPORT jint JNICALL
Java_com_localai_llm_LlamaWrapper_nativeGetContextLength(JNIEnv*, jobject, jlong ptr) {
    auto* lc = toCtx(ptr);
    if (!lc || !lc->ctx) return 0;
    return (jint)llama_n_ctx(lc->ctx);
}

} // extern "C"
