package io.github.jackwener.dsh.core.session

import io.github.jackwener.dsh.core.model.PermissionPreset
import io.github.jackwener.dsh.core.model.Risk
import kotlinx.serialization.json.JsonObject

/** One entry of a session's event log: a dsh `session.event`, or an app-local `app/...` record. */
data class LoggedEvent(val seq: Long, val type: String, val time: Long, val data: JsonObject)

data class PendingApproval(
    val requestKey: String,
    val sessionId: String,
    val toolName: String,
    val callId: String?,
    val reason: String?,
    val arguments: String?,
    val risk: Risk,
    /** dsh shows 始终允许 only when the host offers it; we offer a per-session grant except for dangerous tools. */
    val canAlwaysAllow: Boolean,
)

data class PendingQuestion(
    val requestKey: String,
    val sessionId: String,
    val questions: List<Question>,
) {
    data class Option(val label: String, val description: String?)
    data class Question(
        val id: String,
        val header: String?,
        val text: String,
        val detail: String?,
        val options: List<Option>,
        val multi: Boolean,
    )
}

/** A device tool hit a missing Android permission; the call is held until the user grants or declines. */
data class PendingPermission(
    val requestKey: String,
    val sessionId: String,
    val toolName: String,
    /** PermissionCatalog id, or null when only a description is known (e.g. Shizuku). */
    val permissionId: String?,
    val what: String,
)

data class QueuedMessage(val id: String, val text: String)

/** Live state of one session: the persisted log plus everything that only exists while the app runs. */
data class SessionLive(
    val sessionId: String,
    val title: String = "",
    val events: List<LoggedEvent> = emptyList(),
    val running: Boolean = false,
    /** Set when this session's run is waiting for the engine process to come up. */
    val startingEngine: Boolean = false,
    val runStartedAt: Long? = null,
    val approval: PendingApproval? = null,
    val question: PendingQuestion? = null,
    val permission: PendingPermission? = null,
    val waitingForUnlock: Boolean = false,
    val queue: List<QueuedMessage> = emptyList(),
    val preset: PermissionPreset = PermissionPreset.Ask,
    val exists: Boolean = false,
)

/** What the sidebar status slot shows (dsh StateDot precedence). */
enum class RowStatus { Approval, Question, Running, Idle }
