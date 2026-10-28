package io.github.jackwener.dsh.core.capability.tools

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.InvalidArgsException
import io.github.jackwener.dsh.core.capability.PermissionMissingException
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.bool
import io.github.jackwener.dsh.core.capability.int
import io.github.jackwener.dsh.core.capability.reqStr
import io.github.jackwener.dsh.core.capability.str
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.capability.ShellGate
import io.github.jackwener.dsh.core.capability.ShellUse
import io.github.jackwener.dsh.core.platform.AppVisibility
import io.github.jackwener.dsh.core.platform.ShellUnavailableException
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gate: ShellGate,
) : ToolSet {
    private val shizuku get() = gate.shell
    private val audio get() = context.getSystemService(AudioManager::class.java)

    private fun streamOf(name: String?) = when (name) {
        "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION; "call" -> AudioManager.STREAM_VOICE_CALL
        else -> AudioManager.STREAM_MUSIC
    }

    private suspend fun shell(cmd: String, what: String): ToolResult {
        if (!shizuku.available) throw ShellUnavailableException(gate.access.state.value)
        val r = shizuku.exec(cmd)
        return if (r.code == 0) ToolResult.text("Done via Shizuku: $what. ${r.output.trim()}".trim()) else ToolResult.error("failed", r.output.ifBlank { "exit ${r.code}" })
    }

    /** Shizuku attempt for a [ShellUse.Fallback] tool: null (→ fallback path) when it fails. */
    private suspend fun shellOrNull(cmd: String, what: String): ToolResult? = runCatching { shell(cmd, what) }.getOrNull()?.takeIf { it.ok }

    /**
     * The non-shell path for a toggle: a system panel/page the user finishes with one tap. Android
     * only lets DSH open it while DSH is visible (or may draw overlays); otherwise say so plainly.
     */
    private fun openForUser(intent: Intent, what: String): ToolResult {
        val reason = gate.access.state.value.let { if (it.ready) "the Shizuku command failed" else it.reason }
        if (!AppVisibility.isForeground && !Settings.canDrawOverlays(context))
            return ToolResult.error("needs_user", "Could not $what directly ($reason) and Android blocks opening system panels while DSH is in the background. Ask the user to do it.")
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.fold(
            { ToolResult.text("path=system_panel: $reason, so I opened the system page to $what. The user must tap the switch there; verify with device_status before relying on it.") },
            { ToolResult.error("failed", "Could not $what ($reason) and the system page failed to open: ${it.message}") })
    }

    override val tools = listOf(
        object : Tool("device_status", ToolGroup.System, Risk.read, false,
            "Battery, charging, network (Wi-Fi SSID if permitted), storage, ringer mode, volumes, Do-Not-Disturb, screen lock, time.", {}) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = battery?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }
                val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
                val cm = context.getSystemService(ConnectivityManager::class.java)
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                val net = when {
                    caps == null -> "offline"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi" + (wifiSsid()?.let { " ($it)" } ?: "")
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    else -> "other"
                }
                val stat = StatFs(Environment.getExternalStorageDirectory().path)
                val nm = context.getSystemService(NotificationManager::class.java)
                val km = context.getSystemService(android.app.KeyguardManager::class.java)
                return ToolResult.text(buildString {
                    appendLine("time: ${java.time.ZonedDateTime.now()}")
                    appendLine("battery: $level%${if (plugged) ", charging" else ""}")
                    appendLine("network: $net")
                    appendLine("storage: ${stat.availableBytes / 1_000_000_000}GB free of ${stat.totalBytes / 1_000_000_000}GB")
                    appendLine("ringer: ${when (audio.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }}")
                    appendLine("volume music ${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, ring ${audio.getStreamVolume(AudioManager.STREAM_RING)}/${audio.getStreamMaxVolume(AudioManager.STREAM_RING)}")
                    appendLine("do-not-disturb: ${nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL}")
                    appendLine("locked: ${km.isDeviceLocked}")
                    append("location enabled: ${context.getSystemService(LocationManager::class.java).isLocationEnabled}")
                })
            }

            @Suppress("DEPRECATION")
            private fun wifiSsid(): String? = runCatching {
                context.getSystemService(WifiManager::class.java).connectionInfo.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" }
            }.getOrNull()
        },
        object : Tool("sound_mode", ToolGroup.System, Risk.write, false, "Set ringer mode.", {
            string("mode", "Mode", required = true, enum = listOf("normal", "vibrate", "silent"))
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val nm = context.getSystemService(NotificationManager::class.java)
                val mode = args.reqStr("mode")
                if (mode == "silent" && !nm.isNotificationPolicyAccessGranted) throw PermissionMissingException("Do Not Disturb access")
                audio.ringerMode = when (mode) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
                return ToolResult.text("Ringer mode: $mode")
            }
        },
        object : Tool("volume_set", ToolGroup.System, Risk.write, false, "Set a volume stream to a percentage (0-100).", {
            string("stream", "Stream", required = true, enum = listOf("music", "ring", "alarm", "notification", "call"))
            int("percent", "0-100", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val s = streamOf(args.str("stream"))
                val pct = (args.int("percent") ?: 0).coerceIn(0, 100)
                audio.setStreamVolume(s, audio.getStreamMaxVolume(s) * pct / 100, 0)
                return ToolResult.text("Volume ${args.str("stream")} = $pct%")
            }
        },
        object : Tool("dnd_set", ToolGroup.System, Risk.write, false, "Turn Do Not Disturb on or off.", {
            bool("on", "true = enable DND", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val nm = context.getSystemService(NotificationManager::class.java)
                if (!nm.isNotificationPolicyAccessGranted) throw PermissionMissingException("Do Not Disturb access")
                nm.setInterruptionFilter(if (args.bool("on") == true) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                return ToolResult.text("Do Not Disturb ${if (args.bool("on") == true) "on" else "off"}")
            }
        },
        object : Tool("brightness_set", ToolGroup.System, Risk.write, false, "Set screen brightness 0-100, or auto.", {
            int("percent", "0-100"); bool("auto", "Use adaptive brightness")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                if (!Settings.System.canWrite(context)) throw PermissionMissingException("Modify system settings")
                val cr = context.contentResolver
                if (args.bool("auto") == true) {
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                    return ToolResult.text("Adaptive brightness on")
                }
                val pct = (args.int("percent") ?: throw InvalidArgsException("percent or auto")).coerceIn(0, 100)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceAtLeast(1))
                return ToolResult.text("Brightness $pct%")
            }
        },
        object : Tool("flashlight", ToolGroup.System, Risk.write, false, "Turn the flashlight on or off.", {
            bool("on", "", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val cm = context.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                cm.setTorchMode(id, args.bool("on") == true)
                return ToolResult.text("Flashlight ${if (args.bool("on") == true) "on" else "off"}")
            }
        },
        object : Tool("alarm_set", ToolGroup.System, Risk.write, true, "Create an alarm in the clock app (or a countdown timer).", {
            int("hour", "0-23"); int("minute", "0-59"); string("label", "Label")
            int("timerSeconds", "If set, start a countdown timer instead")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val timer = args.int("timerSeconds")
                val i = if (timer != null) Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, timer)
                else Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, args.int("hour") ?: throw InvalidArgsException("hour"))
                    .putExtra(AlarmClock.EXTRA_MINUTES, args.int("minute") ?: 0)
                args.str("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                i.putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                return ToolResult.text(if (timer != null) "Timer set for ${timer}s" else "Alarm set ${args.int("hour")}:${"%02d".format(args.int("minute") ?: 0)}")
            }
        },
        object : Tool("wifi_toggle", ToolGroup.System, Risk.write, false,
            "Turn Wi-Fi on/off. Directly through Shizuku when available; otherwise opens the system Wi-Fi panel for the user to tap.",
            { bool("on", "", required = true) }, ShellUse.Fallback, "open the system Wi-Fi panel and the user taps the switch") {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val on = args.bool("on") == true
                if (runCatching { context.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrNull() == on)
                    return ToolResult.text("Wi-Fi is already ${if (on) "on" else "off"}; nothing to do.")
                if (gate.acquire(call, name, optional = true)) shellOrNull("svc wifi ${if (on) "enable" else "disable"}", "switch Wi-Fi ${if (on) "on" else "off"}")?.let { return it }
                return openForUser(Intent(Settings.Panel.ACTION_WIFI), "switch Wi-Fi ${if (on) "on" else "off"}")
            }
        },
        object : Tool("bluetooth_toggle", ToolGroup.System, Risk.write, false,
            "Turn Bluetooth on/off. Directly through Shizuku when available; otherwise shows the system Bluetooth prompt/page for the user to confirm.",
            { bool("on", "", required = true) }, ShellUse.Fallback, "show the system Bluetooth prompt or page and the user confirms") {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val on = args.bool("on") == true
                val adapter = runCatching { context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter }.getOrNull()
                if (adapter == null) return ToolResult.error("unsupported", "This phone has no Bluetooth adapter.")
                if (runCatching { adapter.isEnabled }.getOrNull() == on) return ToolResult.text("Bluetooth is already ${if (on) "on" else "off"}; nothing to do.")
                if (gate.acquire(call, name, optional = true)) shellOrNull("cmd bluetooth_manager ${if (on) "enable" else "disable"}", "switch Bluetooth ${if (on) "on" else "off"}")?.let { return it }
                val canAsk = on && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
                return openForUser(if (canAsk) Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE) else Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                    "switch Bluetooth ${if (on) "on" else "off"}")
            }
        },
        object : Tool("mobile_data_toggle", ToolGroup.System, Risk.write, false,
            "Turn mobile data on/off. Directly through Shizuku when available; otherwise opens the system Internet panel for the user to tap.",
            { bool("on", "", required = true) }, ShellUse.Fallback, "open the system Internet panel and the user taps the switch") {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val on = args.bool("on") == true
                if (gate.acquire(call, name, optional = true)) shellOrNull("svc data ${if (on) "enable" else "disable"}", "switch mobile data ${if (on) "on" else "off"}")?.let { return it }
                return openForUser(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY), "switch mobile data ${if (on) "on" else "off"}")
            }
        },
        object : Tool("setting_put", ToolGroup.System, Risk.dangerous, false,
            "Write an Android setting via `settings put <namespace> <key> <value>` (needs Shizuku). For things no dedicated tool covers.", {
                string("namespace", "", required = true, enum = listOf("system", "secure", "global"))
                string("key", "", required = true); string("value", "", required = true)
            }, ShellUse.Required) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val v = args.reqStr("value").replace("'", "")
                return shell("settings put ${args.reqStr("namespace")} ${args.reqStr("key").filter { it.isLetterOrDigit() || it in "_." }} '$v'", "change setting")
            }
        },
    )
}
