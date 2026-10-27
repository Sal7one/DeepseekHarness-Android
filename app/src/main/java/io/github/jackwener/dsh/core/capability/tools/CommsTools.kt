package io.github.jackwener.dsh.core.capability.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.InvalidArgsException
import io.github.jackwener.dsh.core.capability.PermissionMissingException
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.int
import io.github.jackwener.dsh.core.capability.long
import io.github.jackwener.dsh.core.capability.reqStr
import io.github.jackwener.dsh.core.capability.str
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommsTools @Inject constructor(@ApplicationContext private val context: Context) : ToolSet {
    private val cr get() = context.contentResolver
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun need(permission: String, label: String) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw PermissionMissingException(label)
    }

    override val tools = listOf(
        object : Tool("contacts_search", ToolGroup.Comms, Risk.sensitive, false, "Search contacts by name or number.", {
            string("query", "Name or number fragment", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                need(Manifest.permission.READ_CONTACTS, "Contacts")
                val q = "%${args.reqStr("query")}%"
                val rows = mutableListOf<String>()
                cr.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?",
                    arrayOf(q, q), null)?.use { c -> while (c.moveToNext() && rows.size < 50) rows += "${c.getString(0)}: ${c.getString(1)}" }
                return ToolResult.text(rows.distinct().joinToString("\n").ifEmpty { "No contacts found." })
            }
        },
        object : Tool("sms_read", ToolGroup.Comms, Risk.sensitive, false, "Read recent SMS (inbox and sent), optionally filtered by sender/number or text.", {
            string("query", "Filter on number or body"); int("limit", "Default 20")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                need(Manifest.permission.READ_SMS, "SMS")
                val q = args.str("query")
                val rows = mutableListOf<String>()
                cr.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                    q?.let { "${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.BODY} LIKE ?" }, q?.let { arrayOf("%$it%", "%$it%") },
                    "${Telephony.Sms.DATE} DESC")?.use { c ->
                    val limit = args.int("limit") ?: 20
                    while (c.moveToNext() && rows.size < limit) {
                        val dir = if (c.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT) "→" else "←"
                        rows += "${fmt.format(Date(c.getLong(2)))} $dir ${c.getString(0)}: ${c.getString(1)}"
                    }
                }
                return ToolResult.text(rows.joinToString("\n").ifEmpty { "No messages." })
            }
        },
        object : Tool("call_log_read", ToolGroup.Comms, Risk.sensitive, false, "Recent calls.", { int("limit", "Default 20") }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                need(Manifest.permission.READ_CALL_LOG, "Call log")
                val rows = mutableListOf<String>()
                cr.query(CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                    null, null, "${CallLog.Calls.DATE} DESC")?.use { c ->
                    val limit = args.int("limit") ?: 20
                    while (c.moveToNext() && rows.size < limit) {
                        val type = when (c.getInt(3)) { CallLog.Calls.INCOMING_TYPE -> "in"; CallLog.Calls.OUTGOING_TYPE -> "out"; CallLog.Calls.MISSED_TYPE -> "missed"; else -> "other" }
                        rows += "${fmt.format(Date(c.getLong(2)))} $type ${c.getString(0) ?: ""} ${c.getString(1)} ${c.getLong(4)}s"
                    }
                }
                return ToolResult.text(rows.joinToString("\n").ifEmpty { "No calls." })
            }
        },
        object : Tool("calendar_read", ToolGroup.Comms, Risk.sensitive, false, "Calendar events in a time range (epoch ms; default next 7 days).", {
            int("fromMs", "Start (epoch ms)"); int("toMs", "End (epoch ms)")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                need(Manifest.permission.READ_CALENDAR, "Calendar")
                val from = args.long("fromMs") ?: System.currentTimeMillis()
                val to = args.long("toMs") ?: (from + 7 * 86_400_000L)
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(from.toString()).appendPath(to.toString()).build()
                val rows = mutableListOf<String>()
                cr.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION),
                    null, null, CalendarContract.Instances.BEGIN)?.use { c ->
                    while (c.moveToNext() && rows.size < 100) rows += "${fmt.format(Date(c.getLong(1)))}–${fmt.format(Date(c.getLong(2)))} ${c.getString(0)} ${c.getString(3) ?: ""}"
                }
                return ToolResult.text(rows.joinToString("\n").ifEmpty { "No events." })
            }
        },
        object : Tool("calendar_add", ToolGroup.Comms, Risk.write, false, "Add an event to the primary calendar.", {
            string("title", "", required = true); int("startMs", "Start epoch ms", required = true); int("endMs", "End epoch ms", required = true)
            string("location", ""); string("description", "")
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                need(Manifest.permission.WRITE_CALENDAR, "Calendar (write)")
                val calId = cr.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
                    "${CalendarContract.Calendars.IS_PRIMARY}=1", null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null }
                    ?: cr.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null }
                    ?: throw IllegalStateException("No calendar account on this phone")
                val uri = cr.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calId)
                    put(CalendarContract.Events.TITLE, args.reqStr("title"))
                    put(CalendarContract.Events.DTSTART, args.long("startMs") ?: throw InvalidArgsException("startMs"))
                    put(CalendarContract.Events.DTEND, args.long("endMs") ?: throw InvalidArgsException("endMs"))
                    put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    args.str("location")?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
                    args.str("description")?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                })
                return ToolResult.text("Event created: $uri")
            }
        },
        object : Tool("sms_send", ToolGroup.Comms, Risk.dangerous, false, "Send an SMS. Always confirm recipient and text with the user first.", {
            string("to", "Phone number", required = true); string("text", "Message", required = true)
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                need(Manifest.permission.SEND_SMS, "Send SMS")
                val sms = context.getSystemService(SmsManager::class.java)
                sms.sendMultipartTextMessage(args.reqStr("to"), null, sms.divideMessage(args.reqStr("text")), null, null)
                return ToolResult.text("SMS sent to ${args.reqStr("to")}")
            }
        },
    )
}
