package dev.phonebridge.core.mcp

import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class McpInternalsTest {
    private fun classify(json: String) = Incoming.classify(Json.parseToJsonElement(json))

    @Test
    fun classifiesMessages() {
        val req = assertIs<Incoming.Request>(classify("""{"jsonrpc":"2.0","id":"a","method":"ping","params":{}}"""))
        assertEquals(JsonPrimitive("a"), req.id)
        assertEquals("ping", req.method)
        assertEquals(JsonObject(emptyMap()), req.params)
        assertEquals(JsonPrimitive(3.5), assertIs<Incoming.Request>(classify("""{"jsonrpc":"2.0","id":3.5,"method":"x"}""")).id)
        assertIs<Incoming.Notification>(classify("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertIs<Incoming.ClientResponse>(classify("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
        assertIs<Incoming.ClientResponse>(classify("""{"id":1,"error":{}}"""))
        assertEquals(JsonPrimitive(1), assertIs<Incoming.Invalid>(classify("""{"jsonrpc":"2.0","id":1}""")).id)
        assertEquals(JsonNull, assertIs<Incoming.Invalid>(classify("""{"jsonrpc":"2.0","id":[1],"method":"x"}""")).id)
        assertEquals(JsonPrimitive(2), assertIs<Incoming.Invalid>(classify("""{"jsonrpc":2.0,"id":2,"method":"x"}""")).id)
        assertIs<Incoming.Invalid>(classify("[]"))
        assertIs<Incoming.Invalid>(classify("null"))
    }

    @Test
    fun errorBodiesAreJsonRpc() {
        assertEquals("""{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"Unauthorized"}}""", JsonRpc.errorBody(-32001, "Unauthorized"))
    }

    @Test
    fun peekMethodForAuditOfUnauthenticatedRequests() {
        assertEquals("tools/call" to "sms_send", McpServer.peekMethod("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"sms_send"}}"""))
        assertEquals("ping,tools/list" to null, McpServer.peekMethod("""[{"method":"ping"},{"method":"tools/list"},{"method":"ping"}]"""))
        assertEquals("unknown" to null, McpServer.peekMethod("{bad"))
        assertEquals("unknown" to null, McpServer.peekMethod("""{"x":1}"""))
        assertEquals("unknown" to null, McpServer.peekMethod("[" + "1,".repeat(40_000) + "1]"))
    }

    @Test
    fun textHelpers() {
        assertEquals("abc", ToolText.truncate("abc", 3))
        assertEquals("ab…", ToolText.truncate("abcd", 3))
        // Never splits a surrogate pair.
        val emoji = "a😀bc"
        assertEquals("a…", ToolText.truncate(emoji, 3))
        assertEquals("60 seconds", ToolText.seconds(60_000))
        assertEquals("1 second", ToolText.seconds(1_000))
        assertEquals("0.5 seconds", ToolText.seconds(500))
        assertEquals("60 s", ToolText.shortSeconds(60_000))
        assertEquals("0.2 s", ToolText.shortSeconds(200))
        assertEquals("IllegalStateException: x", ToolText.describe(IllegalStateException("x")))
        assertEquals("IllegalStateException", ToolText.describe(IllegalStateException()))
    }

    @Test
    fun confirmationSummaryFallbacks() {
        val blank = tool("t", "Title", "d", "", Risk.SENSITIVE, describe = { " " }) { _, _ -> ToolResult.text("") }
        assertEquals("Title", ToolText.confirmationSummary(blank, JsonObject(emptyMap())))
        assertEquals("""Title: {"a":1}""", ToolText.confirmationSummary(blank, JsonObject(mapOf("a" to JsonPrimitive(1)))))
        val good = tool("t", "Title", "d", "", Risk.SENSITIVE, describe = { "Call Bob" }) { _, _ -> ToolResult.text("") }
        assertEquals("Call Bob", ToolText.confirmationSummary(good, JsonObject(emptyMap())))
        val ok = tool("t", "Title", "d", "", Risk.READ) { _, _ -> ToolResult.text("") }
        assertNull(ToolText.unavailableReason(ok))
    }

    @Test
    fun deadlineExpiresAfterBudget() = runTest {
        val deadline = PausableDeadline(1_000)
        deadline.start(this)
        advanceTimeBy(999)
        runCurrent()
        assertFalse(deadline.isExpired)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(deadline.isExpired)
        deadline.stop()
    }

    @Test
    fun pausedDeadlineDoesNotExpire() = runTest {
        var fakeNanos = 0L
        val deadline = PausableDeadline(1_000) { fakeNanos }
        deadline.start(this)
        advanceTimeBy(400)
        fakeNanos += 400_000_000
        deadline.pause()
        deadline.pause() // nested
        advanceTimeBy(100_000)
        runCurrent()
        assertFalse(deadline.isExpired)
        deadline.resume()
        advanceTimeBy(100_000)
        runCurrent()
        assertFalse(deadline.isExpired, "still paused once")
        deadline.resume()
        advanceTimeBy(599)
        runCurrent()
        assertFalse(deadline.isExpired, "600 ms remained")
        advanceTimeBy(2)
        runCurrent()
        assertTrue(deadline.isExpired)
        deadline.resume() // unbalanced extra resume is ignored
        deadline.stop()
    }

    @Test
    fun stoppedDeadlineNeverExpires() = runTest {
        val deadline = PausableDeadline(100)
        deadline.start(this)
        deadline.stop()
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(deadline.isExpired)
    }
}
