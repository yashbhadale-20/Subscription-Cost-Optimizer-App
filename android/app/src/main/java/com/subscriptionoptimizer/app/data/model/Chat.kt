package com.subscriptionoptimizer.app.data.model

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val timestampMillis: Long,
    /** Policy chunks retrieved for this reply (assistant messages only). */
    val evidence: List<PolicyEvidence> = emptyList(),
    val unsupportedCitations: List<Int> = emptyList(),
)

data class ChatReply(
    val text: String,
    val evidence: List<PolicyEvidence>,
    val citedChunks: List<Int>,
    val unsupportedCitations: List<Int>,
    /** The server had lost this thread's working memory and rebuilt it from the saved transcript. */
    val restoredHistory: Boolean,
)

data class ConversationSummary(
    val threadId: String,
    val title: String,
    val messageCount: Int,
    val updatedAtMillis: Long,
)
