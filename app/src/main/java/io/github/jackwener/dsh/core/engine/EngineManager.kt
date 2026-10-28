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
import io.github.jackwener.dsh.core.account.AccountRepository
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

/** Neither a DeepSeek API key nor a DeepSeek account login: the engine is up, but no model route can authenticate. */
class NoCredentialsException : Exception("No DeepSeek API key and not signed in to a DeepSeek account")
class EngineStartException(message: String, val log: String?, cause: Throwable) : Exception(message, cause)
/** Android refused to start the engine's foreground service from the background (Android 12+). */
class EngineBackgroundStartException(cause: Throwable) : Exception("foreground service start not allowed from the background", cause)

/** The process-wide SDK route (`initialize`): provider + model + effort. */
private data class Route(val provider: String, val model: String, val effort: String)

/**
 * Owns the `:engine` process from the app side: starts it on demand, wires the SDK and capability
 * channels, and shuts it down after idling. See docs/ENGINE-CONTRACT.md.
 *
 * Credentials: an API key (passed as `DEEPSEEK_API_KEY` at process start) or a DeepSeek account
 * login (held by the engine itself). Precedence mirrors dsh's `initializeDefaultModel`: the account
 * route `deepseek-account` is used only when no API key is configured; otherwise
 * `deepseek-official`. The engine starts without either, because account login runs inside it.
 */
