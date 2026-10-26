package com.subscriptionoptimizer.app.ui.assistant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.subscriptionoptimizer.app.appViewModelFactory
import com.subscriptionoptimizer.app.data.model.ChatMessage
import com.subscriptionoptimizer.app.data.model.ChatRole
import com.subscriptionoptimizer.app.data.model.ConversationSummary
import com.subscriptionoptimizer.app.data.repository.ChatRepository
import com.subscriptionoptimizer.app.data.repository.RepositoryError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ChatError(
    val title: String,
    val message: String,
    /** The user message that was not answered; retrying resends it. */
    val failedMessageId: String,
)

/** The saved-conversations sheet. */
data class ConversationsState(
    val visible: Boolean = false,
    val isLoading: Boolean = false,
    val items: List<ConversationSummary> = emptyList(),
    val error: RepositoryError? = null,
)

data class AssistantUiState(
    val threadId: String = newThreadId(),
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
    val error: ChatError? = null,
    /** Loading a saved conversation into the chat. */
    val isLoadingThread: Boolean = false,
    val threadError: RepositoryError? = null,
    /** A one-off note, e.g. that the server rebuilt this thread from its saved transcript. */
    val notice: String? = null,
    val conversations: ConversationsState = ConversationsState(),
) {
    val canSend: Boolean get() = input.isNotBlank() && !isSending && !isLoadingThread && input.length <= MAX_MESSAGE

    companion object {
        /** Mirrors the backend's `ChatRequest.message` limit. */
        const val MAX_MESSAGE = 1_000
    }
}

private fun newThreadId() = "android-" + UUID.randomUUID().toString().take(8)

class AssistantViewModel(
    private val chat: ChatRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    val suggestedPrompts = listOf(
        "Is an annual plan cheaper than paying monthly?",
        "How do introductory discounts affect my yearly cost?",
        "What should I check before cancelling a subscription?",
        "Which costs aren't included in a comparison?",
    )

    fun onInputChange(text: String) = _uiState.update { it.copy(input = text) }

    fun send(text: String = _uiState.value.input) {
        val trimmed = text.trim()
        val state = _uiState.value
        if (trimmed.isEmpty() || state.isSending || state.isLoadingThread || trimmed.length > AssistantUiState.MAX_MESSAGE) return
        val message = ChatMessage(UUID.randomUUID().toString(), ChatRole.USER, trimmed, clock())
        _uiState.update { it.copy(messages = it.messages + message, input = "", error = null, notice = null) }
        request(message)
    }

    fun retry() {
        val error = _uiState.value.error ?: return
        val message = _uiState.value.messages.firstOrNull { it.id == error.failedMessageId } ?: return
        _uiState.update { it.copy(error = null) }
        request(message)
    }

    fun newConversation() {
        _uiState.update { AssistantUiState(conversations = it.conversations.copy(visible = false)) }
    }

    fun dismissNotice() = _uiState.update { it.copy(notice = null) }

    fun showConversations() {
        _uiState.update { it.copy(conversations = it.conversations.copy(visible = true, isLoading = true, error = null)) }
        viewModelScope.launch {
            chat.conversations()
                .onSuccess { items ->
                    _uiState.update { it.copy(conversations = it.conversations.copy(isLoading = false, items = items)) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(conversations = it.conversations.copy(isLoading = false, error = e.asRepositoryError()))
                    }
                }
        }
    }

    fun hideConversations() = _uiState.update { it.copy(conversations = it.conversations.copy(visible = false)) }

    fun openConversation(threadId: String) {
        _uiState.update {
            it.copy(
                threadId = threadId, messages = emptyList(), error = null, notice = null,
                isLoadingThread = true, threadError = null,
                conversations = it.conversations.copy(visible = false),
            )
        }
        viewModelScope.launch {
            chat.messages(threadId)
                .onSuccess { messages ->
                    _uiState.update { if (it.threadId == threadId) it.copy(messages = messages, isLoadingThread = false) else it }
                }
                .onFailure { e ->
                    _uiState.update {
                        if (it.threadId == threadId) it.copy(isLoadingThread = false, threadError = e.asRepositoryError()) else it
                    }
                }
        }
    }

    fun deleteConversation(threadId: String) {
        viewModelScope.launch {
            chat.delete(threadId)
                .onSuccess {
                    _uiState.update { state ->
                        val list = state.conversations.copy(items = state.conversations.items.filterNot { it.threadId == threadId })
                        if (state.threadId == threadId) AssistantUiState(conversations = list) else state.copy(conversations = list)
                    }
                }
                .onFailure { e -> _uiState.update { it.copy(conversations = it.conversations.copy(error = e.asRepositoryError())) } }
        }
    }

    private fun request(message: ChatMessage) {
        val threadId = _uiState.value.threadId
        _uiState.update { it.copy(isSending = true) }
        viewModelScope.launch {
            chat.send(threadId, message.text)
                .onSuccess { reply ->
                    val answer = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        role = ChatRole.ASSISTANT,
                        text = reply.text,
                        timestampMillis = clock(),
                        evidence = reply.evidence,
                        unsupportedCitations = reply.unsupportedCitations,
                    )
                    _uiState.update {
                        it.copy(
                            messages = it.messages + answer,
                            isSending = false,
                            notice = if (reply.restoredHistory) {
                                "The server had lost this conversation's working memory (for example after a " +
                                    "restart) and rebuilt it from the saved transcript."
                            } else {
                                null
                            },
                        )
                    }
                }
                .onFailure { e ->
                    val error = e.asRepositoryError()
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            error = ChatError(
                                title = "Message not answered: ${error.title}",
                                message = error.message ?: "Something went wrong.",
                                failedMessageId = message.id,
                            ),
                        )
                    }
                }
        }
    }

    private fun Throwable.asRepositoryError(): RepositoryError =
        this as? RepositoryError ?: RepositoryError.MalformedResponse(message ?: "Something went wrong.")

    companion object {
        val Factory = appViewModelFactory { AssistantViewModel(it.chatRepository) }
    }
}
