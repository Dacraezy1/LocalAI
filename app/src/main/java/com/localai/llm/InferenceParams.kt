package com.localai.llm

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Tuned defaults for Unisoc T610 (4 cores A75 + 4 cores A55).
 * 4 threads is the sweet spot — avoids thermal throttle while using big cores.
 */
data class InferenceParams(
    val contextSize: Int    = 2048,     // Keep low to save RAM
    val threads: Int        = 4,        // T610 big cores
    val maxTokens: Int      = 512,
    val temperature: Float  = 0.7f,
    val topP: Float         = 0.9f,
    val topK: Int           = 40,
    val repeatPenalty: Float= 1.1f,
    val systemPrompt: String= "You are a helpful, concise AI assistant running locally on a mobile device."
) {
    companion object {
        fun forModel(modelSize: ModelSize) = when (modelSize) {
            ModelSize.TINY   -> InferenceParams(contextSize = 1024, threads = 4, maxTokens = 256)
            ModelSize.SMALL  -> InferenceParams(contextSize = 2048, threads = 4, maxTokens = 512)
            ModelSize.MEDIUM -> InferenceParams(contextSize = 1024, threads = 4, maxTokens = 256)
        }
    }
}

enum class ModelSize { TINY, SMALL, MEDIUM }

data class ModelInfo(
    @SerializedName("name")          val name: String       = "",
    @SerializedName("arch")          val arch: String       = "",
    @SerializedName("n_params")      val nParams: Long      = 0L,
    @SerializedName("context_size")  val contextSize: Int   = 0,
    @SerializedName("vocab_size")    val vocabSize: Int     = 0
) {
    companion object {
        fun fromJson(json: String): ModelInfo = try {
            Gson().fromJson(json, ModelInfo::class.java) ?: ModelInfo()
        } catch (e: Exception) { ModelInfo() }
    }
}