@Singleton
class EngineManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val capabilities: Lazy<CapabilityHub>,
    private val sessions: Lazy<SessionStore>,
    private val account: Lazy<AccountRepository>,
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
    private var activeRoute: Route? = null
    /** Whether an SDK session was opened in this generation (then a route change needs a restart). */
    @Volatile private var sessionsOpened = false
    private var death: CompletableDeferred<Unit>? = null
    /** Generation we asked to exit; its channel closing is not a crash. */
    @Volatile private var expectedExit = -1
    /** Backoff + circuit breaker for unexpected engine exits (see RestartPolicy). */
    val restarts = RestartPolicy()

    private fun wantedRoute(st: io.github.jackwener.dsh.core.data.Settings, hasKey: Boolean) =
        Route(if (!hasKey && account.get().signedIn) "deepseek-account" else "deepseek-official", st.model, st.effort.id)

    /**
     * Returns a ready SDK peer for prompts, starting the engine if needed. The route is
     * process-wide (`initialize`): a changed model / effort / credential route re-initializes an
     * engine that has not opened sessions yet, or restarts an idle one, before the next prompt.
     * Throws [NoCredentialsException] (with the engine left running, so the user can sign in)
     * when there is neither an API key nor an account login.
     */
    suspend fun sdk(): JsonRpcPeer = mutex.withLock {
        val st = settings.current()
        val hasKey = secrets.read() != null
        val peer = sdk?.takeIf { _state.value == EngineState.Ready }?.let { peer ->
            val want = wantedRoute(st, hasKey)
            when {
                want == activeRoute || sessions.get().hasEngineRuns() -> peer
                !sessionsOpened -> runCatching { initialize(peer, want); peer }.getOrElse {
                    Log.w(TAG, "re-initialize failed; restarting", it); shutdownLocked(); null
                }
                else -> { Log.i(TAG, "route changed: restarting engine"); shutdownLocked(); null }
            }
        } ?: startLocked(st, hasKey)
        if (!hasKey && !account.get().signedIn) throw NoCredentialsException()
        peer
    }

    /** A ready peer for control traffic (account login, profile); never restarts a running engine. */
    suspend fun control(): JsonRpcPeer = mutex.withLock {
        sdk?.takeIf { _state.value == EngineState.Ready } ?: startLocked(settings.current(), secrets.read() != null)
    }

    /** SessionStore is about to open (create/resume) an SDK session in the current generation. */
    fun markSessionOpened() { sessionsOpened = true }

    private suspend fun initialize(peer: JsonRpcPeer, route: Route) {
        withTimeout(90_000) {
            peer.request("initialize", buildJsonObject {
                put("cwd", File(context.filesDir, "home").path)
                put("provider", route.provider)
                put("model", route.model)
                put("reasoningEffort", route.effort)
                put("approvalRequests", true)
                putJsonObject("capabilities") { put("userQuestions", true) }
            })
        }
        activeRoute = route
        Log.i(TAG, "SDK route ${route.provider}/${route.model}/${route.effort}")
    }

    private suspend fun startLocked(st: io.github.jackwener.dsh.core.data.Settings, hasKey: Boolean): JsonRpcPeer {
        _state.value = EngineState.Starting
        val t0 = System.currentTimeMillis()
        val gen = generation + 1
        try {
            val service = bind(gen)
            val handles = withContext(Dispatchers.IO) { service.start(environment(secrets.read())) }
            val sdkIn = ParcelFileDescriptor.AutoCloseOutputStream(handles.pfd("sdk_in"))
            val sdkOut = ParcelFileDescriptor.AutoCloseInputStream(handles.pfd("sdk_out"))
            val bridgeFd = handles.pfd("bridge")
            val peer = JsonRpcPeer("sdk", sdkOut, sdkIn, scope,
                onRequest = { m, p -> sessions.get().onServerRequest(m, p) },
                onNotification = { m, p -> if (m.startsWith("account.")) account.get().onNotification(m, p) else sessions.get().onNotification(m, p) },
                onClosed = { onEngineGone(gen) })
            val bridgePeer = JsonRpcPeer("bridge",
                ParcelFileDescriptor.AutoCloseInputStream(bridgeFd),
                ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(bridgeFd.fileDescriptor)),
                scope,
                onRequest = { m, p -> capabilities.get().onBridgeRequest(m, p) },
                onNotification = { m, p -> if (m == "budget/exceeded") sessions.get().onBudgetExceeded(p) },
                onClosed = {})
            peer.start(); bridgePeer.start()
            sessionsOpened = false
            // The account decides the route when there is no key; read it before `initialize`.
            runCatching { withTimeout(90_000) { peer.request("account/state") } }
                .onSuccess { account.get().onEngineView(it) }
                .onFailure { Log.w(TAG, "account/state unavailable", it) }
            initialize(peer, wantedRoute(st, hasKey))
            sdk = peer; bridge = bridgePeer
            generation = gen
            capabilities.get().attach(bridgePeer)
            bridgePeer.notify("budget/update", buildJsonObject {
                if (st.sessionTokenLimit > 0) put("sessionTokenLimit", st.sessionTokenLimit)
                if (st.dailyTokenLimit > 0) put("dailyTokenLimit", st.dailyTokenLimit)
            })
            startupMillis = System.currentTimeMillis() - t0
            _state.value = EngineState.Ready
            Log.i(TAG, "engine ready in ${startupMillis}ms")
            scope.launch { delay(RestartPolicy.STABLE_MS); if (generation == gen && sdk === peer) restarts.recordHealthy() }
            return peer
        } catch (e: Exception) {
            Log.e(TAG, "engine start failed", e)
            if (e is EngineBackgroundStartException) { teardown(); throw e }
            restarts.recordFailure()
            val detail = engineLogTail()
            teardown()
            killEngineProcess()
            _state.value = EngineState.Failed(listOfNotNull(e.message ?: e.javaClass.simpleName, detail).joinToString("\n"))
            throw EngineStartException(e.message ?: e.javaClass.simpleName, detail, e)
        }
    }

    private fun engineLogTail(): String? = runCatching {
        EngineDiagnostics.logTail(context, 600)
    }.getOrNull()

    /** Called when there is no running work; stops the engine after [IDLE_MS]. */
    fun scheduleIdleShutdown() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            if (sessions.get().hasRunning() || account.get().busy) return@launch
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
        val retryInMs = restarts.recordFailure()
        scope.launch {
            sessions.get().onEngineLost(retryInMs)
            delay(2_000) // let the system record the exit reason
            EngineDiagnostics.collectExitInfo(context)
        }
    }

    private fun teardown() {
        sdk = null; bridge = null
        activeRoute = null
        account.get().onEngineStopped()
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
        try {
            context.startForegroundService(intent)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException; older
            // releases throw a plain IllegalStateException for background starts.
            death = null
            throw EngineBackgroundStartException(e)
        }
        check(context.bindService(intent, conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) { "bind failed" }
        connection = conn
        return withTimeout(20_000) { deferred.await() }
    }

    private fun environment(apiKey: String?) = Bundle().apply {
        val files = context.filesDir
        putString("DSH_HOME", File(files, "dsh-home").path)
        putString("HOME", File(files, "home").path)
        putString("TMPDIR", File(context.cacheDir, "tmp").path)
        putString("SHELL", "/system/bin/sh")
        putString("PATH", "${File(files, "bin").path}:/system/bin:/system/xbin")
        putString("DSH_NATIVE_DIR", context.applicationInfo.nativeLibraryDir)
        apiKey?.let { putString("DEEPSEEK_API_KEY", it) }
        putString("DSH_ANDROID_PACKAGE", context.packageName)
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
