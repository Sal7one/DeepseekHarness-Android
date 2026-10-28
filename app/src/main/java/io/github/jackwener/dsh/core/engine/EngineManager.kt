package io.github.jackwener.dsh.core.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.capability.CapabilityHub
import io.github.jackwener.dsh.core.data.SecretStore
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.session.SessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed interface EngineState {
    data object Stopped : EngineState
    data object Starting : EngineState
    data object Ready : EngineState
    data class Failed(val message: String) : EngineState
}

class NoApiKeyException : Exception("DeepSeek API key not set")
class EngineStartException(message: String, val log: String?, cause: Throwable) : Exception(message, cause)

/**
 * Owns the `:engine` process from the app side: starts it on demand, wires the SDK and capability
 * channels, and shuts it down after idling. See docs/ENGINE-CONTRACT.md.
 */
@Singleton
class EngineManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val capabilities: Lazy<CapabilityHub>,
    private val sessions: Lazy<SessionStore>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val _state = MutableStateFlow<EngineState>(EngineState.Stopped)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var sdk: JsonRpcPeer? = null
    private var bridge: JsonRpcPeer? = null
    private var connection: ServiceConnection? = null
    private var idleJob: Job? = null
    var startupMillis: Long = 0; private set

    /** Incremented on every engine start; sessions must be resumed once per generation. */
    @Volatile var generation: Int = 0; private set
    private var activeConfig: Pair<String, String>? = null
    private var death: CompletableDeferred<Unit>? = null
    /** Generation we asked to exit; its channel closing is not a crash. */
    @Volatile private var expectedExit = -1

    /**
     * Returns a ready SDK peer, starting the engine if needed. Model / effort are process-wide
     * (`initialize`), so a changed selection restarts an idle engine before the next prompt.
     */
    suspend fun sdk(): JsonRpcPeer = mutex.withLock {
        val st = settings.current()
        val config = st.model to st.effort.id
        sdk?.let { peer ->
            if (_state.value == EngineState.Ready && (config == activeConfig || sessions.get().hasRunning())) return peer
            Log.i(TAG, "model/effort changed: restarting engine")
            shutdownLocked()
        }
        val key = secrets.read() ?: throw NoApiKeyException()
        _state.value = EngineState.Starting
        val t0 = System.currentTimeMillis()
        val gen = generation + 1
        try {
            val service = bind(gen)
            val handles = withContext(Dispatchers.IO) { service.start(environment(key)) }
            val sdkIn = ParcelFileDescriptor.AutoCloseOutputStream(handles.pfd("sdk_in"))
            val sdkOut = ParcelFileDescriptor.AutoCloseInputStream(handles.pfd("sdk_out"))
            val bridgeFd = handles.pfd("bridge")
            val peer = JsonRpcPeer("sdk", sdkOut, sdkIn, scope,
                onRequest = { m, p -> sessions.get().onServerRequest(m, p) },
                onNotification = { m, p -> sessions.get().onNotification(m, p) },
                onClosed = { onEngineGone(gen) })
            val bridgePeer = JsonRpcPeer("bridge",
                ParcelFileDescriptor.AutoCloseInputStream(bridgeFd),
                ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(bridgeFd.fileDescriptor)),
                scope,
                onRequest = { m, p -> capabilities.get().onBridgeRequest(m, p) },
                onNotification = { m, p -> if (m == "budget/exceeded") sessions.get().onBudgetExceeded(p) },
                onClosed = {})
            peer.start(); bridgePeer.start()
            withTimeout(90_000) {
                peer.request("initialize", buildJsonObject {
                    put("cwd", File(context.filesDir, "home").path)
                    put("provider", "deepseek-official")
                    put("model", st.model)
                    put("reasoningEffort", st.effort.id)
                    put("approvalRequests", true)
                    putJsonObject("capabilities") { put("userQuestions", true) }
                })
            }
            sdk = peer; bridge = bridgePeer
            generation = gen
            activeConfig = config
            capabilities.get().attach(bridgePeer)
            bridgePeer.notify("budget/update", buildJsonObject {
                if (st.sessionTokenLimit > 0) put("sessionTokenLimit", st.sessionTokenLimit)
                if (st.dailyTokenLimit > 0) put("dailyTokenLimit", st.dailyTokenLimit)
            })
            startupMillis = System.currentTimeMillis() - t0
            _state.value = EngineState.Ready
            Log.i(TAG, "engine ready in ${startupMillis}ms")
            peer
        } catch (e: Exception) {
            Log.e(TAG, "engine start failed", e)
            val detail = engineLogTail()
            teardown()
            killEngineProcess()
            _state.value = EngineState.Failed(listOfNotNull(e.message ?: e.javaClass.simpleName, detail).joinToString("\n"))
            throw EngineStartException(e.message ?: e.javaClass.simpleName, detail, e)
        }
    }

    private fun engineLogTail(): String? = runCatching {
        File(context.cacheDir, "engine.log").takeIf { it.exists() }?.readText()?.trim()?.takeLast(600)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Called when there is no running work; stops the engine after [IDLE_MS]. */
    fun scheduleIdleShutdown() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            if (sessions.get().hasRunning()) return@launch
            stop()
        }
    }

    fun cancelIdleShutdown() { idleJob?.cancel() }

    /** Hard stop: the SDK has no cancel method, so stopping a turn means stopping the process. */
    suspend fun stop() = mutex.withLock { shutdownLocked() }

    /** Asks Node to exit and waits for the `:engine` process to die so the next start gets a fresh one. */
    private suspend fun shutdownLocked() {
        val dying = death
        expectedExit = generation
        runCatching { withTimeout(3_000) { sdk?.request("shutdown") } }
        teardown()
        if (dying != null && withTimeoutOrNull(5_000) { dying.await() } == null) killEngineProcess()
    }

    /** Last resort for a wedged `:engine` process (Node can start only once per process). */
    private fun killEngineProcess() {
        val am = context.getSystemService(android.app.ActivityManager::class.java)
        am.runningAppProcesses?.filter { it.processName == context.packageName + ":engine" }?.forEach {
            Log.w(TAG, "killing engine process ${it.pid}")
            android.os.Process.killProcess(it.pid)
        }
    }

    fun notifyCapabilitiesChanged() {
        scope.launch { runCatching { bridge?.notify("capability/changed") } }
    }

    private fun onEngineGone(gen: Int) {
        if (sdk == null || gen != generation || gen == expectedExit) return
        Log.w(TAG, "engine channel closed")
        teardown()
        scope.launch { sessions.get().onEngineLost() }
    }

    private fun teardown() {
        sdk = null; bridge = null
        capabilities.get().detach()
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        context.stopService(Intent(context, EngineService::class.java))
        _state.value = EngineState.Stopped
    }

    private suspend fun bind(gen: Int): IEngineService {
        val deferred = CompletableDeferred<IEngineService>()
        val died = CompletableDeferred<Unit>().also { death = it }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                binder.linkToDeath({ died.complete(Unit); onEngineGone(gen) }, 0)
                deferred.complete(IEngineService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) { died.complete(Unit); onEngineGone(gen) }
        }
        val intent = Intent(context, EngineService::class.java)
        context.startForegroundService(intent)
        check(context.bindService(intent, conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) { "bind failed" }
        connection = conn
        return withTimeout(20_000) { deferred.await() }
    }

    private fun environment(apiKey: String) = Bundle().apply {
        val files = context.filesDir
        putString("DSH_HOME", File(files, "dsh-home").path)
        putString("HOME", File(files, "home").path)
        putString("TMPDIR", File(context.cacheDir, "tmp").path)
        putString("SHELL", "/system/bin/sh")
        putString("PATH", "${File(files, "bin").path}:/system/bin:/system/xbin")
        putString("DSH_NATIVE_DIR", context.applicationInfo.nativeLibraryDir)
        putString("DEEPSEEK_API_KEY", apiKey)
        putString("DSH_ANDROID_LOCALE", Locale.getDefault().toLanguageTag())
        putString("NODE_OPTIONS", "--max-old-space-size=768")
        putString("DSH_TELEMETRY_DISABLED", "1")
        putString("NARB_DISABLE_NATIVE_CACHE", "1")
    }

    private fun Bundle.pfd(key: String): ParcelFileDescriptor =
        @Suppress("DEPRECATION") (getParcelable<ParcelFileDescriptor>(key) ?: error("missing $key"))

    private companion object {
        const val TAG = "EngineManager"
        const val IDLE_MS = 5 * 60_000L
    }
}
