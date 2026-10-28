package io.github.jackwener.dsh.core.capability.tools

import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.InvalidArgsException
import io.github.jackwener.dsh.core.capability.PermissionMissingException
import io.github.jackwener.dsh.core.capability.TextInput
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.bool
import io.github.jackwener.dsh.core.capability.int
import io.github.jackwener.dsh.core.capability.reqStr
import io.github.jackwener.dsh.core.capability.str
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.platform.ShizukuShell
import io.github.jackwener.dsh.core.session.SessionStore
import io.github.jackwener.dsh.service.DshAccessibilityService
import io.github.jackwener.dsh.service.ScreenSnapshot
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/** Reading and operating the screen through the accessibility service (Shizuku `input` as fallback). */
@Singleton
class ScreenTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuShell,
    private val vscreen: dagger.Lazy<VirtualScreenTools>,
    private val textInput: TextInput,
    private val sessions: dagger.Lazy<SessionStore>,
) : ToolSet {
    /** The snapshot the model last saw per display, with each node's text at that time (stale-id check). */
    private class Shown(val snap: ScreenSnapshot, val generation: Int, val signatures: List<String>)
    private val lastShown = ConcurrentHashMap<Int, Shown>()
    private val generations = AtomicInteger(0)

    private fun svc() = DshAccessibilityService.instance ?: throw PermissionMissingException("Accessibility service (DSH screen control)")
    private fun display(args: JsonObject) = args.int("displayId") ?: Display.DEFAULT_DISPLAY

    private fun signature(n: AccessibilityNodeInfo) = "${n.className}|${n.text}|${n.contentDescription}|${n.viewIdResourceName}"

    /** Records a snapshot as shown to the model and returns its generation. */
    private fun show(snap: ScreenSnapshot): Int {
        val g = generations.incrementAndGet()
        lastShown[snap.displayId] = Shown(snap, g, snap.nodes.map(::signature))
        return g
    }

    /** Node from the model's last screen view; refuses ids whose element changed or disappeared since. */
    private fun node(args: JsonObject): AccessibilityNodeInfo? {
        val id = args.int("node") ?: return null
        val shown = lastShown[display(args)] ?: throw InvalidArgsException("Call screen_read first to get node ids")
        val n = shown.snap.nodes.getOrNull(id) ?: throw InvalidArgsException("Unknown node $id; call screen_read again")
        if (!n.refresh() || signature(n) != shown.signatures[id])
            throw InvalidArgsException("Node $id changed or disappeared since the last screen read (#${shown.generation}); call screen_read again and use the new ids.")
        return n
    }

    /** Looks an element up by visible text without replacing the ids the model holds. */
    private fun findByText(text: String, displayId: Int): AccessibilityNodeInfo? {
        val snap = svc().snapshot(displayId)
        return snap.nodes.firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            ?: snap.nodes.firstOrNull { (it.text?.toString() ?: it.contentDescription?.toString() ?: "").contains(text, ignoreCase = true) }
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        repeat(6) { if (cur == null || cur!!.isClickable) return cur; cur = cur!!.parent }
        return null
    }

    private suspend fun settle() = delay(400)

    private fun metrics(displayId: Int): DisplayMetrics = DisplayMetrics().also { m ->
        val d = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
            ?: context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION") d.getRealMetrics(m)
    }

    private fun screenLine(displayId: Int) = metrics(displayId).let { "screen ${it.widthPixels}x${it.heightPixels} px" }

    private fun body(snap: ScreenSnapshot, max: Int): String {
        val text = snap.text.ifBlank { return "(no readable elements; try screen_capture)" }
        if (text.length <= max) return text
        val cut = text.lastIndexOf('\n', max).takeIf { it > 0 } ?: max
        val hidden = text.substring(cut).count { it == '\n' }
        return text.substring(0, cut) + "\n…(truncated: $hidden more elements; use screen_scroll or screen_read for the full list)"
    }

    private fun header(snap: ScreenSnapshot, g: Int) = "App: ${snap.packageName} · read #$g · ${screenLine(snap.displayId)}"

    private suspend fun readResult(displayId: Int, note: String): ToolResult {
        settle()
        val snap = DshAccessibilityService.instance?.snapshot(displayId) ?: return ToolResult.text(note)
        val g = show(snap)
        return ToolResult.text("$note\nScreen now — ${header(snap, g)} (node ids below replace earlier ones):\n${body(snap, 6000)}")
    }

    private companion object {
        const val ERROR_INTERVAL_TOO_SHORT = 3 // AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
        const val ERROR_SECURE_WINDOW = 6 /* AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW (API 34) */
        fun screenshotError(code: Int?) = when (code) {
            null -> "screenshots need Android 11+"
            1 -> "internal error"; 2 -> "accessibility screenshots are not enabled for DSH (re-enable the DSH accessibility service)"
            ERROR_INTERVAL_TOO_SHORT -> "called too often; wait a second"
            4 -> "invalid display"; 5 -> "invalid window"
            else -> "error code $code"
        }
    }

    /**
     * Before Android 14 a FLAG_SECURE window is captured as pure black. Dark UIs are near-black but not
     * uniformly zero, so require every sample on a fine grid to be (almost) exactly black.
     */
    private fun isBlank(bmp: android.graphics.Bitmap): Boolean {
        for (y in 0 until 32) for (x in 0 until 32) {
            val p = bmp.getPixel(x * (bmp.width - 1) / 31, y * (bmp.height - 1) / 31)
            if ((p shr 16 and 0xff) + (p shr 8 and 0xff) + (p and 0xff) > 6) return false
        }
        return true
    }

    /** Protected (FLAG_SECURE) page: ask the user to do this step by hand (AutoGLM-style takeover). */
    private suspend fun protectedScreen(call: CallContext): ToolResult {
        val pkg = DshAccessibilityService.instance?.snapshot()?.packageName ?: "this app"
        if (call.unattended || call.sessionId.isEmpty()) return ToolResult.error("secure_screen",
            "The screen of $pkg is protected (screenshots are blocked). It cannot be automated without the user; stop and report this.")
        val done = call.waitFor("finish the protected step by hand") {
            sessions.get().requestTakeover(call.sessionId, "screen_capture", "$pkg 的当前页面禁止截图（支付、密码等安全页面），需要你在手机上完成这一步。")
        }
        return if (done) ToolResult.text("The screen of $pkg is protected, so the user completed this step by hand and tapped Continue. Use screen_read to see where things stand now; do not ask them to repeat it.")
        else ToolResult.error("user_declined", "The screen of $pkg is protected and the user chose to stop instead of doing the step by hand. Stop and summarize.")
    }

    private suspend fun shellKey(code: Int, d: Int): Boolean = shizuku.available && shizuku.exec("input -d $d keyevent $code").code == 0

    override val tools = listOf(
        object : Tool("screen_read", ToolGroup.Screen, Risk.read, true,
            "Read what is on screen as a numbered list of UI elements: [id] Type \"text\" #resource-id {flags} @x,y (center, in screen pixels). " +
                "Use the ids with screen_tap/screen_type; ids are only valid until the next screen read. Prefer this over screenshots.", {
                int("displayId", "Virtual screen id (default: main screen)")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val snap = svc().snapshot(display(args))
                val g = show(snap)
                return ToolResult.text("${header(snap, g)}\n${body(snap, 12000)}")
            }
        },
        object : Tool("screen_capture", ToolGroup.Screen, Risk.read, true,
            "Screenshot of the screen as an image. Use only when screen_read is not enough (images, canvas, games). " +
                "The image is downscaled; the caption gives the factor to convert image pixels to the screen pixels that screen_tap/screen_swipe take.", {
                int("displayId", "Virtual screen id (default: main screen)")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val d = display(args)
                if (d != Display.DEFAULT_DISPLAY) return vscreen.get().capture(d)
                var shot = svc().capture(d)
                if (shot.bitmap == null && shot.errorCode == ERROR_INTERVAL_TOO_SHORT) { delay(1_100); shot = svc().capture(d) }
                val bmp = shot.bitmap
                if (shot.errorCode == ERROR_SECURE_WINDOW) return protectedScreen(call)
                bmp ?: throw IllegalStateException("Screenshot failed: ${screenshotError(shot.errorCode)}")
                val interactive = context.getSystemService(PowerManager::class.java).isInteractive
                if (Build.VERSION.SDK_INT < 34 && interactive && isBlank(bmp)) return protectedScreen(call)
                val img = Images.encode(bmp)
                val k = bmp.width.toFloat() / img.width
                val caption = if (img.width == bmp.width) "image ${img.width}x${img.height} = screen pixels"
                    else "image ${img.width}x${img.height} = screen ${bmp.width}x${bmp.height} scaled ${"%.3f".format(1 / k)}. " +
                        "screen_tap/screen_swipe take SCREEN pixels: multiply image coordinates by ${"%.3f".format(k)}."
                return ToolResult.image(Images.MIME, Base64.encodeToString(img.bytes, Base64.NO_WRAP), caption)
            }
        },
        object : Tool("screen_tap", ToolGroup.Screen, Risk.write, true,
            "Tap an element (node id from the latest screen read, or visible text) or screen-pixel coordinates. Returns the screen afterwards.", {
                int("node", "Node id from the latest screen read")
                string("text", "Visible text/description of the element to tap")
                number("x", "X in screen pixels"); number("y", "Y in screen pixels")
                string("kind", "Tap kind", enum = listOf("tap", "long"))
                int("displayId", "Virtual screen id")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val d = display(args)
                val long = args.str("kind") == "long"
                val target = node(args) ?: args.str("text")?.let { findByText(it, d) ?: throw InvalidArgsException("No element with text '$it'; call screen_read") }
                if (target != null) {
                    val c = clickable(target)
                    if (c != null && c.performAction(if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK))
                        return readResult(d, "Tapped [${target.text ?: target.contentDescription ?: "element"}]")
                    val r = Rect().also(target::getBoundsInScreen)
                    return tapAt(r.exactCenterX(), r.exactCenterY(), long, d)
                }
                val x = args.int("x") ?: throw InvalidArgsException("Give node, text, or x/y")
                val y = args.int("y") ?: throw InvalidArgsException("missing y")
                return tapAt(x.toFloat(), y.toFloat(), long, d)
            }

            private suspend fun tapAt(x: Float, y: Float, long: Boolean, d: Int): ToolResult {
                val a11y = DshAccessibilityService.instance
                val ok = if (a11y != null) a11y.tap(x, y, if (long) 700 else 60, d)
                else if (shizuku.available) shizuku.exec(if (long) "input -d $d swipe ${x.toInt()} ${y.toInt()} ${x.toInt()} ${y.toInt()} 700"
                    else "input -d $d tap ${x.toInt()} ${y.toInt()}").code == 0
                else throw PermissionMissingException("Accessibility service (DSH screen control)")
                return readResult(d, if (ok) "Tapped at ${x.toInt()},${y.toInt()}" else "Tap was cancelled")
            }
        },
        object : Tool("screen_type", ToolGroup.Screen, Risk.write, true,
            "Type text into an editable field (node id, or the focused field). Replaces the field's content unless append=true. " +
                "submit=true then presses the keyboard's Enter/Search key. Works in WebView/Flutter fields too (falls back to the DSH keyboard, Shizuku or paste).", {
                string("text", "Text to enter", required = true)
                int("node", "Editable node id from the latest screen read (default: focused field)")
                bool("append", "Append instead of replacing")
                bool("submit", "Press Enter/Search afterwards (for search boxes without a button)")
                int("displayId", "Virtual screen id")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val d = display(args)
                val target = node(args) ?: focusedInput(d)
                val out = textInput.type(svc(), target, args.reqStr("text"), args.bool("append") == true)
                    ?: return ToolResult.error("input_failed", if (target == null) "No focused input field; tap the field first, then call screen_type again."
                        else "The field did not accept text by any method (set text, keyboard, Shizuku, paste). Tap the field and retry, or ask the user to type it.")
                val submitted = if (args.bool("submit") == true) " and pressed " + (pressEnter(false, d) ?: "Enter (failed: no method worked)") else ""
                return readResult(d, "Entered text (${out.method})$submitted" + (out.fieldText?.let { "; the field now shows: \"${it.take(300)}\"" } ?: ""))
            }

            private fun focusedInput(d: Int): AccessibilityNodeInfo? =
                if (d == Display.DEFAULT_DISPLAY) svc().findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                else svc().snapshot(d).nodes.firstOrNull { it.isEditable && it.isFocused }
        },
        object : Tool("screen_swipe", ToolGroup.Screen, Risk.write, true, "Swipe/drag from (x1,y1) to (x2,y2), in screen pixels.", {
            number("x1", "Start X (screen pixels)", required = true); number("y1", "Start Y (screen pixels)", required = true)
            number("x2", "End X (screen pixels)", required = true); number("y2", "End Y (screen pixels)", required = true)
            int("durationMs", "Default 300"); int("displayId", "Virtual screen id")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val d = display(args)
                fun i(k: String) = args.int(k) ?: throw InvalidArgsException("missing $k")
                svc().swipe(i("x1").toFloat(), i("y1").toFloat(), i("x2").toFloat(), i("y2").toFloat(), (args.int("durationMs") ?: 300).toLong().coerceIn(50, 5000), d)
                return readResult(d, "Swiped")
            }
        },
        object : Tool("screen_scroll", ToolGroup.Screen, Risk.write, true, "Scroll the screen or a scrollable node.", {
            string("direction", "Direction", required = true, enum = listOf("down", "up", "left", "right"))
            int("node", "Scrollable node id (optional)"); int("displayId", "Virtual screen id")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val d = display(args)
                val dir = args.reqStr("direction")
                val n = node(args)
                if (n != null && n.isScrollable) {
                    n.performAction(if (dir == "down" || dir == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                } else {
                    val m = metrics(d)
                    val cx = m.widthPixels / 2f; val cy = m.heightPixels / 2f
                    val dy = m.heightPixels * 0.3f; val dx = m.widthPixels * 0.3f
                    when (dir) {
                        "down" -> svc().swipe(cx, cy + dy, cx, cy - dy, 350, d)
                        "up" -> svc().swipe(cx, cy - dy, cx, cy + dy, 350, d)
                        "right" -> svc().swipe(cx + dx, cy, cx - dx, cy, 350, d)
                        else -> svc().swipe(cx - dx, cy, cx + dx, cy, 350, d)
                    }
                }
                return readResult(d, "Scrolled $dir")
            }
        },
        object : Tool("screen_key", ToolGroup.Screen, Risk.write, true,
            "Press a key. enter/search = the keyboard's action key in the focused field (submit a search box or a form); delete = backspace; " +
                "back/home/recents; notifications/quick_settings open the shade; lock_screen turns the screen off.", {
            string("key", "Key", required = true, enum = listOf("enter", "search", "delete", "back", "home", "recents", "notifications", "quick_settings", "lock_screen"))
            int("displayId", "Virtual screen id (back/home/enter/delete there need Shizuku)")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val key = args.reqStr("key")
                val d = display(args)
                val done: String = when (key) {
                    "enter", "search" -> pressEnter(key == "search", d) ?: return ToolResult.error("failed",
                        "Could not press $key: the focused field takes no editor action and Shizuku is not available. Tap the on-screen search/send button instead (screen_read to find it).")
                    "delete" -> when {
                        d == Display.DEFAULT_DISPLAY && svc().deleteBackward() -> "Deleted one character"
                        shellKey(67, d) -> "Pressed delete (Shizuku)"
                        else -> return ToolResult.error("failed", "No focused text field to delete from.")
                    }
                    else -> {
                        val global = when (key) {
                            "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME; "recents" -> GLOBAL_ACTION_RECENTS
                            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS; "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
                            else -> GLOBAL_ACTION_NOTIFICATIONS
                        }
                        if (d != Display.DEFAULT_DISPLAY) {
                            val code = when (key) { "back" -> 4; "home" -> 3; else -> throw InvalidArgsException("'$key' only works on the main screen") }
                            if (!shellKey(code, d)) return ToolResult.error("shizuku_unavailable", "Keys on the virtual screen need Shizuku.")
                            "Pressed $key on display $d"
                        } else if (svc().performGlobalAction(global)) "Pressed $key" else return ToolResult.error("failed", "Android refused '$key'.")
                    }
                }
                if (key == "lock_screen") return ToolResult.text(done)
                return readResult(d, done)
            }
        },
        object : Tool("screen_wait", ToolGroup.Screen, Risk.read, true,
            "Wait until an element with this text appears (or disappears with gone=true), or until an app is in front, instead of sleeping and polling. " +
                "Returns the screen when the condition holds or after the timeout.", {
                string("text", "Visible text/description to wait for")
                string("package", "Wait until this app is in front")
                bool("gone", "Wait for the text to disappear instead")
                int("timeoutMs", "Default 10000, max 30000")
                int("displayId", "Virtual screen id")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val text = args.str("text"); val pkg = args.str("package")
                if (text == null && pkg == null) throw InvalidArgsException("Give text and/or package")
                val d = display(args)
                val gone = args.bool("gone") == true
                val until = SystemClock.elapsedRealtime() + (args.int("timeoutMs") ?: 10_000).coerceIn(500, 30_000)
                while (true) {
                    val snap = svc().snapshot(d)
                    val hasText = text == null || snap.nodes.any { n ->
                        (n.text?.toString() ?: "").contains(text, true) || (n.contentDescription?.toString() ?: "").contains(text, true)
                    }
                    val ok = (pkg == null || snap.packageName == pkg) && (if (gone && text != null) !hasText else hasText)
                    if (ok || SystemClock.elapsedRealtime() > until) {
                        val g = show(snap)
                        return ToolResult.text("${if (ok) "Condition met" else "Timed out waiting"} — ${header(snap, g)}\n${body(snap, 6000)}")
                    }
                    delay(500)
                }
            }
        },
    )

    /** Enter/Search in the focused field: accessibility IME action, DSH keyboard, then Shizuku keyevent. Returns how, or null. */
    private suspend fun pressEnter(search: Boolean, d: Int): String? {
        if (d == Display.DEFAULT_DISPLAY && DshAccessibilityService.instance?.pressImeAction(search) == true) return if (search) "Search" else "Enter"
        if (shellKey(66, d)) return "Enter (Shizuku)"
        return null
    }
}
