package io.github.jackwener.dsh.core.trigger

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.capability.CapabilityHub
import io.github.jackwener.dsh.core.data.RuleDao
import io.github.jackwener.dsh.core.data.RuleEntity
import io.github.jackwener.dsh.core.data.RuleRunEntity
import io.github.jackwener.dsh.core.engine.RpcJson
import io.github.jackwener.dsh.core.model.AUTO_SESSION_PREFIX
import io.github.jackwener.dsh.core.model.Condition
import io.github.jackwener.dsh.core.model.RuleAction
import io.github.jackwener.dsh.core.model.RuleSpec
import io.github.jackwener.dsh.core.model.Trigger
import io.github.jackwener.dsh.core.platform.Notifications
import io.github.jackwener.dsh.core.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automation engine: keeps OS registrations in sync with the enabled rules, matches incoming
 * [DeviceEvent]s, and runs the rule's action — fixed tool steps directly, or an unattended agent session.
 */
@Singleton
class TriggerHub @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rules: RuleDao,
    private val capabilities: CapabilityHub,
    private val sessions: dagger.Lazy<SessionStore>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lastFired = ConcurrentHashMap<Long, Long>()
    @Volatile private var cache: List<Pair<RuleEntity, RuleSpec>> = emptyList()

    fun decode(e: RuleEntity): RuleSpec? = runCatching { RpcJson.decodeFromString(RuleSpec.serializer(), e.specJson) }.getOrNull()

    suspend fun create(spec: RuleSpec): Long {
        val normalized = if (spec.action is RuleAction.Steps) spec.copy(allowedTools = (spec.allowedTools + spec.action.steps.map { it.tool }).distinct()) else spec
        val id = rules.insert(RuleEntity(specJson = RpcJson.encodeToString(RuleSpec.serializer(), normalized)))
        sync(); return id
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) { rules.get(id)?.let { rules.upsert(it.copy(enabled = enabled)) }; sync() }
    suspend fun delete(id: Long) { cancelAlarm(id); cancelGeofence(id); rules.delete(id); sync() }

    /** Re-registers OS hooks for all enabled rules. Call at boot and after any change. */
    suspend fun sync() {
        val all = rules.all()
        cache = all.filter { it.enabled }.mapNotNull { e -> decode(e)?.let { e to it } }
        all.forEach { cancelAlarm(it.id); cancelGeofence(it.id) }
        cache.forEach { (e, s) ->
            when (val t = s.trigger) {
                is Trigger.Time -> scheduleAlarm(e.id, t)
                is Trigger.Geofence -> registerGeofence(e.id, t)
                else -> {}
            }
        }
        val needsMonitor = cache.any { it.second.trigger is Trigger.Wifi || it.second.trigger is Trigger.Bluetooth ||
            it.second.trigger is Trigger.Power || it.second.trigger is Trigger.BatteryLow || it.second.trigger is Trigger.Headset || it.second.trigger is Trigger.Screen }
        RuleMonitorService.setRunning(context, needsMonitor)
    }

    fun onEvent(event: DeviceEvent) {
        scope.launch {
            if (cache.isEmpty()) cache = rules.all().filter { it.enabled }.mapNotNull { e -> decode(e)?.let { e to it } }
            cache.filter { (e, s) -> matches(e.id, s.trigger, event) && s.conditions.all(::holds) }.forEach { (e, s) -> fire(e, s, event) }
            if (event is DeviceEvent.Time) rules.get(event.ruleId)?.let { e -> decode(e)?.trigger?.let { t -> if (t is Trigger.Time && e.enabled) scheduleAlarm(e.id, t) } }
        }
    }

    private fun matches(id: Long, t: Trigger, ev: DeviceEvent): Boolean = when (t) {
        is Trigger.Time -> ev is DeviceEvent.Time && ev.ruleId == id
        is Trigger.Geofence -> ev is DeviceEvent.Geofence && ev.ruleId == id && ev.entering == t.enter
        is Trigger.Wifi -> ev is DeviceEvent.Wifi && ev.connected == t.connected && (t.ssid == null || t.ssid.equals(ev.ssid, true))
        is Trigger.Bluetooth -> ev is DeviceEvent.Bluetooth && ev.connected == t.connected &&
            (t.deviceName == null || ev.deviceName?.contains(t.deviceName, true) == true)
        is Trigger.Power -> ev is DeviceEvent.Power && ev.connected == t.connected
        is Trigger.BatteryLow -> ev is DeviceEvent.Battery && ev.percent == t.percent
        is Trigger.Headset -> ev is DeviceEvent.Headset && ev.plugged == t.plugged
        is Trigger.Screen -> ev is DeviceEvent.Screen && ev.event == t.event
        is Trigger.Notification -> ev is DeviceEvent.Notification && (t.packageName == null || t.packageName == ev.packageName) &&
            (t.keyword == null || "${ev.title} ${ev.text}".contains(t.keyword, true))
        is Trigger.AppOpened -> ev is DeviceEvent.AppOpened && ev.packageName == t.packageName
    }

    private fun holds(c: Condition): Boolean = when (c) {
        is Condition.TimeRange -> {
            val now = LocalTime.now(); val from = LocalTime.parse(c.from); val to = LocalTime.parse(c.to)
            if (from <= to) now in from..to else now >= from || now <= to
        }
        is Condition.Days -> LocalDate.now().dayOfWeek.value in c.days
        is Condition.Battery -> battery().let { (pct, _) -> (c.min == null || pct >= c.min) && (c.max == null || pct <= c.max) }
        is Condition.Charging -> battery().second == c.charging
    }

    private fun battery(): Pair<Int, Boolean> {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val pct = i?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) } ?: 0
        return pct to ((i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
    }

    private suspend fun fire(e: RuleEntity, s: RuleSpec, event: DeviceEvent) {
        val now = System.currentTimeMillis()
        if (now - (lastFired[e.id] ?: 0) < DEBOUNCE_MS) return
        if (rules.runsSince(e.id, now - 86_400_000L) >= DAILY_CAP) return
        lastFired[e.id] = now
        rules.upsert(e.copy(lastFiredAt = now))
        val sessionId = "$AUTO_SESSION_PREFIX${e.id}_${UUID.randomUUID()}"
        Log.i(TAG, "rule ${e.id} '${s.name}' fired by $event")
        val outcome = try {
            when (val a = s.action) {
                is RuleAction.Steps -> {
                    val results = a.steps.map { step ->
                        capabilities.invoke(buildJsonObject {
                            put("tool", step.tool); put("sessionId", sessionId); put("callId", UUID.randomUUID().toString()); put("args", step.args)
                        })
                    }
                    if (results.all { it.ok }) "ok" else "failed: " + results.firstOrNull { !it.ok }?.errorMessage
                }
                is RuleAction.Agent -> {
                    sessions.get().prompt(sessionId, "[Automation \"${s.name}\" triggered by ${describe(event)}]\n${a.prompt}", ruleId = e.id)
                    "started"
                }
            }
        } catch (ex: Exception) {
            Log.w(TAG, "rule ${e.id} failed", ex); "error: ${ex.message}"
        }
        rules.insertRun(RuleRunEntity(ruleId = e.id, firedAt = now, sessionId = sessionId.takeIf { s.action is RuleAction.Agent }, outcome = outcome))
        if (outcome != "started") Notifications.post(context, Notifications.CHANNEL_TASKS, e.id.toInt() + 1000,
            context.getString(R.string.notif_rule_ran, s.name), outcome, null)
    }

    private fun describe(e: DeviceEvent) = when (e) {
        is DeviceEvent.Notification -> "notification from ${e.packageName}: ${e.title} — ${e.text.take(500)}"
        is DeviceEvent.Wifi -> "Wi-Fi ${if (e.connected) "connected" else "disconnected"}${e.ssid?.let { " ($it)" } ?: ""}"
        else -> e.toString()
    }

    // ---- time --------------------------------------------------------------------------------

    private fun alarmIntent(id: Long) = PendingIntent.getBroadcast(context, id.toInt(),
        Intent(context, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_TIME).putExtra("ruleId", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun cancelAlarm(id: Long) = context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(id))

    private fun scheduleAlarm(id: Long, t: Trigger.Time) {
        val next = nextFire(t) ?: return
        val am = context.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent(id))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent(id))
    }

    private fun nextFire(t: Trigger.Time): Long? {
        val now = System.currentTimeMillis()
        t.onceAtEpochMs?.let { return it.takeIf { it > now } }
        t.everyMinutes?.let { return now + it.coerceAtLeast(15) * 60_000L }
        val at = t.at?.let(LocalTime::parse) ?: return null
        var dt = LocalDateTime.of(LocalDate.now(), at)
        repeat(8) {
            if (dt.isAfter(LocalDateTime.now()) && (t.days.isEmpty() || dt.dayOfWeek.value in t.days))
                return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            dt = dt.plusDays(1)
        }
        return null
    }

    // ---- geofence ------------------------------------------------------------------------------
    // With Play services: GeofencingClient. Its transition broadcasts are on Android's list of
    // background foreground-service-start exemptions, so an agent rule can start the engine, and
    // fused location keeps it cheap. Without Play services (many CN ROMs): the platform
    // LocationManager.addProximityAlert, which is NOT exempt and only sees background location a few
    // times an hour — such rules may fire minutes late, and if the FGS start is refused the run
    // degrades to the 点此执行 notification (DeferredRuns). The rule UI/Doctor say so.

    val playServicesGeofencing: Boolean by lazy {
        runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS }.getOrDefault(false)
    }

    private fun geofenceIntent(id: Long) = PendingIntent.getBroadcast(context, (id + 500_000).toInt(),
        Intent(context, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_GEOFENCE).putExtra("ruleId", id),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** One shared PendingIntent for all Play-services geofences (request id = rule id). */
    private val gmsGeofenceIntent by lazy {
        PendingIntent.getBroadcast(context, 499_999, Intent(context, TriggerReceiver::class.java).setAction(TriggerReceiver.ACTION_GEOFENCE_GMS),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun registerGeofence(id: Long, g: Trigger.Geofence) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        if (playServicesGeofencing) {
            val fence = Geofence.Builder().setRequestId(id.toString()).setCircularRegion(g.lat, g.lng, g.radiusM)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT).build()
            val request = GeofencingRequest.Builder().setInitialTrigger(0).addGeofence(fence).build()
            @Suppress("MissingPermission")
            runCatching { LocationServices.getGeofencingClient(context).addGeofences(request, gmsGeofenceIntent)
                .addOnFailureListener { Log.w(TAG, "geofence $id not registered", it) } }
            return
        }
        @Suppress("MissingPermission")
        context.getSystemService(LocationManager::class.java).addProximityAlert(g.lat, g.lng, g.radiusM, -1, geofenceIntent(id))
    }

    private fun cancelGeofence(id: Long) = runCatching {
        if (playServicesGeofencing) LocationServices.getGeofencingClient(context).removeGeofences(listOf(id.toString()))
        else context.getSystemService(LocationManager::class.java).removeProximityAlert(geofenceIntent(id))
    }


    private companion object {
        const val TAG = "TriggerHub"
        const val DEBOUNCE_MS = 60_000L
        const val DAILY_CAP = 50
    }
}
