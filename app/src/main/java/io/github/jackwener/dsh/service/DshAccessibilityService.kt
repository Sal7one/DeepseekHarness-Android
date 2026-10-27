package io.github.jackwener.dsh.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.jackwener.dsh.core.trigger.TriggerEvents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Screen reading and input. Also reports foreground-app changes for "app opened" automations
 * and for the quick panel's "current page" context.
 */
class DshAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != packageName && pkg != _foreground.value) {
                _foreground.value = pkg
                TriggerEvents.appOpened(pkg)
            }
        }
    }

    /** Snapshot of the current screen as a compact, numbered node list. */
    fun snapshot(displayId: Int = Display.DEFAULT_DISPLAY): ScreenSnapshot {
        val roots = if (Build.VERSION.SDK_INT >= 30) {
            windowsOnAllDisplays.get(displayId)?.mapNotNull { it.root } ?: emptyList()
        } else listOfNotNull(rootInActiveWindow)
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        roots.forEach { root -> walk(root, 0, nodes, sb) }
        return ScreenSnapshot(displayId, roots.firstOrNull()?.packageName?.toString(), sb.toString(), nodes)
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, out: MutableList<AccessibilityNodeInfo>, sb: StringBuilder) {
        if (!n.isVisibleToUser) return
        val text = n.text?.toString()?.take(120)
        val desc = n.contentDescription?.toString()?.take(120)
        val interesting = !text.isNullOrBlank() || !desc.isNullOrBlank() || n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
        var d = depth
        if (interesting && depth < 40) {
            val id = out.size
            out += n
            val r = Rect().also(n::getBoundsInScreen)
            sb.append("  ".repeat(minOf(depth, 12))).append('[').append(id).append("] ")
            sb.append(n.className?.toString()?.substringAfterLast('.') ?: "View")
            if (!text.isNullOrBlank()) sb.append(" \"").append(text.replace('\n', ' ')).append('"')
            if (!desc.isNullOrBlank() && desc != text) sb.append(" desc=\"").append(desc.replace('\n', ' ')).append('"')
            n.viewIdResourceName?.substringAfter(":id/")?.let { sb.append(" #").append(it) }
            val flags = buildList {
                if (n.isClickable) add("click"); if (n.isLongClickable) add("long"); if (n.isEditable) add("edit")
                if (n.isScrollable) add("scroll"); if (n.isCheckable) add(if (n.isChecked) "checked" else "unchecked")
                if (!n.isEnabled) add("disabled"); if (n.isFocused) add("focused")
            }
            if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append('}')
            sb.append(" @").append(r.centerX()).append(',').append(r.centerY()).append('\n')
            d = depth + 1
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, d, out, sb) }
    }

    suspend fun tap(x: Float, y: Float, durationMs: Long = 60, displayId: Int = Display.DEFAULT_DISPLAY) =
        gesture(Path().apply { moveTo(x, y) }, durationMs, displayId)

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long, displayId: Int = Display.DEFAULT_DISPLAY) =
        gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, durationMs, displayId)

    private suspend fun gesture(path: Path, durationMs: Long, displayId: Int): Boolean = suspendCancellableCoroutine { cont ->
        val builder = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
        if (Build.VERSION.SDK_INT >= 30) builder.setDisplayId(displayId)
        val ok = dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }

    suspend fun screenshot(displayId: Int = Display.DEFAULT_DISPLAY): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(displayId, Executors.newSingleThreadExecutor(), object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    if (cont.isActive) cont.resume(bmp)
                }
                override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
            })
        }
    }

    companion object {
        @Volatile var instance: DshAccessibilityService? = null
            private set
        private val _foreground = MutableStateFlow<String?>(null)
        val foregroundPackage: StateFlow<String?> = _foreground
    }
}

class ScreenSnapshot(val displayId: Int, val packageName: String?, val text: String, val nodes: List<AccessibilityNodeInfo>)
