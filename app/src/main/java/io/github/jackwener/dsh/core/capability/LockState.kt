package io.github.jackwener.dsh.core.capability

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class LockState @Inject constructor(@ApplicationContext private val context: Context) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    val isLocked: Boolean get() = keyguard.isDeviceLocked

    /** Suspends until the user unlocks, or returns false after [timeoutMs]. */
    suspend fun awaitUnlock(timeoutMs: Long): Boolean {
        if (!isLocked) return true
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        if (!isLocked && cont.isActive) { runCatching { context.unregisterReceiver(this) }; cont.resume(true) }
                    }
                }
                context.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            }
        } ?: false
    }
}
