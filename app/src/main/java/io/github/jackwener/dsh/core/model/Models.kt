package io.github.jackwener.dsh.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Risk { read, write, sensitive, dangerous }

enum class ToolGroup(val id: String) {
    Files("files"), Apps("apps"), Screen("screen"), System("system"), Notifications("notifications"),
    Comms("comms"), Media("media"), Clipboard("clipboard"), Browser("browser"), Rules("rules"), VScreen("vscreen");

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
}

/** What the user chose during onboarding ("what should it do for you"). */
enum class UseCase(val groups: Set<ToolGroup>) {
    ManageFiles(setOf(ToolGroup.Files)),
    OperateApps(setOf(ToolGroup.Apps, ToolGroup.Screen, ToolGroup.Browser)),
    Automate(setOf(ToolGroup.Rules, ToolGroup.System, ToolGroup.Media)),
    ReadNotifications(setOf(ToolGroup.Notifications)),
}

const val AUTO_SESSION_PREFIX = "auto_"

fun ruleIdOfSession(sessionId: String): Long? =
    if (sessionId.startsWith(AUTO_SESSION_PREFIX)) sessionId.removePrefix(AUTO_SESSION_PREFIX).substringBefore('_').toLongOrNull() else null
