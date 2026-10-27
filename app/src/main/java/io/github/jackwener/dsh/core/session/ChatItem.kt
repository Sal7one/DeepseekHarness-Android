package io.github.jackwener.dsh.core.session

import io.github.jackwener.dsh.core.model.Risk

sealed interface ChatItem {
    val key: String
    data class User(override val key: String, val text: String) : ChatItem
    data class Assistant(override val key: String, val text: String, val reasoning: String?) : ChatItem
    data class Tool(
        override val key: String,
        val callId: String,
        val name: String,
        val arguments: String,
        val status: ToolStatus,
        val result: String? = null,
        val imageBase64: String? = null,
    ) : ChatItem
    data class Notice(override val key: String, val text: String, val isError: Boolean) : ChatItem
}

enum class ToolStatus { Running, Done, Failed, Rejected }

data class PendingApproval(
    val requestKey: String,
    val sessionId: String,
    val toolName: String,
    val callId: String?,
    val reason: String?,
    val arguments: String?,
    val risk: Risk,
)

data class PendingQuestion(
    val requestKey: String,
    val sessionId: String,
    val questions: List<Question>,
) {
    data class Question(val id: String, val text: String, val options: List<String>, val multi: Boolean)
}

data class ConversationState(
    val sessionId: String,
    val title: String = "",
    val items: List<ChatItem> = emptyList(),
    val running: Boolean = false,
    val approval: PendingApproval? = null,
    val question: PendingQuestion? = null,
    val waitingForUnlock: Boolean = false,
)
