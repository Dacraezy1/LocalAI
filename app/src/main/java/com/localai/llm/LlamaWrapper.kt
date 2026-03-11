package com.localai.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * JNI bridge to llama.cpp native library.
 * Optimized for ARM64 (Unisoc T610) with 4GB RAM constraints.
 */
object LlamaWrapper {

    private const val TAG = "LlamaWrapper"
    private var nativeContext: Long = 0L
    private var isLoaded = false

    @Volatile private var abortRequested = false

    init {
        try {
            System.loadLibrary("localai_jni")
            Log.i(TAG, "llama.cpp native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load llama.cpp: ${e.message}")
        }
    }

    // ── Native declarations ──────────────────────────────────────────────────

    private external fun nativeInit(): Long
    private external fun nativeLoadModel(
        ctx: Long, modelPath: String,
        nCtx: Int, nThreads: Int, nGpuLayers: Int,
        useMmap: Boolean, useMlock: Boolean
    ): Boolean
    private external fun nativeGenerate(
        ctx: Long, prompt: String,
        maxTokens: Int, temperature: Float, topP: Float,
        topK: Int, repeatPenalty: Float, callback: TokenCallback
    ): String
    private external fun nativeFreeModel(ctx: Long)
    private external fun nativeGetModelInfo(ctx: Long): String
    private external fun nativeAbort(ctx: Long)
    private external fun nativeGetContextLength(ctx: Long): Int

    // ── Public API ───────────────────────────────────────────────────────────

    fun loadModel(modelPath: String, params: InferenceParams = InferenceParams()): Result<ModelInfo> {
        return try {
            if (nativeContext == 0L) nativeContext = nativeInit()
            if (nativeContext == 0L) return Result.failure(Exception("Failed to init native context"))

            val ok = nativeLoadModel(
                ctx        = nativeContext,
                modelPath  = modelPath,
                nCtx       = params.contextSize,
                nThreads   = params.threads,
                nGpuLayers = 0,
                useMmap    = true,
                useMlock   = false
            )
            if (ok) {
                isLoaded = true
                Result.success(ModelInfo.fromJson(nativeGetModelInfo(nativeContext)))
            } else {
                Result.failure(Exception("Native model load returned false"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadModel error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Token-by-token streaming using a Channel as the bridge between
     * the native callback thread and the Flow collector.
     */
    fun generateTokenStream(
        prompt: String,
        params: InferenceParams = InferenceParams()
    ): Flow<String> = flow {
        if (!isLoaded || nativeContext == 0L) {
            emit("[ERROR] No model loaded. Please select a model first.")
            return@flow
        }

        abortRequested = false

        // Channel bridges native thread → coroutine flow
        // Capacity 0 = rendezvous; use buffered to avoid blocking native thread
        val channel = Channel<String>(capacity = Channel.UNLIMITED)

        val cb = object : TokenCallback {
            override fun onToken(token: String): Boolean {
                if (abortRequested) return false
                channel.trySend(token)
                return true
            }
        }

        // Run blocking native call on IO thread
        val thread = Thread {
            try {
                nativeGenerate(
                    ctx           = nativeContext,
                    prompt        = prompt,
                    maxTokens     = params.maxTokens,
                    temperature   = params.temperature,
                    topP          = params.topP,
                    topK          = params.topK,
                    repeatPenalty = params.repeatPenalty,
                    callback      = cb
                )
            } finally {
                channel.close()
            }
        }
        thread.start()

        // Collect tokens from channel and emit — no synchronized needed
        for (token in channel) {
            emit(token)
        }

    }.flowOn(Dispatchers.IO)

    // Simpler non-streaming version (returns full response at once)
    fun generateStream(
        prompt: String,
        params: InferenceParams = InferenceParams()
    ): Flow<String> = generateTokenStream(prompt, params)

    fun unloadModel() {
        if (nativeContext != 0L) {
            nativeFreeModel(nativeContext)
            nativeContext = 0L
            isLoaded = false
        }
    }

    fun abortGeneration() {
        abortRequested = true
        if (nativeContext != 0L && isLoaded) nativeAbort(nativeContext)
    }

    fun isModelLoaded() = isLoaded

    fun getContextLength(): Int =
        if (isLoaded && nativeContext != 0L) nativeGetContextLength(nativeContext) else 0

    interface TokenCallback {
        fun onToken(token: String): Boolean
    }
}
