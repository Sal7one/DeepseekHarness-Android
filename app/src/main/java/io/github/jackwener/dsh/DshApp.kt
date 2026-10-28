package io.github.jackwener.dsh

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.platform.Notifications
import io.github.jackwener.dsh.core.trigger.TriggerEvents
import io.github.jackwener.dsh.core.trigger.TriggerHub
import io.github.jackwener.dsh.service.FloatingBallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class DshApp : Application() {
    @Inject lateinit var triggers: dagger.Lazy<TriggerHub>
    @Inject lateinit var settings: dagger.Lazy<SettingsRepository>
    @Inject lateinit var secrets: dagger.Lazy<io.github.jackwener.dsh.core.data.SecretStore>

    override fun onCreate() {
        super.onCreate()
        if (!isMainProcess()) return
        Notifications.createChannels(this)
        importDebugKey()
        TriggerEvents.init(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            // Import :engine crash records (ApplicationExitInfo / tombstones) from earlier runs.
            io.github.jackwener.dsh.core.engine.EngineDiagnostics.collectExitInfo(this@DshApp)
            triggers.get().sync()
            if (settings.get().current().floatingBall) runCatching { FloatingBallService.setEnabled(this@DshApp, true) }
        }
    }

    /** Debug builds only: device tests provision the key with `run-as <pkg> sh -c 'cat > files/debug.key'`. */
    private fun importDebugKey() {
        if (!BuildConfig.DEBUG) return
        val f = java.io.File(filesDir, "debug.key")
        if (!f.exists()) return
        f.readText().trim().takeIf { it.isNotEmpty() }?.let { secrets.get().write(it) }
        f.delete()
    }

    private fun isMainProcess() = getProcessName() == packageName
}
