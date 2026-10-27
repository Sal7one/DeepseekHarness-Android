package io.github.jackwener.dsh.core.capability.tools

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.InvalidArgsException
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.bool
import io.github.jackwener.dsh.core.capability.int
import io.github.jackwener.dsh.core.capability.reqStr
import io.github.jackwener.dsh.core.capability.str
import io.github.jackwener.dsh.core.capability.strs
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.core.platform.ShizukuShell
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizuku: ShizukuShell,
) : ToolSet {
    private val pm get() = context.packageManager

    private fun label(ai: ApplicationInfo) = pm.getApplicationLabel(ai).toString()

    /** Starts an activity from the app process; falls back to Shizuku `am start` when Android blocks background starts. */
    private suspend fun start(intent: Intent, displayId: Int? = null): String {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (displayId == null) try {
            context.startActivity(intent); return "ok"
        } catch (e: ActivityNotFoundException) {
            throw InvalidArgsException("No app can handle this: ${e.message}")
        } catch (e: SecurityException) {
            if (!shizuku.available) throw e
        }
        val uri = intent.toUri(Intent.URI_INTENT_SCHEME)
        val r = shizuku.exec(buildString {
            append("am start")
            if (displayId != null) append(" --display $displayId")
            append(" '${uri.replace("'", "'\\''")}'")
        })
        return if (r.code == 0) "ok (via Shizuku)" else throw IllegalStateException(r.output)
    }

    override val tools = listOf(
        object : Tool("app_list", ToolGroup.Apps, Risk.read, false, "List installed user-launchable apps (label + package). Filter with query.", {
            string("query", "Substring of app name or package")
            bool("includeSystem", "Include system apps")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val q = args.str("query")?.lowercase()
                val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
                val list = launchable.filter { args.bool("includeSystem") == true || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || q != null }
                    .map { label(it) to it.packageName }
                    .filter { q == null || it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
                    .sortedBy { it.first.lowercase() }
                return ToolResult.text(list.joinToString("\n") { "${it.first}  (${it.second})" }.ifEmpty { "No matching apps." })
            }
        },
        object : Tool("app_info", ToolGroup.Apps, Risk.read, false, "Details of an installed app: version, install/update time, size, exported deep links.", {
            string("package", "Package name", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val p = args.reqStr("package")
                val info = try { pm.getPackageInfo(p, PackageManager.GET_ACTIVITIES) } catch (e: PackageManager.NameNotFoundException) {
                    throw InvalidArgsException("Not installed: $p") }
                val ai = info.applicationInfo!!
                return ToolResult.text(buildString {
                    appendLine("${label(ai)} ($p) ${info.versionName}")
                    appendLine("installed ${java.util.Date(info.firstInstallTime)}, updated ${java.util.Date(info.lastUpdateTime)}")
                    appendLine("apk ${File(ai.sourceDir).length() / 1024 / 1024} MB, system=${(ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0}")
                    append("activities: ${info.activities?.filter { it.exported }?.take(30)?.joinToString { it.name.removePrefix(p) } ?: "-"}")
                })
            }
        },
        object : Tool("app_launch", ToolGroup.Apps, Risk.write, true, "Open an app by package name (optionally a specific exported activity, or on the agent's virtual screen).", {
            string("package", "Package name", required = true)
            string("activity", "Fully-qualified exported activity (optional)")
            int("displayId", "Virtual screen id from vscreen_start (optional)")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val p = args.reqStr("package")
                val intent = args.str("activity")?.let { Intent().setComponent(ComponentName(p, it)) }
                    ?: pm.getLaunchIntentForPackage(p) ?: throw InvalidArgsException("No launchable activity for $p")
                return ToolResult.text("Launched $p: " + start(intent, args.int("displayId")))
            }
        },
        object : Tool("app_open_link", ToolGroup.Apps, Risk.write, true,
            "Open a URI/deep link (https:, tel:, geo:, mailto:, app schemes like weixin://, alipays://, etc.).", {
                string("uri", "The URI", required = true)
                string("package", "Restrict to this app (optional)")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(args.reqStr("uri")))
                args.str("package")?.let { intent.setPackage(it) }
                return ToolResult.text("Opened: " + start(intent))
            }
        },
        object : Tool("app_send_intent", ToolGroup.Apps, Risk.write, true,
            "Start an activity with an explicit Android intent (action/data/type/package/extras as string map).", {
                string("action", "e.g. android.settings.BLUETOOTH_SETTINGS", required = true)
                string("data", "Data URI")
                string("type", "MIME type")
                string("package", "Target package")
                obj("extras", "String extras {key: value}")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val intent = Intent(args.reqStr("action"))
                val data = args.str("data")?.let(Uri::parse)
                val type = args.str("type")
                if (data != null && type != null) intent.setDataAndType(data, type) else { data?.let(intent::setData); type?.let(intent::setType) }
                args.str("package")?.let(intent::setPackage)
                (args["extras"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { intent.putExtra(k, it) } }
                return ToolResult.text("Sent: " + start(intent))
            }
        },
        object : Tool("share_to_app", ToolGroup.Apps, Risk.write, true, "Share files and/or text to another app (opens the share target).", {
            strings("paths", "Files to share")
            string("text", "Text to share")
            string("package", "Target app package (optional: otherwise the system chooser opens)")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val root = Environment.getExternalStorageDirectory()
                val uris = ArrayList(args.strs("paths").map { p ->
                    FileProvider.getUriForFile(context, "${context.packageName}.files", if (p.startsWith("/")) File(p) else File(root, p))
                })
                val intent = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
                    type = if (uris.isEmpty()) "text/plain" else "*/*"
                    args.str("text")?.let { putExtra(Intent.EXTRA_TEXT, it) }
                    if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else if (uris.size > 1) putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val target = args.str("package")?.let { intent.setPackage(it) } ?: Intent.createChooser(intent, null)
                return ToolResult.text("Share sheet opened: " + start(target))
            }
        },
    )
}
