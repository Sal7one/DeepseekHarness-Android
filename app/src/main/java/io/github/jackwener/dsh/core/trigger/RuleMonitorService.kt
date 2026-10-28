package io.github.jackwener.dsh.core.trigger

import android.Manifest
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.platform.Notifications

/**
 * Lightweight foreground service that only exists while some rule needs runtime-registered
 * broadcasts (Wi-Fi, Bluetooth, power, battery, headset, screen). It never loads the engine.
 */
class RuleMonitorService : Service() {
    private var lastBattery = -1
    private var lastSsid: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val ev = when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> DeviceEvent.Power(true)
                Intent.ACTION_POWER_DISCONNECTED -> DeviceEvent.Power(false)
                Intent.ACTION_HEADSET_PLUG -> DeviceEvent.Headset(i.getIntExtra("state", 0) == 1)
                Intent.ACTION_SCREEN_ON -> DeviceEvent.Screen("on")
                Intent.ACTION_SCREEN_OFF -> DeviceEvent.Screen("off")
                Intent.ACTION_USER_PRESENT -> DeviceEvent.Screen("unlock")
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    @Suppress("DEPRECATION") val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val name = if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) d?.name else null
                    DeviceEvent.Bluetooth(i.action == BluetoothDevice.ACTION_ACL_CONNECTED, name)
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val pct = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (pct == lastBattery) return
                    lastBattery = pct; DeviceEvent.Battery(pct)
                }
                else -> return
            }
            TriggerEvents.emit(ev)
        }
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val ssid = (caps.transportInfo as? WifiInfo)?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" }
            if (ssid != lastSsid) { lastSsid = ssid; TriggerEvents.emit(DeviceEvent.Wifi(true, ssid)) }
        }
        override fun onLost(network: Network) { TriggerEvents.emit(DeviceEvent.Wifi(false, lastSsid)); lastSsid = null }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(Notifications.ID_MONITOR, NotificationCompat.Builder(this, Notifications.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_dsh).setContentTitle(getString(R.string.monitor_running)).setOngoing(true).setSilent(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        registerReceiver(receiver, IntentFilter().apply {
            listOf(Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED, Intent.ACTION_HEADSET_PLUG, Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT, BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED,
                Intent.ACTION_BATTERY_CHANGED).forEach(::addAction)
        }, RECEIVER_NOT_EXPORTED)
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), netCallback)
    }

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(receiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Whether the monitor is up (Doctor). */
        @Volatile var running = false
            private set
        /** Set when Android refused to start it from the background (Doctor explains the battery exemption). */
        @Volatile var lastStartRefused = false
            private set

        fun setRunning(context: Context, running: Boolean) {
            val i = Intent(context, RuleMonitorService::class.java)
            if (running) {
                lastStartRefused = runCatching { context.startForegroundService(i) }
                    .onFailure { android.util.Log.w("RuleMonitorService", "start refused", it) }.isFailure
            } else context.stopService(i)
        }
    }
}
