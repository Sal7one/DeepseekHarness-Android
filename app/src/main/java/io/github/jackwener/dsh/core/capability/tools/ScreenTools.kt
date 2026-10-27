package io.github.jackwener.dsh.core.capability.tools

import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
import android.graphics.Rect
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.InvalidArgsException
import io.github.jackwener.dsh.core.capability.PermissionMissingException
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.int
import io.github.jackwener.dsh.core.capability.reqStr
import io.github.jackwener.dsh.core.capability.str
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.platform.ShizukuShell
import io.github.jackwener.dsh.service.DshAccessibilityService
import io.github.jackwener.dsh.service.ScreenSnapshot
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Reading and operating the screen through the accessibility service (Shizuku `input` as fallback). */
@Singleton
class ScreenTools @Inject constructor(private val shizuku: ShizukuShell, private val vscreen: dagger.Lazy<VirtualScreenTools>) : ToolSet {
    private val lastSnapshot = ConcurrentHashMap<Int, ScreenSnapshot>()

    private fun svc() = DshAccessibilityService.instance ?: throw PermissionMissingException("Accessibility service (DSH screen control)")
    private fun display(args: JsonObject) = args.int("displayId") ?: Display.DEFAULT_DISPLAY

    private fun node(args: JsonObject): AccessibilityNodeInfo? {
        val id = args.int("node") ?: return null
        val snap = lastSnapshot[display(args)] ?: throw InvalidArgsException("Call screen_read first to get node ids")
        return snap.nodes.getOrNull(id) ?: throw InvalidArgsException("Unknown node $id; call screen_read again")
    }

    private fun findByText(text: String, displayId: Int): AccessibilityNodeInfo? {
        val snap = svc().snapshot(displayId).also { lastSnapshot[displayId] = it }
        return snap.nodes.firstOrNull { it.text?.toString() == text || it.contentDescription?.toString() == text }
            ?: snap.nodes.firstOrNull { (it.text?.toString() ?: it.contentDescription?.toString() ?: "").contains(text, ignoreCase = true) }
    }

    private fun clickable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        repeat(6) { if (cur == null || cur!!.isClickable) return cur; cur = cur!!.parent }
        return null
    }

    private suspend fun settle() = delay(400)

    private suspend fun readResult(displayId: Int, note: String): ToolResult {
        settle()
        val snap = DshAccessibilityService.instance?.snapshot(displayId)?.also { lastSnapshot[displayId] = it } ?: return ToolResult.text(note)
        return ToolResult.text("$note\nScreen now (${snap.packageName}):\n${snap.text.take(6000)}")
    }

    override val tools = listOf(
        object : Tool("screen_read", ToolGroup.Screen, Risk.read, true,
            "Read what is on screen as a numbered list of UI elements: [id] Type \"text\" #resource-id {flags} @x,y. Use the ids with screen_tap/screen_type. Prefer this over screenshots.", {
                int("displayId", "Virtual screen id (default: main screen)")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val snap = svc().snapshot(display(args)).also { lastSnapshot[it.displayId] = it }
                return ToolResult.text("App: ${snap.packageName}\n${snap.text.ifBlank { "(no readable elements; try screen_capture)" }.take(12000)}")
            }
        },
        object : Tool("screen_capture", ToolGroup.Screen, Risk.read, true,
            "Screenshot of the screen as an image. Use only when screen_read is not enough (images, canvas, games).", {
                int("displayId", "Virtual screen id (default: main screen)")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val d = display(args)
                if (d != Display.DEFAULT_DISPLAY) return vscreen.get().capture(d)
                val bmp = svc().screenshot(d) ?: throw IllegalStateException("Screenshot failed (requires Android 11+)")
                return ToolResult.image("image/jpeg", Base64.encodeToString(Images.encode(bmp), Base64.NO_WRAP), "${bmp.width}x${bmp.height}")
            }
        },
        object : Tool("screen_tap", ToolGroup.Screen, Risk.write, true,
            "Tap an element (node id from screen_read, or visible text) or coordinates. Returns the screen afterwards.", {
                int("node", "Node id from the latest screen_read")
                string("text", "Visible text/description of the element to tap")
                int("x", "X coordinate"); int("y", "Y coordinate")
                string("kind", "Tap kind", enum = listOf("tap", "long"))
                int("displayId", "Virtual screen id")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val d = display(args)
                val long = args.str("kind") == "long"
                val target = node(args) ?: args.str("text")?.let { findByText(it, d) ?: throw InvalidArgsException("No element with text '$it'") }
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
            "Type text into an editable field (node id, or the focused field). Replaces the field's content unless append=true.", {
                string("text", "Text to enter", required = true)
                int("node", "Editable node id from screen_read (default: focused field)")
                int("displayId", "Virtual screen id")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val d = display(args)
                val target = node(args) ?: svc().findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: throw InvalidArgsException("No focused input field; tap one first or pass node")
                val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, args.reqStr("text"))
                })
                return readResult(d, if (ok) "Entered text" else "Field rejected text input")
            }
        },
        object : Tool("screen_swipe", ToolGroup.Screen, Risk.write, true, "Swipe/drag from (x1,y1) to (x2,y2).", {
            int("x1", "", required = true); int("y1", "", required = true); int("x2", "", required = true); int("y2", "", required = true)
            int("durationMs", "Default 300"); int("displayId", "Virtual screen id")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val d = display(args)
                fun i(k: String) = args.int(k) ?: throw InvalidArgsException("missing $k")
                svc().swipe(i("x1").toFloat(), i("y1").toFloat(), i("x2").toFloat(), i("y2").toFloat(), (args.int("durationMs") ?: 300).toLong(), d)
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
                    val m = svc().resources.displayMetrics
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
        object : Tool("screen_key", ToolGroup.Screen, Risk.write, true, "Press a system key: back, home, recents, or open the notification shade.", {
            string("key", "Key", required = true, enum = listOf("back", "home", "recents", "notifications"))
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val action = when (args.reqStr("key")) {
                    "back" -> GLOBAL_ACTION_BACK; "home" -> GLOBAL_ACTION_HOME
                    "recents" -> GLOBAL_ACTION_RECENTS; else -> GLOBAL_ACTION_NOTIFICATIONS
                }
                svc().performGlobalAction(action)
                return readResult(Display.DEFAULT_DISPLAY, "Pressed ${args.reqStr("key")}")
            }
        },
    )
}
