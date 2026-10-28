package io.github.jackwener.dsh.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Risk { read, write, sensitive, dangerous }

enum class ToolGroup(val id: String) {
    Files("files"), Apps("apps"), Screen("screen"), System("system"), Notifications("notifications"),
    Comms("comms"), Media("media"), Clipboard("clipboard"), Browser("browser"), Rules("rules"), VScreen("vscreen");

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
}

/**
 * dsh permission presets (ui-permission-presets), mapped onto the app's approval gate:
 * read-only refuses every action that needs approval, ask (dsh "workspace-write", the default)
 * asks for each one, full access approves everything except `dangerous` device tools.
 */
enum class PermissionPreset(val id: String) {
    ReadOnly("read-only"), Ask("workspace-write"), Full("danger-full-access");
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: Ask }
}

const val AUTO_SESSION_PREFIX = "auto_"

fun ruleIdOfSession(sessionId: String): Long? =
    if (sessionId.startsWith(AUTO_SESSION_PREFIX)) sessionId.removePrefix(AUTO_SESSION_PREFIX).substringBefore('_').toLongOrNull() else null
