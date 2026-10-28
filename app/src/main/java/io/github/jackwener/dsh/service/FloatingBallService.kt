package io.github.jackwener.dsh.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.platform.Notifications
import io.github.jackwener.dsh.feature.quick.QuickActivity
import kotlin.math.abs

/** Edge-docked floating button that opens the quick panel from any app. Hidden while our own UI is in front. */
class FloatingBallService : Service() {
    private var view: ImageView? = null
    private val appInFront = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) { view?.visibility = android.view.View.GONE }
        override fun onStop(owner: LifecycleOwner) { view?.visibility = android.view.View.VISIBLE }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()
        startForeground(Notifications.ID_BALL, NotificationCompat.Builder(this, Notifications.CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_dsh).setContentTitle(getString(R.string.ball_running)).setOngoing(true).setSilent(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val wm = getSystemService(WindowManager::class.java)
        val size = (48 * resources.displayMetrics.density).toInt()
        val margin = (8 * resources.displayMetrics.density).toInt()
        // Full window width (displayMetrics excludes insets on some devices, which pushed the ball half off-screen).
        val screenW = wm.currentWindowMetrics.bounds.width()
        val screenH = wm.currentWindowMetrics.bounds.height()
        val lp = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - size - margin; y = screenH / 3
        }
        val v = ImageView(this).apply {
            setImageResource(R.drawable.ic_ball)
            alpha = 0.85f
            contentDescription = getString(R.string.app_name)
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; true }
                MotionEvent.ACTION_MOVE -> { lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt(); wm.updateViewLayout(v, lp); true }
                MotionEvent.ACTION_UP -> {
                    if (abs(e.rawX - downX) < 12 && abs(e.rawY - downY) < 12) open()
                    else { lp.x = if (lp.x + size / 2 < screenW / 2) margin else screenW - size - margin; wm.updateViewLayout(v, lp) }
                    true
                }
                else -> false
            }
        }
        wm.addView(v, lp)
        view = v
        ProcessLifecycleOwner.get().lifecycle.addObserver(appInFront)
    }

    private fun open() = startActivity(Intent(this, QuickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appInFront)
        view?.let { getSystemService(WindowManager::class.java).removeView(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun setEnabled(context: Context, on: Boolean) {
            val i = Intent(context, FloatingBallService::class.java)
            if (on && Settings.canDrawOverlays(context)) context.startForegroundService(i) else context.stopService(i)
        }
    }
}
