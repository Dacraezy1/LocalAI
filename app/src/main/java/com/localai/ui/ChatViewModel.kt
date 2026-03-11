package com.localai.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import com.localai.llm.InferenceParams
import com.localai.llm.LlamaWrapper
import com.localai.llm.PromptFormatter
import com.localai.model.AppDatabase
import com.localai.model.ModelEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = "ChatViewModel"
    private val db = AppDatabase.getInstance(app)

    // ── State ────────────────────────────────────────────────────────────────

    sealed class UiState {
        object Idle          : UiState()
        object LoadingModel  : UiState()
        object Ready         : UiState()
        object Generating    : UiState()
        data class Error(val msg: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    val activeModel: Flow<ModelEntity?> = db.modelDao().observeActive()

    private val _tokenSpeed = MutableStateFlow(0f)
    val tokenSpeed: StateFlow<Float> = _tokenSpeed.asStateFlow()

    // ── Conversation history (context window management) ─────────────────────

    private val chatHistory = mutableListOf<PromptFormatter.Message>()
    private var currentParams = InferenceParams()
    private var template = PromptFormatter.Template.CHATML
    private var generateJob: Job? = null

    // ── Model loading ────────────────────────────────────────────────────────

    private var loadedModelId: String? = null

    fun loadModelIfNeeded(model: ModelEntity) {
        // Guard: skip if this model is already loaded and we're ready
        if (model.id == loadedModelId && _uiState.value == UiState.Ready) return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = UiState.LoadingModel
            LlamaWrapper.unloadModel()
            chatHistory.clear()
            loadedModelId = null

            val params = InferenceParams.forModel(
                when {
                    model.sizeBytes < 800_000_000L   -> com.localai.llm.ModelSize.TINY
                    model.sizeBytes < 2_000_000_000L -> com.localai.llm.ModelSize.SMALL
                    else                             -> com.localai.llm.ModelSize.MEDIUM
                }
            )
            currentParams = params
            template = PromptFormatter.detectTemplate(model.fileName)

            val result = LlamaWrapper.loadModel(model.filePath, params)
            if (result.isSuccess) {
                loadedModelId = model.id
                _uiState.value = UiState.Ready
                // NOTE: do NOT call updateLastUsed here — it triggers observeActive() re-emission
                addSystemMessage("${model.displayName} loaded. Context ${params.contextSize} tokens.")
            } else {
                _uiState.value = UiState.Error(result.exceptionOrNull()?.message ?: "Load failed")
            }
        }
    }

    // ── Chat ─────────────────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        if (_uiState.value == UiState.Generating) return
        if (!LlamaWrapper.isModelLoaded()) {
            addSystemMessage("⚠ No model loaded. Tap the model icon to select one.")
            return
        }

        // Add user message
        val userMsg = ChatMessage(role = ChatRole.USER, content = text)
        _messages.update { it + userMsg }
        chatHistory.add(PromptFormatter.Message(PromptFormatter.Role.USER, text))

        // Trim history if context is getting full (keep last 10 exchanges)
        while (chatHistory.size > 20) chatHistory.removeAt(0)

        // Start AI response
        val assistantMsg = ChatMessage(role = ChatRole.ASSISTANT, content = "", isLoading = true)
        _messages.update { it + assistantMsg }
        _uiState.value = UiState.Generating

        generateJob = viewModelScope.launch {
            val prompt = PromptFormatter.format(chatHistory, template, currentParams.systemPrompt)
            val sb = StringBuilder()
            var tokenCount = 0
            val startTime = System.currentTimeMillis()

            LlamaWrapper.generateTokenStream(prompt, currentParams)
                .catch { e ->
                    Log.e(TAG, "Generation error: ${e.message}")
                    updateLastMessage("[Error: ${e.message}]", loading = false)
                    _uiState.value = UiState.Ready
                }
                .collect { token ->
                    sb.append(token)
                    tokenCount++
                    updateLastMessage(sb.toString(), loading = true)

                    // Update token speed every 10 tokens
                    if (tokenCount % 10 == 0) {
                        val elapsed = (System.currentTimeMillis() - startTime) / 1000f
                        _tokenSpeed.value = if (elapsed > 0) tokenCount / elapsed else 0f
                    }
                }

            // Finalize
            val finalText = sb.toString().trim()
            updateLastMessage(finalText, loading = false)
            chatHistory.add(PromptFormatter.Message(PromptFormatter.Role.ASSISTANT, finalText))
            _uiState.value = UiState.Ready

            val elapsed = (System.currentTimeMillis() - startTime) / 1000f
            _tokenSpeed.value = if (elapsed > 0) tokenCount / elapsed else 0f
        }
    }

    fun stopGeneration() {
        generateJob?.cancel()
        LlamaWrapper.abortGeneration()
        updateLastMessage(
            _messages.value.lastOrNull()?.content?.takeIf { it.isNotBlank() } ?: "[Stopped]",
            loading = false
        )
        _uiState.value = UiState.Ready
    }

    fun clearChat() {
        chatHistory.clear()
        _messages.value = emptyList()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun updateLastMessage(content: String, loading: Boolean) {
        _messages.update { list ->
            if (list.isEmpty()) list
            else list.dropLast(1) + list.last().copy(content = content, isLoading = loading)
        }
    }

    private fun addSystemMessage(text: String) {
        _messages.update { it + ChatMessage(role = ChatRole.SYSTEM, content = text) }
    }

    override fun onCleared() {
        super.onCleared()
        LlamaWrapper.unloadModel()
    }
}

// ── Chat message data classes ────────────────────────────────────────────────

enum class ChatRole { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val id: Long        = System.nanoTime(),
    val role: ChatRole,
    val content: String,
    val isLoading: Boolean = false,
    val timestamp: Long    = System.currentTimeMillis()
)
