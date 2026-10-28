package io.github.jackwener.dsh.core.capability.tools

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.InvalidArgsException
import io.github.jackwener.dsh.core.capability.ShellUse
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.int
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.platform.ShizukuShell
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Experimental: a private virtual screen (created as the shell user through Shizuku) where the agent
 * operates apps without taking over the user's screen. Screen/app tools accept its displayId.
 */
@Singleton
class VirtualScreenTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuShell,
) : ToolSet {
    @Volatile var activeDisplay: Int? = null
        private set

    suspend fun capture(displayId: Int): ToolResult {
        if (context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId) == null) {
            if (activeDisplay == displayId) activeDisplay = null
            throw InvalidArgsException("Virtual screen $displayId no longer exists (Shizuku restarted?); call vscreen_start again")
        }
        val jpeg = shizuku.service().captureDisplay(displayId) ?: throw IllegalStateException("No frame yet; wait and retry")
        return ToolResult.image("image/jpeg", Base64.encodeToString(jpeg, Base64.NO_WRAP), "virtual screen $displayId (screen pixels)")
    }

    override val tools = listOf(
        object : Tool("vscreen_start", ToolGroup.VScreen, Risk.write, false,
            "Create the agent's own invisible screen (needs Shizuku). Returns displayId; pass it to app_launch/screen_* tools so work happens off the user's screen. " +
                "screen_key there supports back/home/enter/delete only (through Shizuku).", {}, ShellUse.Required) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                // The id goes stale when the Shizuku service died (Shizuku restart, reboot): only reuse a live display.
                activeDisplay?.let { id ->
                    if (context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(id) != null)
                        return ToolResult.text("Virtual screen already running: displayId=$id")
                    activeDisplay = null
                }
                val m = context.resources.displayMetrics
                val id = shizuku.service().createDisplay(m.widthPixels, m.heightPixels, m.densityDpi)
                if (id < 0) throw IllegalStateException("Could not create a virtual display on this device")
                activeDisplay = id
                return ToolResult.text("Virtual screen ready: displayId=$id (${m.widthPixels}x${m.heightPixels})")
            }
        },
        object : Tool("vscreen_stop", ToolGroup.VScreen, Risk.write, false, "Close the agent's virtual screen (apps on it are moved/closed by the system).", {
            int("displayId", "Display id (default: the active one)")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val id = args.int("displayId") ?: activeDisplay ?: throw InvalidArgsException("No virtual screen")
                shizuku.service().releaseDisplay(id)
                if (activeDisplay == id) activeDisplay = null
                return ToolResult.text("Closed virtual screen $id")
            }
        },
    )
}
