package io.github.jackwener.dsh.core.session

import io.github.jackwener.dsh.core.engine.RpcJson
import io.github.jackwener.dsh.core.model.Risk
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectorTest {
    private fun ev(seq: Long, type: String, json: String, time: Long = seq * 1000) =
        LoggedEvent(seq, type, time, RpcJson.parseToJsonElement(json) as JsonObject)

    private fun result(seq: Long, text: String, replace: Boolean = false) = ev(seq, "tool/result",
        """{"message":{"toolCallId":"c1","content":[{"type":"text","text":"$text"}]}${if (replace) ""","$SURFACE_OP_KEY":{"op":"replace"}""" else ""}}""")

    @Test fun `pruned replacement does not overwrite the tool result`() {
        val events = listOf(
            ev(0, "turn/start", """{"turn":1}"""),
            ev(1, "tool/call", """{"callId":"c1","name":"file_read","arguments":"{}"}"""),
            result(2, "full"),
            ev(3, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""),
            result(900, "[pruned]", replace = true),
        )
        val tool = Projector.conversation(events, running = false).tool("c1")!!
        assertEquals("full", tool.result)
        assertEquals(2000L, tool.endTime)
        val rec = Projector.trajectory(events, emptyList(), running = false).records.first { it.callId == "c1" }
        assertEquals("full", rec.output)
    }

    @Test fun `context counts cached input and todos survive a new turn`() {
        val events = listOf(
            ev(0, "turn/start", """{"turn":1}"""),
            ev(1, "todo/write", """{"todos":[{"content":"a","status":"pending"}]}"""),
            ev(2, "assistant/message", """{"message":{"content":[{"type":"text","text":"hi"}]},"usage":{"inputTokens":100,"cacheReadTokens":9000,"cacheWriteTokens":50,"outputTokens":7}}"""),
            ev(3, "turn/end", """{"turn":1,"reason":{"kind":"completed"}}"""),
            ev(4, "turn/start", """{"turn":2}"""),
        )
        val conv = Projector.conversation(events, running = true)
        assertEquals(9150L, conv.contextTokens)
        assertEquals(9150L, conv.turns.first().usage.input)
        assertEquals(1, conv.todos?.size)
    }

    @Test fun `app prompt is not shown as a user message`() {
        val events = listOf(
            ev(0, "turn/start", """{"turn":1}"""),
            ev(1, "user/message", """{"source":{"kind":"user"},"content":[{"type":"text","text":"${APP_PROMPT_PREFIX}continue"}]}"""),
        )
        assertNull(Projector.conversation(events, running = true).turns.single().user)
    }

    @Test fun `pending requests queue and the next becomes visible`() {
        val a = PendingApproval("a", "s", "t1", null, null, null, Risk.read, true)
        val b = PendingApproval("b", "s", "t2", null, null, null, Risk.read, true)
        var live = SessionLive("s").withPending(a).withPending(b)
        assertEquals("a", live.approval?.requestKey)
        live = live.withoutPending("b")
        assertEquals("a", live.approval?.requestKey)
        live = live.withoutPending("a")
        assertNull(live.approval)
        assertTrue(!live.hasPendingRequest)
    }

    @Test fun `idle hands the queue back to the composer`() {
        val live = SessionLive("s", running = true, queue = listOf(QueuedMessage("1", "one"), QueuedMessage("2", "two"))).idle()
        assertTrue(live.queue.isEmpty() && !live.running)
        assertNotNull(live.restoredDraft)
        assertEquals("one\n\ntwo", live.restoredDraft)
        assertNull(SessionLive("s", running = true).idle().restoredDraft)
    }
}
