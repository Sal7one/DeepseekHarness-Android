package io.github.jackwener.dsh.core.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Time alarms and proximity alerts (internal, not exported). */
@AndroidEntryPoint
class TriggerReceiver : BroadcastReceiver() {
    @Inject lateinit var hub: TriggerHub

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TIME -> hub.onEvent(DeviceEvent.Time(intent.getLongExtra("ruleId", -1)))
            ACTION_GEOFENCE -> hub.onEvent(DeviceEvent.Geofence(intent.getLongExtra("ruleId", -1),
                intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true)))
            ACTION_GEOFENCE_GMS -> {
                val ev = GeofencingEvent.fromIntent(intent) ?: return
                if (ev.hasError()) return
                val entering = ev.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
                ev.triggeringGeofences?.forEach { g -> g.requestId.toLongOrNull()?.let { hub.onEvent(DeviceEvent.Geofence(it, entering)) } }
            }
        }
    }

    companion object {
        const val ACTION_TIME = "io.github.jackwener.dsh.TRIGGER_TIME"
        const val ACTION_GEOFENCE = "io.github.jackwener.dsh.TRIGGER_GEOFENCE"
        const val ACTION_GEOFENCE_GMS = "io.github.jackwener.dsh.TRIGGER_GEOFENCE_GMS"
    }
}

/** Re-registers all automations after boot or app update. */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {
    @Inject lateinit var hub: TriggerHub

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch { try { hub.sync() } finally { pending.finish() } }
    }
}
