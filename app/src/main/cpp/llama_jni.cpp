/**
 * llama_jni.cpp  –  JNI bridge for llama.cpp b3447
 * Uses ONLY the public llama.h API (no common/ helpers).
 */

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct LlamaCtx {
    llama_model   * model   = nullptr;
    llama_context * ctx     = nullptr;
    bool            aborted = false;
    ~LlamaCtx() {
        if (ctx)   { llama_free(ctx);         ctx   = nullptr; }
        if (model) { llama_free_model(model);  model = nullptr; }
    }
};

static inline LlamaCtx* get(jlong ptr) { return reinterpret_cast<LlamaCtx*>(ptr); }
static jmethodID g_onToken = nullptr;

static llama_token sample_token(llama_context* ctx, float temp, float top_p, int top_k,
                                 float rep_pen, const std::vector<llama_token>& prev) {
    int n_vocab = llama_n_vocab(llama_get_model(ctx));
    std::vector<llama_token_data> td(n_vocab);
    float* logits = llama_get_logits(ctx);
    for (int i = 0; i < n_vocab; ++i) td[i] = {i, logits[i], 0.0f};

    llama_token_data_array cands{td.data(), (size_t)n_vocab, false};

    if (rep_pen != 1.0f && !prev.empty())
        llama_sample_repetition_penalties(ctx, &cands, prev.data(), prev.size(), rep_pen, 0.0f, 0.0f);

    if (temp <= 0.0f) return llama_sample_token_greedy(ctx, &cands);

    llama_sample_temp(ctx, &cands, temp);
    if (top_k > 0) llama_sample_top_k(ctx, &cands, top_k, 1);
    if (top_p < 1.0f) llama_sample_top_p(ctx, &cands, top_p, 1);
    return llama_sample_token(ctx, &cands);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_localai_llm_LlamaWrapper_nativeInit(JNIEnv*, jobject) {
    llama_backend_init();
    return reinterpret_cast<jlong>(new LlamaCtx());
}

JNIEXPORT jboolean JNICALL
Java_com_localai_llm_LlamaWrapper_nativeLoadModel(
        JNIEnv* env, jobject, jlong ptr, jstring jpath,
        jint nCtx, jint nThreads, jint nGpuLayers, jboolean useMmap, jboolean useMlock)
{
    auto* lc = get(ptr); if (!lc) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (lc->ctx)   { llama_free(lc->ctx);         lc->ctx   = nullptr; }
    if (lc->model) { llama_free_model(lc->model);  lc->model = nullptr; }

    auto mp = llama_model_default_params();
    mp.n_gpu_layers = nGpuLayers;
    mp.use_mmap     = (bool)useMmap;
    mp.use_mlock    = (bool)useMlock;
    lc->model = llama_load_model_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!lc->model) { LOGE("load model failed"); return JNI_FALSE; }

    auto cp = llama_context_default_params();
    cp.n_ctx           = (uint32_t)nCtx;
    cp.n_threads       = (uint32_t)nThreads;
    cp.n_threads_batch = (uint32_t)nThreads;
    lc->ctx = llama_new_context_with_model(lc->model, cp);
    if (!lc->ctx) { llama_free_model(lc->model); lc->model = nullptr; return JNI_FALSE; }
    LOGI("model loaded nctx=%d threads=%d", nCtx, nThreads);
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_localai_llm_LlamaWrapper_nativeGenerate(
        JNIEnv* env, jobject, jlong ptr, jstring jPrompt,
        jint maxTokens, jfloat temperature, jfloat topP, jint topK,
        jfloat repeatPenalty, jobject callback)
{
    auto* lc = get(ptr);
    if (!lc || !lc->ctx || !lc->model) return env->NewStringUTF("[ERROR: no model]");
    lc->aborted = false;

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    int ctx_size = (int)llama_n_ctx(lc->ctx);
    std::vector<llama_token> toks(ctx_size);
    int n = llama_tokenize(lc->model, prompt, (int)strlen(prompt),
                           toks.data(), ctx_size, true, false);
    env->ReleaseStringUTFChars(jPrompt, prompt);
    if (n < 0) return env->NewStringUTF("[ERROR: tokenize]");
    toks.resize(n);

    if (!g_onToken) {
        jclass cls = env->GetObjectClass(callback);
        g_onToken  = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
    }

    llama_batch batch = llama_batch_get_one(toks.data(), n, 0, 0);
    if (llama_decode(lc->ctx, batch)) return env->NewStringUTF("[ERROR: decode prompt]");

    std::string result; result.reserve(2048);
    std::vector<llama_token> prev(toks);
    int n_max = (maxTokens < ctx_size - n) ? maxTokens : ctx_size - n;

    for (int i = 0; i < n_max && !lc->aborted; i++) {
        llama_token tok = sample_token(lc->ctx, temperature, topP, topK, repeatPenalty, prev);
        if (llama_token_is_eog(lc->model, tok)) break;

        char piece[256];
        int plen = llama_token_to_piece(lc->model, tok, piece, sizeof(piece)-1, 0, false);
        if (plen <= 0) break;
        piece[plen] = '\0';
        result.append(piece, plen);
        prev.push_back(tok);

        jstring jtok = env->NewStringUTF(piece);
        jboolean cont = env->CallBooleanMethod(callback, g_onToken, jtok);
        env->DeleteLocalRef(jtok);
        if (!cont) break;

        llama_batch nb = llama_batch_get_one(&tok, 1, n + i, 0);
        if (llama_decode(lc->ctx, nb)) break;
    }

    llama_kv_cache_clear(lc->ctx);
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_localai_llm_LlamaWrapper_nativeFreeModel(JNIEnv*, jobject, jlong ptr) { delete get(ptr); }

JNIEXPORT jstring JNICALL
Java_com_localai_llm_LlamaWrapper_nativeGetModelInfo(JNIEnv* env, jobject, jlong ptr) {
    auto* lc = get(ptr);
    if (!lc || !lc->model) return env->NewStringUTF("{}");
    char name[256] = {};
    llama_model_desc(lc->model, name, sizeof(name));
    char buf[512];
    snprintf(buf, sizeof(buf), "{\"name\":\"%s\",\"n_ctx\":%d,\"n_vocab\":%d}",
        name, llama_n_ctx(lc->ctx), llama_n_vocab(lc->model));
    return env->NewStringUTF(buf);
}

JNIEXPORT void JNICALL
Java_com_localai_llm_LlamaWrapper_nativeAbort(JNIEnv*, jobject, jlong ptr) {
    auto* lc = get(ptr); if (lc) lc->aborted = true;
}

JNIEXPORT jint JNICALL
Java_com_localai_llm_LlamaWrapper_nativeGetContextLength(JNIEnv*, jobject, jlong ptr) {
    auto* lc = get(ptr); return (!lc || !lc->ctx) ? 0 : (jint)llama_n_ctx(lc->ctx);
}

} // extern "C"
