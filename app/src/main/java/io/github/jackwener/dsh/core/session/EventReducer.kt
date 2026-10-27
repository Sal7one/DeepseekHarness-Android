package io.github.jackwener.dsh.core.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Folds dsh session events (packages/core/session types.ts) into chat items. Pure. */
object EventReducer {
    fun reduce(state: ConversationState, type: String, seq: Long, data: JsonObject): ConversationState = when (type) {
        "user/message" -> {
            val source = data["source"]?.jsonObject?.get("kind")?.jsonPrimitive?.contentOrNull
            if (source != "user") state else state.copy(items = state.items + ChatItem.User("u$seq", textOf(data["content"])))
        }
        "assistant/message" -> {
            val msg = data["message"]?.jsonObject
            val text = textOf(msg?.get("content"))
            val reasoning = reasoningOf(msg?.get("content"))
            if (text.isBlank() && reasoning.isNullOrBlank()) state
            else state.copy(items = state.items + ChatItem.Assistant("a$seq", text, reasoning))
        }
        "tool/call" -> {
            val callId = data.str("callId") ?: "c$seq"
            state.copy(items = state.items + ChatItem.Tool("t$callId", callId, data.str("name") ?: "?", data.str("arguments") ?: "", ToolStatus.Running))
        }
        "tool/result" -> {
            val msg = data["message"]?.jsonObject
            val callId = msg?.str("toolCallId")
            val isError = msg?.get("isError")?.jsonPrimitive?.booleanOrNull == true || data["error"] != null
            val rejected = data["error"]?.jsonObject?.str("code")?.contains("REJECT", ignoreCase = true) == true
            val image = imageOf(msg?.get("content"))
            state.copy(items = state.items.map {
                if (it is ChatItem.Tool && it.callId == callId) it.copy(
                    status = when { rejected -> ToolStatus.Rejected; isError -> ToolStatus.Failed; else -> ToolStatus.Done },
                    result = textOf(msg?.get("content")).ifBlank { data["error"]?.jsonObject?.str("reason") },
                    imageBase64 = image,
                ) else it
            })
        }
        "turn/end" -> {
            val reason = data["reason"]?.jsonObject
            when (reason?.str("kind")) {
                "error" -> state.copy(items = state.items + ChatItem.Notice("e$seq",
                    reason["error"]?.jsonObject?.str("message") ?: "error", isError = true))
                "aborted" -> state.copy(items = state.items + ChatItem.Notice("e$seq", "stopped", isError = false))
                else -> state
            }.let { s -> s.copy(items = s.items.map { if (it is ChatItem.Tool && it.status == ToolStatus.Running) it.copy(status = ToolStatus.Failed) else it }) }
        }
        "session/title" -> state.copy(title = data.str("title") ?: state.title)
        else -> state
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    fun textOf(content: Any?): String = (content as? JsonArray)?.mapNotNull {
        val o = it.jsonObject
        if (o.str("type") == "text") o.str("text") else null
    }?.joinToString("\n")?.trim() ?: ""

    private fun reasoningOf(content: Any?): String? = (content as? JsonArray)?.mapNotNull {
        val o = it.jsonObject
        if (o.str("type") == "reasoning") o.str("text") ?: o.str("reasoning") else null
    }?.joinToString("\n")?.takeIf { it.isNotBlank() }

    private fun imageOf(content: Any?): String? = (content as? JsonArray)?.firstNotNullOfOrNull {
        val o = it.jsonObject
        if (o.str("type") == "image") o.str("data") else null
    }
}
