package io.github.jackwener.dsh.core.capability

import android.content.Context
import android.util.Log
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.capability.tools.AppTools
import io.github.jackwener.dsh.core.capability.tools.BrowserTools
import io.github.jackwener.dsh.core.capability.tools.ClipboardTools
import io.github.jackwener.dsh.core.capability.tools.CommsTools
import io.github.jackwener.dsh.core.capability.tools.FileTools
import io.github.jackwener.dsh.core.capability.tools.MediaTools
import io.github.jackwener.dsh.core.capability.tools.NotificationTools
import io.github.jackwener.dsh.core.capability.tools.RuleTools
import io.github.jackwener.dsh.core.capability.tools.ScreenTools
import io.github.jackwener.dsh.core.capability.tools.SystemTools
import io.github.jackwener.dsh.core.capability.tools.VirtualScreenTools
import io.github.jackwener.dsh.core.data.RuleDao
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.engine.JsonRpcException
import io.github.jackwener.dsh.core.engine.JsonRpcPeer
import io.github.jackwener.dsh.core.engine.RpcJson
import io.github.jackwener.dsh.core.model.RuleSpec
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.model.ruleIdOfSession
import io.github.jackwener.dsh.core.platform.Notifications
import io.github.jackwener.dsh.core.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single gate for device capabilities (docs/ENGINE-CONTRACT.md §3): lists the enabled tools
 * and executes calls after group, unattended-whitelist and lock-state checks.
 */
@Singleton
class CapabilityHub @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val rules: RuleDao,
    private val lock: LockState,
    private val sessions: Lazy<SessionStore>,
    files: FileTools, apps: AppTools, screen: ScreenTools, system: SystemTools, notifications: NotificationTools,
    comms: CommsTools, media: MediaTools, clipboard: ClipboardTools, browser: BrowserTools, ruleTools: RuleTools,
    vscreen: VirtualScreenTools,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val allTools: Map<String, Tool> = listOf(files, apps, screen, system, notifications, comms, media, clipboard, browser, ruleTools, vscreen)
        .flatMap { it.tools }.associateBy { it.name }
    @Volatile private var bridge: JsonRpcPeer? = null

    init {
        scope.launch {
            settings.settings.map { it.enabledGroups }.distinctUntilChanged().drop(1).collect {
                runCatching { bridge?.notify("capability/changed") }
            }
        }
    }

    fun descriptor(name: String): ToolDescriptor? = allTools[name]?.descriptor

    fun attach(peer: JsonRpcPeer) { bridge = peer }
    fun detach() { bridge = null }

    suspend fun onBridgeRequest(method: String, params: JsonObject): JsonElement = when (method) {
        "capability/list" -> {
            val enabled = settings.current().enabledGroups
            buildJsonObject {
                putJsonArray("tools") {
                    allTools.values.filter { ToolGroup.of(it.descriptor.group) in enabled }
                        .forEach { add(RpcJson.encodeToJsonElement(ToolDescriptor.serializer(), it.descriptor)) }
                }
            }
        }
        "capability/invoke" -> encode(invoke(params))
        else -> throw JsonRpcException(-32601, "unknown method $method")
    }

    /** Also used directly by rule "steps" actions (no engine involved). */
    suspend fun invoke(params: JsonObject): ToolResult {
        val name = params["tool"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.error("invalid_args", "missing tool")
        val sessionId = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: ""
        val callId = params["callId"]?.jsonPrimitive?.contentOrNull ?: ""
        val args = (params["args"] as? JsonObject) ?: JsonObject(emptyMap())
        val tool = allTools[name] ?: return ToolResult.error("invalid_args", "unknown tool $name")
        val group = ToolGroup.of(tool.descriptor.group)
        if (group !in settings.current().enabledGroups) return ToolResult.error("not_authorized",
            "The '${tool.descriptor.group}' capability is turned off. Ask the user to enable it in DSH → Me → Capabilities.")
        val ruleId = ruleIdOfSession(sessionId)
        if (ruleId != null) {
            val spec = rules.get(ruleId)?.let { RpcJson.decodeFromString(RuleSpec.serializer(), it.specJson) }
            if (spec == null || name !in spec.allowedTools) return ToolResult.error("not_authorized",
                "tool_not_authorized: '$name' is not in this automation's approved tool list.")
        }
        if (tool.needsUnlock && lock.isLocked) {
            Notifications.post(context, Notifications.CHANNEL_ATTENTION, sessionId.hashCode() + 1,
                context.getString(R.string.notif_unlock_title), context.getString(R.string.notif_unlock_text), sessionId.ifEmpty { null })
            if (sessionId.isNotEmpty()) sessions.get().setWaitingForUnlock(sessionId, true)
            val unlocked = lock.awaitUnlock(UNLOCK_WAIT_MS)
            if (sessionId.isNotEmpty()) sessions.get().setWaitingForUnlock(sessionId, false)
            if (!unlocked) return ToolResult.error("device_locked_timeout", "The phone stayed locked; this step needs an unlocked screen.")
        }
        return try {
            tool.invoke(args, CallContext(sessionId, callId, unattended = ruleId != null))
        } catch (e: PermissionMissingException) {
            ToolResult.error("permission_missing", "Missing permission: ${e.what}. Ask the user to grant it in DSH → Me → Permissions.")
        } catch (e: InvalidArgsException) {
            ToolResult.error("invalid_args", e.message ?: "invalid arguments")
        } catch (e: Exception) {
            Log.w(TAG, "tool $name failed", e)
            ToolResult.error("failed", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun encode(r: ToolResult) = buildJsonObject {
        put("ok", r.ok)
        putJsonArray("content") {
            r.content.forEach { c -> addJsonObject {
                when (c) {
                    is Content.Text -> { put("type", "text"); put("text", c.text) }
                    is Content.Image -> { put("type", "image"); put("mimeType", c.mimeType); put("data", c.base64) }
                }
            } }
        }
        if (!r.ok) putJsonObject("error") { put("code", r.errorCode ?: "failed"); put("message", r.errorMessage ?: "") }
    }

    private companion object {
        const val TAG = "CapabilityHub"
        const val UNLOCK_WAIT_MS = 6 * 60 * 60_000L
    }
}

/** Every tool group class exposes its tools. */
interface ToolSet { val tools: List<Tool> }
