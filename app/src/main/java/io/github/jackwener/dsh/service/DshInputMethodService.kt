package io.github.jackwener.dsh.service

import android.content.ComponentName
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import io.github.jackwener.dsh.R

/**
 * DSH 输入法: a keyboard with no keys (like ADBKeyboard / droidrun Portal) that lets the agent
 * commit any text — including CJK and emoji — into fields where AccessibilityNodeInfo
 * ACTION_SET_TEXT does not work (WebView, Flutter, games, custom editors). It runs in the app's main
 * process, so CapabilityHub calls [commit] directly; nothing is exported besides the system binding.
 * The agent switches to it only through Shizuku (`ime set`) and switches back afterwards; a user who
 * selects it manually gets a one-line bar with a button back to their keyboard.
 */
class DshInputMethodService : InputMethodService() {
    override fun onCreate() { super.onCreate(); instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onCreateInputView(): View = TextView(this).apply {
        text = getString(R.string.ime_bar)
        gravity = Gravity.CENTER
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        setOnClickListener {
            if (!switchToPreviousInputMethod()) getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) { super.onStartInput(attribute, restarting); editor = attribute }

    @Volatile private var editor: EditorInfo? = null

    /** True when an editor is connected and the text was committed. */
    fun commit(text: String, replace: Boolean): Boolean {
        val ic = currentInputConnection ?: return false
        if (editor == null) return false
        ic.beginBatchEdit()
        if (replace) { ic.performContextMenuAction(android.R.id.selectAll); ic.commitText("", 1) }
        val ok = ic.commitText(text, 1)
        ic.endBatchEdit()
        return ok
    }

    companion object {
        @Volatile var instance: DshInputMethodService? = null
            private set

        fun id(context: Context) = ComponentName(context, DshInputMethodService::class.java).flattenToShortString()

        fun isCurrent(context: Context) = currentId(context)?.let(ComponentName::unflattenFromString) ==
            ComponentName(context, DshInputMethodService::class.java)

        fun currentId(context: Context): String? = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    }
}
