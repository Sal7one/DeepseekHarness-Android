package io.github.jackwener.dsh.core.capability.tools

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.PermissionMissingException
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.int
import io.github.jackwener.dsh.core.capability.str
import io.github.jackwener.dsh.core.capability.strs
import io.github.jackwener.dsh.core.data.SettingsRepository
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import io.github.jackwener.dsh.service.DshNotificationListener
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationTools @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : ToolSet {
    private fun listener() = DshNotificationListener.instance ?: throw PermissionMissingException("Notification access")

    override val tools = listOf(
        object : Tool("notification_list", ToolGroup.Notifications, Risk.sensitive, false,
            "List current notifications from apps the user allowed (Me → Notifications). Includes key for dismissing.", {
                string("package", "Only this app"); int("limit", "Default 30")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val allowed = settings.current().notificationApps
                if (allowed.isEmpty()) return ToolResult.text("The user has not allowed any app's notifications to be read. Ask them to choose apps in DSH → Me → Notifications.")
                val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.US)
                val list = listener().activeNotifications.filter { it.packageName in allowed && (args.str("package") == null || it.packageName == args.str("package")) }
                    .sortedByDescending { it.postTime }.take(args.int("limit") ?: 30)
                return ToolResult.text(list.joinToString("\n") {
                    val e = it.notification.extras
                    "${fmt.format(Date(it.postTime))} [${it.packageName}] ${e.getCharSequence("android.title") ?: ""}: " +
                        "${(e.getCharSequence("android.bigText") ?: e.getCharSequence("android.text") ?: "").toString().take(300)}  key=${it.key}"
                }.ifEmpty { "No notifications." })
            }
        },
        object : Tool("notification_dismiss", ToolGroup.Notifications, Risk.write, false, "Dismiss notifications by key (or all from allowed apps).", {
            strings("keys", "Keys from notification_list; empty = all allowed")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val keys = args.strs("keys")
                val allowed = settings.current().notificationApps
                val l = listener()
                val targets = if (keys.isEmpty()) l.activeNotifications.filter { it.packageName in allowed }.map { it.key } else keys
                l.cancelNotifications(targets.toTypedArray())
                return ToolResult.text("Dismissed ${targets.size}")
            }
        },
    )
}
