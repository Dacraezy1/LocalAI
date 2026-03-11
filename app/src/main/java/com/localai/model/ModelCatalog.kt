package com.localai.model

/**
 * Curated GGUF models tested to run on 4GB RAM devices (Unisoc T610 class).
 * All models are Q4_K_M quantization — best quality/size tradeoff.
 */
object ModelCatalog {

    data class CatalogEntry(
        val id: String,
        val displayName: String,
        val description: String,
        val sizeGb: Float,
        val ramRequiredGb: Float,
        val downloadUrl: String,
        val fileName: String,
        val tags: List<String>,
        val promptTemplate: String,
        val recommended: Boolean = false
    )

    val models = listOf(

        // ── BEST FOR 4GB RAM (< 1.5GB file) ────────────────────────────────

        CatalogEntry(
            id              = "phi2_q4",
            displayName     = "Phi-2 Q4 (2.7B)",
            description     = "Microsoft's tiny powerhouse. Fast, smart, great for coding & Q&A.",
            sizeGb          = 1.48f,
            ramRequiredGb   = 1.8f,
            downloadUrl     = "https://huggingface.co/TheBloke/phi-2-GGUF/resolve/main/phi-2.Q4_K_M.gguf",
            fileName        = "phi-2.Q4_K_M.gguf",
            tags            = listOf("fast", "coding", "recommended"),
            promptTemplate  = "chatml",
            recommended     = true
        ),

        CatalogEntry(
            id              = "qwen15_chat_q4",
            displayName     = "Qwen1.5 Chat Q4 (1.8B)",
            description     = "Alibaba's multilingual model. Supports English, Arabic, French & more.",
            sizeGb          = 1.1f,
            ramRequiredGb   = 1.4f,
            downloadUrl     = "https://huggingface.co/Qwen/Qwen1.5-1.8B-Chat-GGUF/resolve/main/qwen1_5-1_8b-chat-q4_k_m.gguf",
            fileName        = "qwen1_5-1_8b-chat-q4_k_m.gguf",
            tags            = listOf("multilingual", "fast", "tiny"),
            promptTemplate  = "chatml",
            recommended     = true
        ),

        CatalogEntry(
            id              = "tinyllama_chat",
            displayName     = "TinyLlama Chat Q4 (1.1B)",
            description     = "Smallest model. Runs extremely fast, basic conversation.",
            sizeGb          = 0.67f,
            ramRequiredGb   = 0.9f,
            downloadUrl     = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            fileName        = "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            tags            = listOf("fastest", "tiny"),
            promptTemplate  = "chatml"
        ),

        CatalogEntry(
            id              = "gemma2_2b",
            displayName     = "Gemma 2 IT Q4 (2B)",
            description     = "Google's Gemma 2 — very capable for its size, great reasoning.",
            sizeGb          = 1.6f,
            ramRequiredGb   = 2.0f,
            downloadUrl     = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            fileName        = "gemma-2-2b-it-Q4_K_M.gguf",
            tags            = listOf("google", "reasoning"),
            promptTemplate  = "gemma"
        ),

        // ── MEDIUM (needs ~2.5GB RAM) ───────────────────────────────────────

        CatalogEntry(
            id              = "mistral_7b_q2",
            displayName     = "Mistral 7B Q2 (7B)",
            description     = "Mistral 7B heavily quantized to Q2_K. Fits in 4GB but slower.",
            sizeGb          = 2.7f,
            ramRequiredGb   = 3.2f,
            downloadUrl     = "https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.2-GGUF/resolve/main/mistral-7b-instruct-v0.2.Q2_K.gguf",
            fileName        = "mistral-7b-instruct-v0.2.Q2_K.gguf",
            tags            = listOf("powerful", "slow", "7b"),
            promptTemplate  = "chatml"
        ),

        CatalogEntry(
            id              = "llama3_2_1b",
            displayName     = "Llama 3.2 Instruct Q4 (1B)",
            description     = "Meta's latest small model. Strong instruction following.",
            sizeGb          = 0.77f,
            ramRequiredGb   = 1.0f,
            downloadUrl     = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            fileName        = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            tags            = listOf("meta", "fast", "recommended"),
            promptTemplate  = "chatml",
            recommended     = true
        )
    )

    fun getById(id: String) = models.find { it.id == id }
    fun getRecommended() = models.filter { it.recommended }
    fun filterByMaxRam(maxRamGb: Float) = models.filter { it.ramRequiredGb <= maxRamGb }
}
