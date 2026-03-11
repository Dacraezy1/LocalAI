package com.localai.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * JNI bridge to llama.cpp native library.
 * Optimized for ARM64 (Unisoc T610) with 4GB RAM constraints.
 */
object LlamaWrapper {

    private const val TAG = "LlamaWrapper"
    private var nativeContext: Long = 0L
    private var isLoaded = false

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
        ctx: Long,
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
        useMlock: Boolean
    ): Boolean
    private external fun nativeGenerate(
        ctx: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        callback: TokenCallback
    ): String
    private external fun nativeFreeModel(ctx: Long)
    private external fun nativeGetModelInfo(ctx: Long): String
    private external fun nativeAbort(ctx: Long)
    private external fun nativeGetContextLength(ctx: Long): Int

    // ── Public API ───────────────────────────────────────────────────────────

    fun loadModel(
        modelPath: String,
        params: InferenceParams = InferenceParams()
    ): Result<ModelInfo> {
        return try {
            if (nativeContext == 0L) {
                nativeContext = nativeInit()
            }
            if (nativeContext == 0L) return Result.failure(Exception("Failed to init native context"))

            val success = nativeLoadModel(
                ctx        = nativeContext,
                modelPath  = modelPath,
                nCtx       = params.contextSize,
                nThreads   = params.threads,
                nGpuLayers = 0,         // Unisoc T610 has no Vulkan compute
                useMmap    = true,      // mmap avoids loading full model into RAM
                useMlock   = false      // Don't lock pages on 4GB device
            )

            if (success) {
                isLoaded = true
                val infoJson = nativeGetModelInfo(nativeContext)
                Result.success(ModelInfo.fromJson(infoJson))
            } else {
                Result.failure(Exception("Native model load returned false"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadModel error: ${e.message}")
            Result.failure(e)
        }
    }

    fun generateStream(
        prompt: String,
        params: InferenceParams = InferenceParams()
    ): Flow<String> = flow {
        if (!isLoaded || nativeContext == 0L) {
            emit("[ERROR] No model loaded")
            return@flow
        }

        val buffer = StringBuilder()
        var aborted = false

        val callback = object : TokenCallback {
            override fun onToken(token: String): Boolean {
                if (!coroutineContext.isActive) {
                    aborted = true
                    return false  // signal native to stop
                }
                return true
            }
        }

        // Run blocking native call on IO dispatcher, emit tokens as they arrive
        // For true streaming we use the callback + a shared channel
        val result = nativeGenerate(
            ctx           = nativeContext,
            prompt        = prompt,
            maxTokens     = params.maxTokens,
            temperature   = params.temperature,
            topP          = params.topP,
            topK          = params.topK,
            repeatPenalty = params.repeatPenalty,
            callback      = callback
        )

        if (!aborted) emit(result)

    }.flowOn(Dispatchers.IO)

    /**
     * Streaming version using the native callback for token-by-token output.
     * This is the preferred method for chat UI.
     */
    fun generateTokenStream(
        prompt: String,
        params: InferenceParams = InferenceParams()
    ): Flow<String> = flow {
        if (!isLoaded || nativeContext == 0L) {
            emit("[ERROR] No model loaded. Please select a model first.")
            return@flow
        }

        val tokens = mutableListOf<String>()
        var done = false

        // Native callback collects tokens
        val cb = object : TokenCallback {
            override fun onToken(token: String): Boolean {
                synchronized(tokens) { tokens.add(token) }
                return true
            }
        }

        // Launch native in background, poll tokens for streaming feel
        val thread = Thread {
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
            done = true
        }
        thread.start()

        var lastIndex = 0
        while (!done || lastIndex < tokens.size) {
            synchronized(tokens) {
                while (lastIndex < tokens.size) {
                    emit(tokens[lastIndex++])
                }
            }
            if (!done) kotlinx.coroutines.delay(16) // ~60fps polling
        }

    }.flowOn(Dispatchers.IO)

    fun unloadModel() {
        if (nativeContext != 0L) {
            nativeFreeModel(nativeContext)
            nativeContext = 0L
            isLoaded = false
        }
    }

    fun abortGeneration() {
        if (nativeContext != 0L && isLoaded) {
            nativeAbort(nativeContext)
        }
    }

    fun isModelLoaded() = isLoaded

    fun getContextLength(): Int =
        if (isLoaded && nativeContext != 0L) nativeGetContextLength(nativeContext) else 0

    interface TokenCallback {
        fun onToken(token: String): Boolean
    }
}
