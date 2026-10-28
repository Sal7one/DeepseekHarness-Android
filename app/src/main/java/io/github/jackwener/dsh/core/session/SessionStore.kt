package io.github.jackwener.dsh.core.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.capability.CapabilityHub
import io.github.jackwener.dsh.core.capability.ShotStore
import io.github.jackwener.dsh.core.data.EventEntity
import io.github.jackwener.dsh.core.data.RuleDao
import io.github.jackwener.dsh.core.data.SessionDao
import io.github.jackwener.dsh.core.data.SessionEntity
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.data.UsageDao
import io.github.jackwener.dsh.core.data.UsageEntity
import io.github.jackwener.dsh.core.engine.EngineBackgroundStartException
import io.github.jackwener.dsh.core.engine.EngineManager
import io.github.jackwener.dsh.core.engine.EngineState
import io.github.jackwener.dsh.core.engine.TurnWakeLock
import io.github.jackwener.dsh.core.engine.EngineStartException
import io.github.jackwener.dsh.core.engine.JsonRpcException
import io.github.jackwener.dsh.core.engine.NoCredentialsException
import io.github.jackwener.dsh.core.engine.RpcJson
import io.github.jackwener.dsh.core.model.PermissionPreset
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.RuleSpec
import io.github.jackwener.dsh.core.model.ruleIdOfSession
import io.github.jackwener.dsh.core.platform.AppVisibility
import io.github.jackwener.dsh.core.platform.DeferredRuns
import io.github.jackwener.dsh.core.platform.Notifications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class ApprovalOutcome { AllowOnce, AllowSession, Reject }

