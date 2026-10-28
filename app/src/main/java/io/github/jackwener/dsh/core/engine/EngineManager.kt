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

    /** Returns a ready SDK peer, starting the engine if needed. */
    suspend fun sdk(): JsonRpcPeer = mutex.withLock {
        sdk?.let { if (_state.value == EngineState.Ready) return it }
        val key = secrets.read() ?: throw NoApiKeyException()
        _state.value = EngineState.Starting
        val t0 = System.currentTimeMillis()
        try {
            val service = bind()
            val handles = withContext(Dispatchers.IO) { service.start(environment(key)) }
            val sdkIn = ParcelFileDescriptor.AutoCloseOutputStream(handles.pfd("sdk_in"))
            val sdkOut = ParcelFileDescriptor.AutoCloseInputStream(handles.pfd("sdk_out"))
            val bridgeFd = handles.pfd("bridge")
            val peer = JsonRpcPeer("sdk", sdkOut, sdkIn, scope,
                onRequest = { m, p -> sessions.get().onServerRequest(m, p) },
                onNotification = { m, p -> sessions.get().onNotification(m, p) },
                onClosed = ::onEngineGone)
            val bridgePeer = JsonRpcPeer("bridge",
                ParcelFileDescriptor.AutoCloseInputStream(bridgeFd),
                ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(bridgeFd.fileDescriptor)),
                scope,
                onRequest = { m, p -> capabilities.get().onBridgeRequest(m, p) },
                onNotification = { m, p -> if (m == "budget/exceeded") sessions.get().onBudgetExceeded(p) },
                onClosed = {})
            peer.start(); bridgePeer.start()
            val model = settings.current().model
            withTimeout(60_000) {
                peer.request("initialize", buildJsonObject {
                    put("cwd", File(context.filesDir, "home").path)
                    put("provider", "deepseek-official")
                    put("model", model)
                    put("approvalRequests", true)
                    putJsonObject("capabilities") { put("userQuestions", true) }
                })
            }
            sdk = peer; bridge = bridgePeer
            capabilities.get().attach(bridgePeer)
            settings.current().let { st ->
                bridgePeer.notify("budget/update", buildJsonObject {
                    if (st.sessionTokenLimit > 0) put("sessionTokenLimit", st.sessionTokenLimit)
                    if (st.dailyTokenLimit > 0) put("dailyTokenLimit", st.dailyTokenLimit)
                })
            }
            startupMillis = System.currentTimeMillis() - t0
            _state.value = EngineState.Ready
            Log.i(TAG, "engine ready in ${startupMillis}ms")
            peer
        } catch (e: Exception) {
            Log.e(TAG, "engine start failed", e)
            _state.value = EngineState.Failed(e.message ?: e.javaClass.simpleName)
            teardown()
            throw e
        }
    }

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
    suspend fun stop() = mutex.withLock {
        runCatching { withTimeout(3_000) { sdk?.request("shutdown") } }
        teardown()
    }

    fun notifyCapabilitiesChanged() {
        scope.launch { runCatching { bridge?.notify("capability/changed") } }
    }

    private fun onEngineGone() {
        if (sdk == null) return
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

    private suspend fun bind(): IEngineService {
        val deferred = CompletableDeferred<IEngineService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                binder.linkToDeath({ onEngineGone() }, 0)
                deferred.complete(IEngineService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) { onEngineGone() }
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
