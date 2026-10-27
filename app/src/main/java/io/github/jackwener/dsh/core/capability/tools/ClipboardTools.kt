package io.github.jackwener.dsh.core.capability.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.reqStr
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.platform.ShizukuShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClipboardTools @Inject constructor(@ApplicationContext private val context: Context, private val shizuku: ShizukuShell) : ToolSet {
    private val cm get() = context.getSystemService(ClipboardManager::class.java)

    override val tools = listOf(
        object : Tool("clipboard_get", ToolGroup.Clipboard, Risk.sensitive, false,
            "Read the clipboard text. Android only allows this while DSH is in the foreground (or via Shizuku).", {}) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val text = withContext(Dispatchers.Main) { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }
                if (text != null) return ToolResult.text(text)
                if (shizuku.available) {
                    val r = shizuku.exec("cmd clipboard get-primary-clip 2>/dev/null || service call clipboard 2")
                    if (r.code == 0 && r.output.isNotBlank()) return ToolResult.text(r.output.trim())
                }
                return ToolResult.error("failed", "Clipboard is not readable right now (Android restricts background access).")
            }
        },
        object : Tool("clipboard_set", ToolGroup.Clipboard, Risk.write, false, "Put text on the clipboard.", {
            string("text", "", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                withContext(Dispatchers.Main) { cm.setPrimaryClip(ClipData.newPlainText("DSH", args.reqStr("text"))) }
                return ToolResult.text("Copied to clipboard")
            }
        },
    )
}
