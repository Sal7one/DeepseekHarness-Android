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
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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

    /** One shared thread for screenshot callbacks (a new executor per capture leaked a thread each time). */
    private val shotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (instance === this) instance = null; shotExecutor.shutdown(); super.onDestroy() }
    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == _foreground.value || pkg == SYSTEM_UI || pkg in imePackages()) return
        // Only a real app window counts as "opened": dialogs, the shade, keyboards and overlays from
        // other packages also send state changes.
        val active = runCatching { windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) } }.getOrNull()
        val activePkg = active?.root?.packageName?.toString()
        if (activePkg != null && activePkg != pkg) return
        _foreground.value = pkg
        TriggerEvents.appOpened(pkg)
    }

    private fun imePackages(): Set<String> = runCatching {
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    /**
     * Snapshot of the current screen as a compact, numbered node list. App windows come first (the
     * focused one names the package); system UI (status bar, shade) follows under its own header;
     * keyboards, accessibility overlays and DSH's own windows (floating ball, quick panel) are left out
     * unless DSH is the only app on screen.
     */
    fun snapshot(displayId: Int = Display.DEFAULT_DISPLAY): ScreenSnapshot {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        if (Build.VERSION.SDK_INT < 30) {
            val root = rootInActiveWindow
            root?.let { walk(it, 0, nodes, sb) }
            return ScreenSnapshot(displayId, root?.packageName?.toString(), sb.toString(), nodes)
        }
        val wins = windowsOnAllDisplays.get(displayId).orEmpty().mapNotNull { w -> w.root?.let { w to it } }
        val apps = wins.filter { it.first.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val others = apps.filter { it.second.packageName?.toString() != packageName }
        val shownApps = (others.ifEmpty { apps }).sortedByDescending { it.first.isActive || it.first.isFocused }
        val system = wins.filter { it.first.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.second.packageName?.toString() != packageName }
        shownApps.forEach { (_, root) -> walk(root, 0, nodes, sb) }
        if (system.isNotEmpty()) {
            val mark = sb.length
            sb.append("-- system UI (status bar / notification shade) --\n")
            val before = nodes.size
            system.forEach { (_, root) -> walk(root, 0, nodes, sb) }
            if (nodes.size == before) sb.setLength(mark)
        }
        val pkg = shownApps.firstOrNull()?.second?.packageName?.toString() ?: wins.firstOrNull()?.second?.packageName?.toString()
        return ScreenSnapshot(displayId, pkg, sb.toString(), nodes)
    }

    /**
     * Presses the keyboard's action key (Enter / Search / Go / Send) in the focused field: the
     * accessibility IME-enter action (API 30+), then the accessibility service's own input connection
     * (API 33+), then DSH 输入法 when it is the current keyboard. False when none applied.
     */
    fun pressImeAction(search: Boolean): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (Build.VERSION.SDK_INT >= 30 && focused != null &&
            focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
        if (Build.VERSION.SDK_INT >= 33) inputMethod?.let { im ->
            val ic = im.currentInputConnection
            if (ic != null) {
                val declared = (im.currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                val action = when {
                    search -> EditorInfo.IME_ACTION_SEARCH
                    declared != EditorInfo.IME_ACTION_NONE && declared != EditorInfo.IME_ACTION_UNSPECIFIED -> declared
                    else -> EditorInfo.IME_ACTION_DONE
                }
                ic.performEditorAction(action); return true // void: the editor gets it if a field is connected
            }
        }
        return DshInputMethodService.instance?.editorAction(search) == true
    }

    /** Deletes the character before the cursor in the focused field (API 33+ input connection, else set text). */
    fun deleteBackward(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) inputMethod?.currentInputConnection?.let { it.deleteSurroundingText(1, 0); return true }
        val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val t = n.text?.toString() ?: return false
        if (t.isEmpty() || n.isPassword) return false
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, t.dropLast(1))
        })
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

    suspend fun screenshot(displayId: Int = Display.DEFAULT_DISPLAY): Bitmap? = capture(displayId).bitmap

    /** Screenshot plus the failure code (ERROR_TAKE_SCREENSHOT_SECURE_WINDOW = FLAG_SECURE on Android 14+). */
    class Capture(val bitmap: Bitmap?, val errorCode: Int?)

    suspend fun capture(displayId: Int = Display.DEFAULT_DISPLAY): Capture {
        if (Build.VERSION.SDK_INT < 30) return Capture(null, null)
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(displayId, shotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    if (cont.isActive) cont.resume(Capture(bmp, null))
                }
                override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(Capture(null, errorCode)) }
            })
        }
    }

    /**
     * Android 13+: commit text through the accessibility service's own input connection
     * (flagInputMethodEditor) — works in most editors without switching keyboards.
     */
    fun commitViaA11yInput(text: String, replace: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val ic = inputMethod?.currentInputConnection ?: return false
        if (replace) { ic.performContextMenuAction(android.R.id.selectAll); ic.commitText("", 1, null) }
        ic.commitText(text, 1, null)
        return true
    }

    companion object {
        @Volatile var instance: DshAccessibilityService? = null
            private set
        private const val SYSTEM_UI = "com.android.systemui"
        private val _foreground = MutableStateFlow<String?>(null)
        val foregroundPackage: StateFlow<String?> = _foreground
    }
}

class ScreenSnapshot(val displayId: Int, val packageName: String?, val text: String, val nodes: List<AccessibilityNodeInfo>)
