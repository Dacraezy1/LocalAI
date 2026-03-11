package com.localai.llm

/**
 * Formats chat history into the correct prompt template for each model family.
 */
object PromptFormatter {

    enum class Template {
        LLAMA2_CHAT,
        CHATML,          // Mistral, Phi, Qwen, etc.
        ALPACA,
        GEMMA,
        PLAIN
    }

    data class Message(val role: Role, val content: String)
    enum class Role { SYSTEM, USER, ASSISTANT }

    fun format(
        messages: List<Message>,
        template: Template,
        systemPrompt: String = "You are a helpful AI assistant."
    ): String = when (template) {

        Template.LLAMA2_CHAT -> buildLlama2(messages, systemPrompt)
        Template.CHATML      -> buildChatML(messages, systemPrompt)
        Template.ALPACA      -> buildAlpaca(messages)
        Template.GEMMA       -> buildGemma(messages, systemPrompt)
        Template.PLAIN       -> buildPlain(messages)
    }

    /** Llama-2-Chat format */
    private fun buildLlama2(msgs: List<Message>, sys: String): String {
        val sb = StringBuilder()
        var systemAdded = false

        for (msg in msgs) {
            when (msg.role) {
                Role.SYSTEM -> { /* embedded in first user turn */ }
                Role.USER -> {
                    sb.append("[INST] ")
                    if (!systemAdded) {
                        sb.append("<<SYS>>\n$sys\n<</SYS>>\n\n")
                        systemAdded = true
                    }
                    sb.append("${msg.content} [/INST]")
                }
                Role.ASSISTANT -> sb.append(" ${msg.content} </s><s>")
            }
        }
        return sb.toString()
    }

    /** ChatML format (Mistral, Phi-2, Qwen, Hermes, OpenChat) */
    private fun buildChatML(msgs: List<Message>, sys: String): String {
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n$sys<|im_end|>\n")

        for (msg in msgs) {
            when (msg.role) {
                Role.SYSTEM    -> { /* already added */ }
                Role.USER      -> sb.append("<|im_start|>user\n${msg.content}<|im_end|>\n")
                Role.ASSISTANT -> sb.append("<|im_start|>assistant\n${msg.content}<|im_end|>\n")
            }
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /** Alpaca instruction format */
    private fun buildAlpaca(msgs: List<Message>): String {
        val lastUser = msgs.lastOrNull { it.role == Role.USER }?.content ?: ""
        return "### Instruction:\n$lastUser\n\n### Response:\n"
    }

    /** Gemma format */
    private fun buildGemma(msgs: List<Message>, sys: String): String {
        val sb = StringBuilder()
        for (msg in msgs) {
            when (msg.role) {
                Role.USER      -> sb.append("<start_of_turn>user\n${msg.content}<end_of_turn>\n")
                Role.ASSISTANT -> sb.append("<start_of_turn>model\n${msg.content}<end_of_turn>\n")
                Role.SYSTEM    -> {}
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun buildPlain(msgs: List<Message>): String {
        return msgs.joinToString("\n") { "${it.role.name}: ${it.content}" } + "\nASSISTANT:"
    }

    /** Auto-detect template from model filename */
    fun detectTemplate(modelFileName: String): Template {
        val name = modelFileName.lowercase()
        return when {
            "llama-2" in name || "llama2" in name -> Template.LLAMA2_CHAT
            "mistral" in name || "phi" in name ||
            "qwen" in name || "hermes" in name ||
            "openchat" in name || "neural" in name -> Template.CHATML
            "gemma" in name                        -> Template.GEMMA
            "alpaca" in name || "vicuna" in name   -> Template.ALPACA
            else                                   -> Template.CHATML  // safe default
        }
    }
}
