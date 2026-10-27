package io.github.jackwener.dsh.core.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.capability.CapabilityHub
import io.github.jackwener.dsh.core.data.EventEntity
import io.github.jackwener.dsh.core.data.RuleDao
import io.github.jackwener.dsh.core.data.SessionDao
import io.github.jackwener.dsh.core.data.SessionEntity
import io.github.jackwener.dsh.core.data.UsageDao
import io.github.jackwener.dsh.core.data.UsageEntity
import io.github.jackwener.dsh.core.engine.EngineManager
import io.github.jackwener.dsh.core.engine.JsonRpcException
import io.github.jackwener.dsh.core.engine.RpcJson
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.RuleSpec
import io.github.jackwener.dsh.core.model.ruleIdOfSession
import io.github.jackwener.dsh.core.platform.AppVisibility
import io.github.jackwener.dsh.core.platform.Notifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class ApprovalOutcome { AllowOnce, AllowSession, Reject }

/**
 * Conversations: sends prompts, folds `session.event`s into UI state (persisted for replay),
 * and answers dsh's server requests (approvals, user questions).
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: EngineManager,
    private val dao: SessionDao,
    private val rules: RuleDao,
    private val usage: UsageDao,
    private val capabilities: CapabilityHub,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = ConcurrentHashMap<String, MutableStateFlow<ConversationState>>()
    private val loadLock = Mutex()
    private val approvals = ConcurrentHashMap<String, CompletableDeferred<ApprovalOutcome>>()
    private val questions = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val sessionGrants = ConcurrentHashMap<String, MutableSet<String>>()

    val conversations: Flow<List<SessionEntity>> = dao.observeAttended()

    suspend fun state(sessionId: String): StateFlow<ConversationState> = flowOf(sessionId)

    private suspend fun flowOf(sessionId: String): MutableStateFlow<ConversationState> = loadLock.withLock {
        states[sessionId]?.let { return it }
        val entity = dao.get(sessionId)
        var s = ConversationState(sessionId, title = entity?.title ?: "")
        dao.events(sessionId).forEach { e ->
            s = EventReducer.reduce(s, e.type, e.seq, RpcJson.parseToJsonElement(e.json).jsonObject)
        }
        MutableStateFlow(s.copy(running = entity?.running == true)).also { states[sessionId] = it }
    }

    fun hasRunning() = states.values.any { it.value.running }

    fun newSessionId(): String = UUID.randomUUID().toString()

    /** Sends a user prompt; creates the session row on first use. */
    suspend fun prompt(sessionId: String, text: String, ruleId: Long? = null, imagesBase64: List<Pair<String, String>> = emptyList()) {
        val now = System.currentTimeMillis()
        if (dao.get(sessionId) == null) dao.upsert(SessionEntity(sessionId, text.take(40), now, now, ruleId, running = true))
        else dao.setRunning(sessionId, true)
        val flow = flowOf(sessionId)
        flow.update { it.copy(running = true) }
        engine.cancelIdleShutdown()
        val sdk = engine.sdk()
        sdk.request("session/prompt", buildJsonObject {
            put("sessionId", sessionId)
            putJsonArray("contentBlocks") {
                addJsonObject { put("type", "text"); put("text", text) }
                imagesBase64.forEach { (mime, data) -> addJsonObject { put("type", "image"); put("mimeType", mime); put("data", data) } }
            }
        })
    }

    suspend fun delete(sessionId: String) {
        dao.delete(sessionId); dao.deleteEvents(sessionId); states.remove(sessionId)
    }

    // ---- SDK callbacks -------------------------------------------------------------------------

    suspend fun onNotification(method: String, params: JsonObject) {
        val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
        when (method) {
            "session.event" -> {
                val event = params["event"]?.jsonObject ?: return
                val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return
                val seq = event["seq"]?.jsonPrimitive?.longOrNull ?: return
                val data = event["data"]?.jsonObject ?: JsonObject(emptyMap())
                dao.insertEvent(EventEntity(sessionId, seq, type, data.toString()))
                flowOf(sessionId).update { EventReducer.reduce(it, type, seq, data) }
                if (type == "session/title") data["title"]?.jsonPrimitive?.contentOrNull?.let { dao.setTitle(sessionId, it) }
                if (type == "assistant/message") recordUsage(data)
            }
            "session.status" -> {
                val running = params["status"]?.jsonPrimitive?.contentOrNull == "running"
                dao.setRunning(sessionId, running)
                flowOf(sessionId).update { it.copy(running = running) }
                if (!running) onTurnFinished(sessionId)
            }
        }
    }

    suspend fun onServerRequest(method: String, params: JsonObject): JsonElement = when (method) {
        "approval/request" -> buildJsonObject { put("outcome", approve(params)) }
        "user-question/ask" -> ask(params)
        else -> throw JsonRpcException(-32601, "unsupported $method")
    }

    private suspend fun approve(p: JsonObject): String {
        // Subagent requests carry the child session plus rootSessionId; approvals belong to the root conversation.
        val sessionId = (p["rootSessionId"] ?: p["sessionId"])?.jsonPrimitive?.contentOrNull ?: return "rejected"
        val tool = p["toolName"]?.jsonPrimitive?.contentOrNull ?: return "rejected"
        val ruleId = ruleIdOfSession(sessionId)
        if (ruleId != null) {
            // Unattended: never prompt; allow only what the rule's owner approved.
            val spec = rules.get(ruleId)?.let { RpcJson.decodeFromString(RuleSpec.serializer(), it.specJson) }
            return if (spec != null && tool in spec.allowedTools) "allowed-once" else "rejected"
        }
        if (sessionGrants[sessionId]?.contains(tool) == true) return "allowed-once"
        val key = UUID.randomUUID().toString()
        val risk = capabilities.descriptor(tool)?.risk ?: Risk.write
        val args = state(sessionId).value.items.filterIsInstance<ChatItem.Tool>()
            .lastOrNull { it.callId == p["callId"]?.jsonPrimitive?.contentOrNull }?.arguments
        val deferred = CompletableDeferred<ApprovalOutcome>()
        approvals[key] = deferred
        flowOf(sessionId).update {
            it.copy(approval = PendingApproval(key, sessionId, tool, p["callId"]?.jsonPrimitive?.contentOrNull,
                p["reason"]?.jsonPrimitive?.contentOrNull, args, risk))
        }
        if (!AppVisibility.isForeground) Notifications.post(context, Notifications.CHANNEL_ATTENTION, sessionId.hashCode(),
            context.getString(R.string.notif_approval_title), context.getString(R.string.notif_approval_text, tool), sessionId)
        val outcome = try { deferred.await() } finally {
            approvals.remove(key)
            flowOf(sessionId).update { if (it.approval?.requestKey == key) it.copy(approval = null) else it }
        }
        return when (outcome) {
            ApprovalOutcome.AllowOnce -> "allowed-once"
            ApprovalOutcome.AllowSession -> { sessionGrants.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }.add(tool); "allowed-once" }
            ApprovalOutcome.Reject -> "rejected"
        }
    }

    fun answerApproval(requestKey: String, outcome: ApprovalOutcome) { approvals[requestKey]?.complete(outcome) }

    private suspend fun ask(p: JsonObject): JsonElement {
        val sessionId = p["sessionId"]?.jsonPrimitive?.contentOrNull ?: throw JsonRpcException(-32602, "no session")
        if (ruleIdOfSession(sessionId) != null) throw JsonRpcException(-32000, "PROVIDER_ERROR: unattended session cannot ask the user")
        val key = p["requestId"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
        val qs = p["questions"]?.jsonArray?.map {
            val o = it.jsonObject
            PendingQuestion.Question(
                id = o["id"]!!.jsonPrimitive.content,
                text = (o["question"] ?: o["text"] ?: o["prompt"])?.jsonPrimitive?.contentOrNull ?: "",
                options = o["options"]?.jsonArray?.map { opt ->
                    (opt as? JsonObject)?.let { oo -> (oo["label"] ?: oo["id"])?.jsonPrimitive?.contentOrNull } ?: opt.jsonPrimitive.content
                } ?: emptyList(),
                multi = o["multiSelect"]?.jsonPrimitive?.booleanOrNull == true,
            )
        } ?: emptyList()
        val deferred = CompletableDeferred<JsonElement>()
        questions[key] = deferred
        flowOf(sessionId).update { it.copy(question = PendingQuestion(key, sessionId, qs)) }
        return try { deferred.await() } finally {
            questions.remove(key)
            flowOf(sessionId).update { if (it.question?.requestKey == key) it.copy(question = null) else it }
        }
    }

    /** answers: questionId -> selected labels (or a single custom text). */
    fun answerQuestion(requestKey: String, answers: Map<String, Pair<List<String>, String?>>) {
        questions[requestKey]?.complete(buildJsonObject {
            putJsonArray("answers") {
                answers.forEach { (id, a) -> addJsonObject {
                    put("id", id)
                    put("selected", buildJsonArray { a.first.forEach { add(it) } })
                    a.second?.let { put("custom", it) }
                } }
            }
        })
    }

    /** Engine refused a step because a token/cost budget is exhausted. */
    suspend fun onBudgetExceeded(p: JsonObject) {
        val sessionId = p["sessionId"]?.jsonPrimitive?.contentOrNull ?: return
        val text = "Budget limit reached (${p["kind"]?.jsonPrimitive?.contentOrNull}: ${p["used"]?.jsonPrimitive?.contentOrNull} / ${p["limit"]?.jsonPrimitive?.contentOrNull})"
        flowOf(sessionId).update { it.copy(items = it.items + ChatItem.Notice("budget${System.currentTimeMillis()}", text, isError = true)) }
    }

    suspend fun setWaitingForUnlock(sessionId: String, waiting: Boolean) =
        flowOf(sessionId).update { it.copy(waitingForUnlock = waiting) }

    private fun onTurnFinished(sessionId: String) {
        if (states.values.none { it.value.running }) engine.scheduleIdleShutdown()
        if (!AppVisibility.isForeground) scope.launch {
            val s = state(sessionId).value
            val last = s.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant ?: return@launch
            Notifications.post(context, Notifications.CHANNEL_TASKS, sessionId.hashCode(),
                s.title.ifBlank { context.getString(R.string.app_name) }, last.text.take(300), sessionId)
        }
    }

    /** The engine died: mark sessions idle and resume the ones that were running. */
    suspend fun onEngineLost() {
        val running = dao.running()
        states.values.forEach { f -> f.update { it.copy(running = false, approval = null, question = null) } }
        approvals.values.forEach { it.complete(ApprovalOutcome.Reject) }
        running.forEach { s ->
            runCatching {
                engine.sdk().request("session/resume", buildJsonObject { put("sessionId", s.id) })
                dao.setRunning(s.id, false)
            }
        }
    }

    private suspend fun recordUsage(data: JsonObject) {
        val u = data["usage"]?.jsonObject ?: return
        fun n(vararg keys: String) = keys.firstNotNullOfOrNull { u[it]?.jsonPrimitive?.longOrNull } ?: 0L
        val day = LocalDate.now().toString()
        val cur = usage.get(day) ?: UsageEntity(day, 0, 0, 0)
        usage.upsert(cur.copy(
            inputTokens = cur.inputTokens + n("input", "inputTokens", "prompt_tokens"),
            outputTokens = cur.outputTokens + n("output", "outputTokens", "completion_tokens"),
            cachedTokens = cur.cachedTokens + n("cacheRead", "cachedInput", "prompt_cache_hit_tokens"),
        ))
    }
}
