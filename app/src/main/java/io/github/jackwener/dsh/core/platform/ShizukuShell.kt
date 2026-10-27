package io.github.jackwener.dsh.core.platform

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.BuildConfig
import io.github.jackwener.dsh.core.capability.PermissionMissingException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

data class ShellResult(val code: Int, val output: String)

/** Shell-user privileges through Shizuku (optional enhancement). */
@Singleton
class ShizukuShell @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    @Volatile private var service: IShellService? = null

    val installed: Boolean get() = runCatching { context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true }.getOrDefault(false)
    val running: Boolean get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    val granted: Boolean get() = running && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    val available: Boolean get() = granted

    fun requestPermission() { if (running) Shizuku.requestPermission(REQUEST_CODE) }

    private val args = Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellUserService::class.java.name))
        .daemon(false).processNameSuffix("shell").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)

    suspend fun service(): IShellService = mutex.withLock {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        if (!granted) throw PermissionMissingException("Shizuku (install, start and authorize Shizuku)")
        val deferred = CompletableDeferred<IShellService>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder != null && binder.pingBinder()) deferred.complete(IShellService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        withContext(Dispatchers.Main) { Shizuku.bindUserService(args, conn) }
        withTimeout(10_000) { deferred.await() }.also { service = it }
    }

    suspend fun exec(cmd: String, timeoutMs: Long = 30_000): ShellResult = withContext(Dispatchers.IO) {
        val raw = service().exec(cmd, timeoutMs)
        ShellResult(raw.substringBefore('\n').toIntOrNull() ?: -1, raw.substringAfter('\n'))
    }

    companion object { const val REQUEST_CODE = 7 }
}
