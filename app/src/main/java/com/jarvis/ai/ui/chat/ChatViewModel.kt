package com.jarvis.ai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.ai.core.ai.AIMessage
import com.jarvis.ai.core.ai.AIRouter
import com.jarvis.ai.core.ai.TaskKind
import com.jarvis.ai.data.db.ConversationDao
import com.jarvis.ai.data.db.entities.MessageEntity
import com.jarvis.ai.ui.orb.OrbState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<MessageEntity> = emptyList(),
    val orbState: OrbState = OrbState.IDLE,
    val inputText: String = "",
    val errorMessage: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val aiRouter: AIRouter,
    private val conversationDao: ConversationDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            conversationDao.observeAll().collect { messages ->
                _uiState.value = _uiState.value.copy(messages = messages)
            }
        }
    }

    fun onInputChange(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return
        _uiState.value = _uiState.value.copy(inputText = "", orbState = OrbState.THINKING)

        viewModelScope.launch {
            conversationDao.insert(MessageEntity(role = "user", content = text, providerName = null))
            try {
                val history = _uiState.value.messages.map { AIMessage(it.role, it.content) } + AIMessage("user", text)
                val response = aiRouter.route(TaskKind.CHAT, history)
                conversationDao.insert(
                    MessageEntity(role = "assistant", content = response.text, providerName = response.providerName)
                )
                _uiState.value = _uiState.value.copy(orbState = OrbState.SPEAKING)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(orbState = OrbState.ERROR, errorMessage = e.message)
            }
        }
    }
}