/**
 * Sessions: sends prompts, persists every `session.event` (plus app-local error/stop records) as
 * the single log both the conversation and trajectory views project from, keeps the app-side
 * message queue, and answers dsh's server requests (approvals, user questions) and the
 * capability layer's just-in-time permission requests.
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: EngineManager,
    private val dao: SessionDao,
    private val rules: RuleDao,
    private val usage: UsageDao,
    private val settings: SettingsRepository,
    private val capabilities: CapabilityHub,
    private val wakeLock: TurnWakeLock,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = ConcurrentHashMap<String, MutableStateFlow<SessionLive>>()
    private val loadLock = Mutex()
    private val approvals = ConcurrentHashMap<String, CompletableDeferred<ApprovalOutcome>>()
    private val questions = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val permissions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val takeovers = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    /** Sessions whose interrupted turn was already continued automatically once (cleared when a turn ends normally). */
    private val autoContinued = ConcurrentHashMap.newKeySet<String>()
    private val sessionGrants = ConcurrentHashMap<String, MutableSet<String>>()
    /** sessionId -> engine generation it was last resumed/created in. */
    private val resumedIn = ConcurrentHashMap<String, Int>()
    private val localSeq = AtomicLong(-System.currentTimeMillis())
    @Volatile private var viewing: String? = null

    private val _status = MutableStateFlow<Map<String, RowStatus>>(emptyMap())
    /** Live sidebar status per session (approval / question / running). */
    val status: StateFlow<Map<String, RowStatus>> = _status.asStateFlow()

    val conversations: Flow<List<SessionEntity>> = dao.observeAttended()

    init {
        // Rows still marked running belong to a previous app process (both processes died): the
        // turn is gone, so show it as interrupted with 继续 rather than silently idle.
        scope.launch {
            dao.running().forEach { row ->
                dao.setRunning(row.id, false)
                if (row.parentId == null) appendLocal(row.id, "app/error", buildJsonObject {
                    put("kind", "engine_lost"); put("message", context.getString(R.string.err_interrupted_previous))
                })
            }
        }
    }
    fun observeSession(id: String): Flow<SessionEntity?> = dao.observe(id)

    suspend fun state(sessionId: String): StateFlow<SessionLive> = flowOf(sessionId)

    private suspend fun flowOf(sessionId: String): MutableStateFlow<SessionLive> = loadLock.withLock {
        states[sessionId]?.let { return it }
        val entity = dao.get(sessionId)
        val events = dao.events(sessionId).map { it.toLogged() }
        val preset = entity?.preset?.let { PermissionPreset.of(it) } ?: settings.current().defaultPreset
        MutableStateFlow(SessionLive(sessionId, entity?.title ?: "", events, running = false, preset = preset, exists = entity != null))
            .also { states[sessionId] = it }
    }

    private suspend fun mutate(sessionId: String, f: (SessionLive) -> SessionLive) {
        val flow = flowOf(sessionId)
        flow.update(f)
        val s = flow.value
        val st = when {
            s.approval != null || s.permission != null || s.takeover != null -> RowStatus.Approval
            s.question != null -> RowStatus.Question
            s.running -> RowStatus.Running
            else -> RowStatus.Idle
        }
        _status.update { if (it[sessionId] == st) it else it + (sessionId to st) }
        // CPU stays awake only while some turn is actively running (not while it waits for the user).
        if (_status.value.values.any { it == RowStatus.Running }) wakeLock.hold() else wakeLock.release()
    }

    private fun EventEntity.toLogged() = LoggedEvent(seq, type, time, RpcJson.parseToJsonElement(json) as JsonObject)

    fun hasRunning() = states.values.any { it.value.running }

    /** Runs the engine is executing now (a prompt still waiting for the engine to start is not one). */
    fun hasEngineRuns() = states.values.any { it.value.running && !it.value.startingEngine }

    fun newSessionId(): String = UUID.randomUUID().toString()

    /** Which session the user is looking at (clears its completed-unread dot). */
    fun setViewing(sessionId: String?) {
        viewing = sessionId
        if (sessionId != null) scope.launch { dao.setUnread(sessionId, false) }
    }

    suspend fun childEvents(sessionId: String): List<List<LoggedEvent>> =
        dao.children(sessionId).map { c -> dao.events(c.id).map { it.toLogged() } }

    // ---- sending ---------------------------------------------------------------------------------

    /**
     * User send from the composer. While a run is active the message waits in the app-side queue
     * (dsh 排队发送) unless [now] is set, in which case it goes to the engine immediately and dsh
     * claims it at its next turn boundary.
     */
    suspend fun send(sessionId: String, text: String, images: List<Pair<String, String>> = emptyList(), preset: PermissionPreset? = null, now: Boolean = false) {
        val live = flowOf(sessionId).value
        if (live.running && !now && images.isEmpty()) {
            mutate(sessionId) { it.copy(queue = it.queue + QueuedMessage(UUID.randomUUID().toString(), text)) }
            return
        }
        if (preset != null && !live.exists) mutate(sessionId) { it.copy(preset = preset) }
        prompt(sessionId, text, images = images)
    }

    fun removeQueued(sessionId: String, id: String) = scope.launch { mutate(sessionId) { s -> s.copy(queue = s.queue.filterNot { it.id == id }) } }

    suspend fun sendQueuedNow(sessionId: String, id: String) {
        val q = flowOf(sessionId).value.queue.firstOrNull { it.id == id } ?: return
        mutate(sessionId) { s -> s.copy(queue = s.queue.filterNot { it.id == id }) }
        prompt(sessionId, q.text)
    }

    /**
     * Sends a prompt to the engine; creates the session row on first use. Never throws for
     * engine problems: a failure becomes an `app/error` record in the log (shown as a failed
     * turn with a retry affordance) and the session goes back to idle.
     */
    suspend fun prompt(sessionId: String, text: String, ruleId: Long? = null, images: List<Pair<String, String>> = emptyList()) {
        val now = System.currentTimeMillis()
        val existing = dao.get(sessionId)
        val live = flowOf(sessionId).value
        if (existing == null) dao.upsert(SessionEntity(sessionId, text.lineSequence().first().take(40), now, now, ruleId, running = true, preset = live.preset.id))
        else dao.setRunning(sessionId, true)
        mutate(sessionId) { it.copy(running = true, exists = true, runStartedAt = now, startingEngine = true) }
        engine.cancelIdleShutdown()
        if (ruleIdOfSession(sessionId) != null && engine.restarts.open && engine.state.value != EngineState.Ready) {
            // Circuit breaker open: automations do not restart a crash-looping engine (Doctor shows why).
            fail(sessionId, "engine", context.getString(R.string.err_engine_crash_loop), null, text)
            return
        }
        try {
            val sdk = engine.sdk()
            mutate(sessionId) { it.copy(startingEngine = false) }
            engine.markSessionOpened()
            if (live.events.isNotEmpty() && resumedIn[sessionId] != engine.generation) {
                runCatching { sdk.request("session/resume", buildJsonObject { put("sessionId", sessionId) }) }
            }
            resumedIn[sessionId] = engine.generation
            sdk.request("session/prompt", buildJsonObject {
                put("sessionId", sessionId)
                putJsonArray("contentBlocks") {
                    addJsonObject { put("type", "text"); put("text", text) }
                    images.forEach { (mime, data) -> addJsonObject { put("type", "image"); put("mimeType", mime); put("data", data) } }
                }
            })
        } catch (e: NoCredentialsException) {
            fail(sessionId, "no_credentials", e.message ?: "", null, text)
        } catch (e: EngineBackgroundStartException) {
            // Android 12+ refused the engine's foreground service from the background (e.g. battery
            // optimization re-enabled): degrade to a notification that runs the task when tapped.
            val rule = ruleId ?: ruleIdOfSession(sessionId)
            fail(sessionId, "engine", context.getString(R.string.err_bg_start), null, text)
            val label = rule?.let { rules.get(it) }?.let { runCatching { RpcJson.decodeFromString(RuleSpec.serializer(), it.specJson).name }.getOrNull() }
                ?: dao.get(sessionId)?.title ?: text.take(40)
            DeferredRuns.post(context, sessionId, text, rule, label)
        } catch (e: EngineStartException) {
            fail(sessionId, "engine", e.message ?: "", e.log, text)
        } catch (e: JsonRpcException) {
            fail(sessionId, "engine", e.message ?: "", e.code.toString(), text)
        } catch (e: Exception) {
            fail(sessionId, "engine", e.message ?: e.javaClass.simpleName, null, text)
        }
    }

    /** Fire-and-forget [prompt] in the store's own scope (callers that are about to go away, e.g. a notification trampoline). */
    fun launchPrompt(sessionId: String, text: String, ruleId: Long? = null) { scope.launch { prompt(sessionId, text, ruleId) } }

    /** Re-sends after a failure: restarts the engine if it is wedged, then retries the prompt (or asks to continue). */
    suspend fun retry(sessionId: String, error: TurnError) {
        if (error.kind == "engine" || error.kind == "engine_lost") engine.stop()
        prompt(sessionId, error.retryText ?: context.getString(R.string.retry_continue_prompt))
    }

    private suspend fun fail(sessionId: String, kind: String, message: String, code: String?, retryText: String?) {
        dao.setRunning(sessionId, false)
        appendLocal(sessionId, "app/error", buildJsonObject {
            put("kind", kind); put("message", message)
            code?.let { put("code", it) }
            retryText?.let { put("retryText", it) }
        })
        mutate(sessionId) { it.copy(running = false, startingEngine = false, runStartedAt = null) }
        if (!hasRunning()) engine.scheduleIdleShutdown()
    }

    private suspend fun appendLocal(sessionId: String, type: String, data: JsonObject) {
        // The prompt failure and the channel-closed callback can both report the same crash.
        val last = flowOf(sessionId).value.events.lastOrNull()
        if (type == "app/error" && last?.type == "app/error" && System.currentTimeMillis() - last.time < 10_000) return
        val e = LoggedEvent(localSeq.decrementAndGet(), type, System.currentTimeMillis(), data)
        if (dao.get(sessionId) != null) dao.insertEvent(EventEntity(sessionId, e.seq, type, data.toString(), e.time))
        mutate(sessionId) { it.copy(events = it.events + e) }
    }

    /** Stops every run (the SDK has no per-session cancel, so this stops the engine process). */
    @Volatile private var stopping = false

    suspend fun stop() {
        val running = states.values.map { it.value }.filter { it.running }.map { it.sessionId }
        stopping = true
        try { engine.stop() } finally { stopping = false }
        cancelPending()
        running.forEach { id ->
            dao.setRunning(id, false)
            appendLocal(id, "app/stopped", JsonObject(emptyMap()))
            mutate(id) { it.copy(running = false, startingEngine = false, runStartedAt = null, waitingForUnlock = false) }
        }
    }

    private fun cancelPending() {
        approvals.values.forEach { it.complete(ApprovalOutcome.Reject) }
        questions.values.forEach { it.completeExceptionally(JsonRpcException(-32000, "cancelled")) }
        permissions.values.forEach { it.complete(false) }
        takeovers.values.forEach { it.complete(false) }
    }

    // ---- session management ----------------------------------------------------------------------

    suspend fun rename(sessionId: String, title: String) { dao.setTitle(sessionId, title); mutate(sessionId) { it.copy(title = title) } }
    suspend fun setPinned(sessionId: String, pinned: Boolean) = dao.setPinned(sessionId, pinned)
    suspend fun setArchived(sessionId: String, archived: Boolean) = dao.setArchived(sessionId, archived)
    suspend fun setPreset(sessionId: String, preset: PermissionPreset) {
        if (dao.get(sessionId) != null) dao.setPreset(sessionId, preset.id)
        mutate(sessionId) { it.copy(preset = preset) }
    }

    suspend fun delete(sessionId: String) {
        dao.children(sessionId).forEach { dao.delete(it.id); dao.deleteEvents(it.id) }
        dao.delete(sessionId); dao.deleteEvents(sessionId); states.remove(sessionId)
        ShotStore.deleteSession(context, sessionId)
        _status.update { it - sessionId }
    }

    // ---- SDK callbacks ---------------------------------------------------------------------------

    suspend fun onNotification(method: String, params: JsonObject) {
        if (method == "subagent.started") {
            val parent = params.s("parentSessionId") ?: return
            val child = params.s("childSessionId") ?: return
            if (dao.get(child) == null) {
                val now = System.currentTimeMillis()
                dao.upsert(SessionEntity(child, "", now, now, ruleIdOfSession(parent), parentId = parent))
            }
            return
        }
        if (method == "user-question.cancelled") { params.s("requestId")?.let(::cancelQuestion); return }
        val sessionId = params.s("sessionId") ?: return
        when (method) {
            "session.event" -> {
                val event = params.o("event") ?: return
                val type = event.s("type") ?: return
                val seq = event.l("seq") ?: return
                val time = event.l("time") ?: System.currentTimeMillis()
                if (type in UNLOGGED) return
                val data = slim(type, event.o("data") ?: JsonObject(emptyMap()))
                if (dao.get(sessionId) != null) dao.insertEvent(EventEntity(sessionId, seq, type, data.toString(), time))
                mutate(sessionId) { s -> if (s.events.any { it.seq == seq }) s else s.copy(events = s.events + LoggedEvent(seq, type, time, data)) }
                if (type == "session/title") data.s("title")?.let { rename(sessionId, it) }
                if (type == "assistant/message") recordUsage(data)
            }
            "session.status" -> {
                val running = params.s("status") == "running"
                dao.setRunning(sessionId, running)
                mutate(sessionId) { it.copy(running = running, startingEngine = false, runStartedAt = if (running) (it.runStartedAt ?: System.currentTimeMillis()) else null) }
                if (!running) onTurnFinished(sessionId)
            }
        }
    }

    /** Drops the bulky model stream from assistant events, keeping only its first-token time. */
    private fun slim(type: String, data: JsonObject): JsonObject {
        if (type != "assistant/message" && type != "assistant/attempt") return data
        val stream = data["stream"] as? JsonArray ?: return data
        val first = stream.mapNotNull { (it as? JsonObject)?.l("time0") }.minOrNull()
        return JsonObject(data.filterKeys { it != "stream" } + (if (first != null) mapOf("firstTokenTime" to JsonPrimitive(first)) else emptyMap()))
    }

    suspend fun onServerRequest(method: String, params: JsonObject): JsonElement = when (method) {
        "approval/request" -> buildJsonObject { put("outcome", approve(params)) }
        "user-question/ask" -> ask(params)
        else -> throw JsonRpcException(-32601, "unsupported $method")
    }

    private suspend fun approve(p: JsonObject): String {
        // Subagent requests carry the child session plus rootSessionId; approvals belong to the root conversation.
        val sessionId = (p["rootSessionId"] ?: p["sessionId"])?.jsonPrimitive?.contentOrNull ?: return "rejected"
        val tool = p.s("toolName") ?: return "rejected"
        val ruleId = ruleIdOfSession(sessionId)
        if (ruleId != null) {
            // Unattended: never prompt; allow only what the rule's owner approved.
            val spec = rules.get(ruleId)?.let { RpcJson.decodeFromString(RuleSpec.serializer(), it.specJson) }
            return if (spec != null && tool in spec.allowedTools) "allowed-once" else "rejected"
        }
        val live = flowOf(sessionId).value
        val risk = capabilities.descriptor(tool)?.risk ?: Risk.write
        when (live.preset) {
            PermissionPreset.ReadOnly -> return "rejected"
            PermissionPreset.Full -> if (risk != Risk.dangerous) return "allowed-once"
            PermissionPreset.Ask -> {}
        }
        if (sessionGrants[sessionId]?.contains(tool) == true && risk != Risk.dangerous) return "allowed-once"
        val key = UUID.randomUUID().toString()
        val callId = p.s("callId")
        val args = callArguments(sessionId, p.s("sessionId"), callId)
        val deferred = CompletableDeferred<ApprovalOutcome>()
        approvals[key] = deferred
        mutate(sessionId) { it.copy(approval = PendingApproval(key, sessionId, tool, callId, p.s("reason"), args, risk, risk != Risk.dangerous)) }
        if (!AppVisibility.isForeground) Notifications.post(context, Notifications.CHANNEL_ATTENTION, sessionId.hashCode(),
            context.getString(R.string.notif_approval_title), context.getString(R.string.notif_approval_text, tool), sessionId)
        val outcome = try { deferred.await() } finally {
            approvals.remove(key)
            mutate(sessionId) { if (it.approval?.requestKey == key) it.copy(approval = null) else it }
        }
        return when (outcome) {
            ApprovalOutcome.AllowOnce -> "allowed-once"
            ApprovalOutcome.AllowSession -> { sessionGrants.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }.add(tool); "allowed-once" }
            ApprovalOutcome.Reject -> "rejected"
        }
    }

    private suspend fun callArguments(root: String, own: String?, callId: String?): String? {
        callId ?: return null
        for (id in listOfNotNull(own, root).distinct()) {
            flowOf(id).value.events.lastOrNull { it.type == "tool/call" && it.data.s("callId") == callId }?.let { return it.data.s("arguments") }
        }
        return null
    }

    fun answerApproval(requestKey: String, outcome: ApprovalOutcome) { approvals[requestKey]?.complete(outcome) }

    private suspend fun ask(p: JsonObject): JsonElement {
        val sessionId = p.s("sessionId") ?: throw JsonRpcException(-32602, "no session")
        if (ruleIdOfSession(sessionId) != null) throw JsonRpcException(-32000, "PROVIDER_ERROR: unattended session cannot ask the user")
        val owner = rootOf(sessionId)
        val key = p.s("requestId") ?: UUID.randomUUID().toString()
        val qs = p.a("questions")?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            PendingQuestion.Question(
                id = o.s("id") ?: return@mapNotNull null,
                header = o.s("header"),
                text = o.s("question") ?: o.s("text") ?: "",
                detail = o.s("detail"),
                options = o.a("options")?.mapNotNull { opt ->
                    when (opt) {
                        is JsonObject -> opt.s("label")?.let { PendingQuestion.Option(it, opt.s("description")) }
                        is JsonPrimitive -> PendingQuestion.Option(opt.content, null)
                        else -> null
                    }
                } ?: emptyList(),
                multi = o.b("multiSelect") == true,
            )
        } ?: emptyList()
        val deferred = CompletableDeferred<JsonElement>()
        questions[key] = deferred
        mutate(owner) { it.copy(question = PendingQuestion(key, owner, qs)) }
        if (!AppVisibility.isForeground) Notifications.post(context, Notifications.CHANNEL_ATTENTION, owner.hashCode(),
            context.getString(R.string.notif_question_title), qs.firstOrNull()?.text ?: "", owner)
        return try { deferred.await() } finally {
            questions.remove(key)
            mutate(owner) { if (it.question?.requestKey == key) it.copy(question = null) else it }
        }
    }

    private suspend fun rootOf(sessionId: String): String {
        var id = sessionId
        repeat(8) { id = dao.get(id)?.parentId ?: return id }
        return id
    }

    /** Called when the runtime cancels a pending question (`user-question.cancelled`). */
    fun cancelQuestion(requestKey: String) { questions[requestKey]?.completeExceptionally(JsonRpcException(-32000, "cancelled")) }

    /** answers: questionId -> selected labels plus an optional custom text. */
    fun answerQuestion(requestKey: String, answers: Map<String, Pair<List<String>, String?>>) {
        questions[requestKey]?.complete(buildJsonObject {
            putJsonArray("answers") {
                answers.forEach { (id, a) -> addJsonObject {
                    put("id", id)
                    put("selected", buildJsonArray { a.first.forEach { add(it) } })
                    a.second?.takeIf { it.isNotBlank() }?.let { put("custom", it) }
                } }
            }
        })
    }

    /**
     * A device tool needs an Android permission that is not granted. Shows the permission card in
     * the composer slot and suspends until the user grants (true, the call is retried) or declines.
     */
    suspend fun requestPermission(sessionId: String, toolName: String, what: String): Boolean {
        val owner = rootOf(sessionId)
        val key = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        permissions[key] = deferred
        mutate(owner) { it.copy(permission = PendingPermission(key, owner, toolName, permissionIdFor(what), what)) }
        if (!AppVisibility.isForeground) Notifications.post(context, Notifications.CHANNEL_ATTENTION, owner.hashCode(),
            context.getString(R.string.perm_card_strip), what, owner)
        return try { deferred.await() } finally {
            permissions.remove(key)
            mutate(owner) { if (it.permission?.requestKey == key) it.copy(permission = null) else it }
        }
    }

    fun answerPermission(requestKey: String, granted: Boolean) { permissions[requestKey]?.complete(granted) }

    /**
     * A screen step cannot be automated (FLAG_SECURE / black screenshot, or input the agent cannot
     * deliver): pause the call with a 请你来操作 card until the user has done it and taps 继续 (true)
     * or 停止 (false). AutoGLM-style manual takeover.
     */
    suspend fun requestTakeover(sessionId: String, toolName: String, reason: String): Boolean {
        val owner = rootOf(sessionId)
        val key = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Boolean>()
        takeovers[key] = deferred
        mutate(owner) { it.copy(takeover = PendingTakeover(key, owner, toolName, reason)) }
        Notifications.post(context, Notifications.CHANNEL_ATTENTION, owner.hashCode() + 2,
            context.getString(R.string.takeover_title), context.getString(R.string.takeover_notif), owner)
        return try { deferred.await() } finally {
            takeovers.remove(key)
            mutate(owner) { if (it.takeover?.requestKey == key) it.copy(takeover = null) else it }
        }
    }

    fun answerTakeover(requestKey: String, done: Boolean) { takeovers[requestKey]?.complete(done) }

    /** Engine refused a step because a token/cost budget is exhausted. */
    suspend fun onBudgetExceeded(p: JsonObject) {
        val sessionId = p.s("sessionId") ?: return
        appendLocal(rootOf(sessionId), "app/notice", buildJsonObject {
            put("text", context.getString(R.string.budget_exceeded, p.s("kind") ?: "", p.s("used") ?: "", p.s("limit") ?: ""))
        })
    }

    suspend fun setWaitingForUnlock(sessionId: String, waiting: Boolean) = mutate(rootOf(sessionId)) { it.copy(waitingForUnlock = waiting) }

    private suspend fun onTurnFinished(sessionId: String) {
        autoContinued.remove(sessionId)
        val live = flowOf(sessionId).value
        if (live.queue.isNotEmpty()) {
            val next = live.queue.first()
            mutate(sessionId) { it.copy(queue = it.queue.drop(1)) }
            scope.launch { prompt(sessionId, next.text) }
            return
        }
        if (states.values.none { it.value.running }) engine.scheduleIdleShutdown()
        if (dao.get(sessionId)?.parentId != null) return
        if (viewing != sessionId || !AppVisibility.isForeground) dao.setUnread(sessionId, true)
        if (!AppVisibility.isForeground) {
            val conv = Projector.conversation(live.events, running = false)
            val reply = conv.turns.lastOrNull()?.reply?.text ?: return
            Notifications.post(context, Notifications.CHANNEL_TASKS, sessionId.hashCode(),
                live.title.ifBlank { context.getString(R.string.app_name) }, reply.take(300), sessionId)
        }
    }

    /**
     * The engine channel closed unexpectedly (AC8). dsh's `session/resume` only repairs the
     * interrupted final turn (closes dangling tool calls) and cannot continue it, so recovery is:
     * after the [RestartPolicy][io.github.jackwener.dsh.core.engine.RestartPolicy] backoff
     * ([retryInMs]) each interrupted root session is resumed (prompt() does that once per engine
     * generation) and sent one continuation prompt that tells the model what happened. A session
     * gets one automatic continuation per turn; a second loss in the same turn, or an open circuit
     * breaker ([retryInMs] == null), leaves the visible engine_lost error with its 继续 button.
     */
    suspend fun onEngineLost(retryInMs: Long?) {
        if (stopping) return
        cancelPending()
        val running = states.values.map { it.value }.filter { it.running }.map { it.sessionId }.toSet() + dao.running().map { it.id }
        val roots = mutableListOf<String>()
        running.forEach { id ->
            dao.setRunning(id, false)
            mutate(id) { it.copy(running = false, startingEngine = false, runStartedAt = null, waitingForUnlock = false) }
            if (dao.get(id)?.parentId == null) roots += id
        }
        roots.forEach { id ->
            if (retryInMs != null && autoContinued.add(id)) {
                appendLocal(id, "app/notice", buildJsonObject { put("text", context.getString(R.string.engine_recovering)) })
                mutate(id) { it.copy(running = true, startingEngine = true, runStartedAt = System.currentTimeMillis()) }
            } else {
                appendLocal(id, "app/error", buildJsonObject {
                    put("kind", "engine_lost")
                    put("message", context.getString(if (retryInMs == null) R.string.err_engine_crash_loop else R.string.err_engine_lost))
                })
            }
        }
        if (retryInMs == null) return
        val resume = roots.filter { it in autoContinued }
        if (resume.isEmpty()) return
        scope.launch {
            delay(retryInMs)
            // Skip sessions the user stopped during the backoff.
            resume.filter { flowOf(it).value.running }.forEach { id -> scope.launch { prompt(id, CONTINUE_AFTER_CRASH, ruleIdOfSession(id)) } }
        }
    }

    private suspend fun recordUsage(data: JsonObject) {
        val u = Usage.of(data.o("usage")) ?: return
        val day = LocalDate.now().toString()
        val cur = usage.get(day) ?: UsageEntity(day, 0, 0, 0)
        usage.upsert(cur.copy(inputTokens = cur.inputTokens + u.input, outputTokens = cur.outputTokens + u.output, cachedTokens = cur.cachedTokens + u.cacheRead))
    }

    private companion object {
        /** Model-facing continuation after an engine crash (the interrupted tool calls were closed by session/resume). */
        const val CONTINUE_AFTER_CRASH = "[DSH] The agent engine stopped unexpectedly and was restarted, so your previous turn was " +
            "interrupted. Check which steps already completed (do not repeat them), then continue the task."

        /** Log-only records the phone UI never shows; they carry the whole prompt and tool schema. */
        val UNLOGGED = setOf("request/header", "request/context", "system/message", "developer/message", "session/end-seed")

        /** Maps a PermissionMissingException description to a PermissionCatalog id. */
        fun permissionIdFor(what: String): String? {
            val w = what.lowercase()
            return when {
                "accessibility" in w -> "accessibility"
                "notification access" in w -> "notif_access"
                "all files" in w -> "files"
                "do not disturb" in w -> "dnd"
                "modify system" in w -> "write_settings"
                "shizuku" in w -> null
                listOf("contacts", "sms", "call log", "calendar").any { it in w } -> "comms"
                "location" in w -> "location"
                "bluetooth" in w -> "bluetooth"
                else -> null
            }
        }
    }
}
