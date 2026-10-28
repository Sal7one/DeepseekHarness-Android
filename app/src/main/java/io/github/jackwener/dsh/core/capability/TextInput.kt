package io.github.jackwener.dsh.core.capability

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.platform.ShizukuShell
import io.github.jackwener.dsh.service.DshAccessibilityService
import io.github.jackwener.dsh.service.DshInputMethodService
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Text entry with fallbacks (review §7/G7). ACTION_SET_TEXT is the cleanest path but fails, or
 * "succeeds" without effect, in WebView, Flutter, games and some custom editors, so each step is
 * verified by re-reading the node and the next path is tried:
 *  1. AccessibilityNodeInfo ACTION_SET_TEXT (verified);
 *  2. Android 13+: the accessibility service's own input connection (flagInputMethodEditor);
 *  3. DSH 输入法 ([DshInputMethodService]) when it is the current keyboard;
 *  4. Shizuku: `input text` for ASCII; otherwise switch to DSH 输入法 with `ime set`, commit, and
 *     switch back to the user's keyboard;
 *  5. clipboard + ACTION_PASTE on the field (any language; the clipboard keeps the text).
 */
@Singleton
class TextInput @Inject constructor(@ApplicationContext private val context: Context, private val shizuku: ShizukuShell) {

    /** Returns the method that worked, or null when every path failed. */
    suspend fun type(svc: DshAccessibilityService, target: AccessibilityNodeInfo?, text: String, append: Boolean): String? {
        val full = if (append) (target?.text?.toString().orEmpty() + text) else text
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, full)
            }) && verify(target, text)) return "set_text"

        target?.let { focus(it) }
        if (svc.commitViaA11yInput(text, replace = !append) && verify(target, text, trustWithoutNode = true)) return "a11y_input"
        if (DshInputMethodService.isCurrent(context) && DshInputMethodService.instance?.commit(text, replace = !append) == true &&
            verify(target, text, trustWithoutNode = true)) return "dsh_ime"

        if (shizuku.available) {
            if (text.all { it.code in 0x20..0x7e }) {
                if (!append && target != null) clear(target)
                if (shizuku.exec("input text ${shellQuote(text.replace(" ", "%s"))}").code == 0 && verify(target, text, trustWithoutNode = true)) return "shizuku_input"
            } else if (viaImeSwitch(text, !append) && verify(target, text, trustWithoutNode = true)) return "shizuku_ime"
        }

        if (target != null) {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("dsh", text))
            if (!append) clear(target)
            if (target.performAction(AccessibilityNodeInfo.ACTION_PASTE) && verify(target, text)) return "paste"
        }
        return null
    }

    private fun focus(n: AccessibilityNodeInfo) {
        if (!n.isFocused) { n.performAction(AccessibilityNodeInfo.ACTION_FOCUS); n.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
    }

    private fun clear(n: AccessibilityNodeInfo) {
        n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, n.text?.length ?: 0)
        })
    }

    /** Temporarily selects DSH 输入法 through Shizuku, commits, then restores the user's keyboard. */
    private suspend fun viaImeSwitch(text: String, replace: Boolean): Boolean {
        val ours = DshInputMethodService.id(context)
        val previous = DshInputMethodService.currentId(context)
        if (shizuku.exec("ime enable $ours && ime set $ours").code != 0) return false
        try {
            repeat(20) {
                DshInputMethodService.instance?.let { if (it.commit(text, replace)) return true }
                delay(100)
            }
            return false
        } finally {
            if (previous != null && previous != ours) shizuku.exec("ime set ${shellQuote(previous)}")
        }
    }

    /** The field shows the text now (password fields cannot be read back: trust the action). */
    private suspend fun verify(n: AccessibilityNodeInfo?, expected: String, trustWithoutNode: Boolean = false): Boolean {
        if (n == null) return trustWithoutNode
        if (n.isPassword) return true
        delay(150)
        n.refresh()
        val now = n.text?.toString() ?: return false
        return now.contains(expected) || now.replace(Regex("\\s"), "").contains(expected.replace(Regex("\\s"), ""))
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
