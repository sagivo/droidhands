package com.droidhands.core.transport

import com.droidhands.core.auth.DeviceIdentity
import com.droidhands.core.mcp.TransportRequest
import com.droidhands.core.mcp.TransportResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayClientTest {
    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identity = DeviceIdentity(ByteArray(32) { it.toByte() })
    private val clients = mutableListOf<RelayClient>()
    private val handled = CopyOnWriteArrayList<TransportRequest>()

    /** Server end of one WebSocket connection. */
    private class Peer : WebSocketListener() {
        val opened = CompletableFuture<WebSocket>()
        val messages = LinkedBlockingQueue<String>()
        val closedCode = CompletableFuture<Int>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages += text
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            closedCode.complete(code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closedCode.complete(-1)
        }

        fun ws(): WebSocket = opened.get(10, TimeUnit.SECONDS)

        fun next(): JsonObject {
            val text = assertNotNull(messages.poll(10, TimeUnit.SECONDS), "expected a frame from the device")
            return Json.parseToJsonElement(text).jsonObject
        }

        fun nextOfType(type: String): JsonObject {
            while (true) {
                val frame = next()
                if (frame["type"]?.jsonPrimitive?.content == type) return frame
            }
        }

        fun welcome(mcpUrl: String = "https://relay.example.com/mcp") =
            ws().send("""{"type":"welcome","deviceId":"x","mcpUrl":"$mcpUrl","serverTime":1}""")
    }

    private fun peer(): Peer = Peer().also { server.enqueue(MockResponse().withWebSocketUpgrade(it)) }

    private fun client(
        baseUrl: String = server.url("/relay/").toString(),
        hello: DeviceHello = DeviceHello("android", "0.1.0", "Pixel 8", accessKey = "k3y"),
        handler: suspend (TransportRequest) -> TransportResponse = { req ->
            handled += req
            TransportResponse(200, """{"jsonrpc":"2.0","id":1,"result":{}}""", mapOf("content-type" to "application/json"))
        },
        heartbeatIntervalMs: Long = RelayProtocol.HEARTBEAT_INTERVAL_MS,
    ) = RelayClient(baseUrl, identity, hello, handler, scope, heartbeatIntervalMs = heartbeatIntervalMs).also { clients += it }

    private fun RelayClient.await(predicate: (RelayStatus) -> Boolean): RelayStatus = runBlocking {
        withTimeout(10_000) { status.first(predicate) }
    }

    @After
    fun tearDown() {
        clients.forEach { it.stop() }
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun sendsHelloThenConnectsOnWelcome() {
        val peer = peer()
        val c = client()
        c.start()
        assertEquals("/relay/v1/device?deviceId=${identity.deviceId}", server.takeRequest(10, TimeUnit.SECONDS)!!.path)
        val hello = peer.next()
        assertEquals("hello", hello["type"]!!.jsonPrimitive.content)
        assertEquals(1, hello["protocolVersion"]!!.jsonPrimitive.int)
        assertEquals(identity.deviceId, hello["deviceId"]!!.jsonPrimitive.content)
        assertEquals(identity.secretEncoded, hello["deviceSecret"]!!.jsonPrimitive.content)
        assertEquals("android", hello["platform"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", hello["appVersion"]!!.jsonPrimitive.content)
        assertEquals("Pixel 8", hello["deviceName"]!!.jsonPrimitive.content)
        assertEquals("k3y", hello["accessKey"]!!.jsonPrimitive.content)
        assertEquals(RelayStatus.Connecting, c.status.value)

        peer.welcome("https://relay.example.com/mcp")
        assertEquals(RelayStatus.Connected("https://relay.example.com/mcp"), c.await { it is RelayStatus.Connected })
    }

    @Test
    fun helloOmitsBlankAccessKey() {
        val peer = peer()
        client(hello = DeviceHello("android", "1", "Phone", accessKey = " ")).start()
        assertNull(peer.next()["accessKey"])
    }

    @Test
    fun tunneledRequestRoundTrip() {
        val peer = peer()
        val c = client()
        c.start()
        peer.next()
        peer.welcome()
        c.await { it is RelayStatus.Connected }

        peer.ws().send(
            """{"type":"request","id":"r_1","method":"POST","path":"/mcp","headers":{"authorization":"Bearer pb1.t",""" +
                """"Mcp-Protocol-Version":"2025-06-18","user-agent":"claude-code/2.0","content-type":"application/json"},""" +
                """"body":"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}","remoteAddress":"203.0.113.7"}""",
        )
        val response = peer.nextOfType("response")
        assertEquals("r_1", response["id"]!!.jsonPrimitive.content)
        assertEquals(200, response["status"]!!.jsonPrimitive.int)
        assertEquals("application/json", response["headers"]!!.jsonObject["content-type"]!!.jsonPrimitive.content)
        assertEquals("""{"jsonrpc":"2.0","id":1,"result":{}}""", response["body"]!!.jsonPrimitive.content)

        val req = handled.single()
        assertEquals("Bearer pb1.t", req.authorization)
        assertEquals("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", req.body)
        assertEquals("relay", req.transport)
        assertEquals("2025-06-18", req.protocolVersionHeader)
        assertEquals("claude-code/2.0", req.userAgent)
        assertEquals("203.0.113.7", req.remoteAddress)
    }

    @Test
    fun requestsAreHandledConcurrentlyAndErrorsBecome500() {
        val peer = peer()
        val c = client(handler = { req ->
            when (req.body) {
                "slow" -> {
                    delay(1_000)
                    TransportResponse(200, "slow-done", mapOf("content-type" to "application/json"))
                }
                "boom" -> throw IllegalStateException("boom")
                else -> TransportResponse(202, null)
            }
        })
        c.start()
        peer.next()
        peer.welcome()
        val ws = peer.ws()
        ws.send("""{"type":"request","id":"a","method":"POST","path":"/mcp","headers":{},"body":"slow"}""")
        ws.send("""{"type":"request","id":"b","method":"POST","path":"/mcp","headers":{},"body":"fast"}""")
        ws.send("""{"type":"request","id":"c","method":"POST","path":"/mcp","headers":{},"body":"boom"}""")
        val responses = (1..3).map { peer.nextOfType("response") }
        assertEquals("a", responses.last()["id"]!!.jsonPrimitive.content, "slow response arrives last")
        val byId = responses.associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals(202, byId["b"]!!["status"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, byId["b"]!!["body"])
        assertEquals(500, byId["c"]!!["status"]!!.jsonPrimitive.int)
        assertTrue(byId["c"]!!["body"]!!.jsonPrimitive.content.contains("-32603"))
    }

    @Test
    fun nonPostRequestsGet405() {
        val peer = peer()
        client().start()
        peer.next()
        peer.ws().send("""{"type":"request","id":"g","method":"GET","path":"/mcp","headers":{}}""")
        val response = peer.nextOfType("response")
        assertEquals(405, response["status"]!!.jsonPrimitive.int)
        assertTrue(handled.isEmpty())
    }

    @Test
    fun answersPingsAndIgnoresUnknownFrames() {
        val peer = peer()
        val c = client()
        c.start()
        peer.next()
        peer.welcome()
        c.await { it is RelayStatus.Connected }
        val ws = peer.ws()
        ws.send("""{"type":"future_thing","x":1}""")
        ws.send("not json at all")
        ws.send("""{"type":"request"}""") // no id: ignored
        ws.send("""{"type":"ping","t":1731500000123}""")
        val pong = peer.next()
        assertEquals("pong", pong["type"]!!.jsonPrimitive.content)
        assertEquals(1731500000123, pong["t"]!!.jsonPrimitive.long)
        assertIs<RelayStatus.Connected>(c.status.value)
    }

    @Test
    fun sendsHeartbeatsOnlyAfterTheWelcome() {
        val peer = peer()
        val c = client(heartbeatIntervalMs = 100)
        c.start()
        assertEquals("hello", peer.next()["type"]!!.jsonPrimitive.content)
        Thread.sleep(350)
        assertNull(peer.messages.poll(), "no heartbeat before the welcome (a relay would take it for the hello)")

        peer.welcome()
        c.await { it is RelayStatus.Connected }
        repeat(3) { assertEquals(RelayProtocol.HEARTBEAT_FRAME, assertNotNull(peer.messages.poll(5, TimeUnit.SECONDS))) }

        // hb_ack is optional and ignored; heartbeats go on either way.
        peer.ws().send("""{"type":"hb_ack"}""")
        assertEquals(RelayProtocol.HEARTBEAT_FRAME, assertNotNull(peer.messages.poll(5, TimeUnit.SECONDS)))
        assertIs<RelayStatus.Connected>(c.status.value)

        c.stop()
        Thread.sleep(300)
        peer.messages.clear()
        Thread.sleep(300)
        assertNull(peer.messages.poll(), "no heartbeat after stop()")
    }

    @Test
    fun relayWithoutHbAckStaysConnected() {
        val peer = peer()
        val c = client(heartbeatIntervalMs = 50)
        c.start()
        peer.next()
        peer.welcome()
        c.await { it is RelayStatus.Connected }
        Thread.sleep(500) // ~10 heartbeats, none answered (an older relay ignores unknown frames)
        assertIs<RelayStatus.Connected>(c.status.value)
        assertEquals(1, server.requestCount, "no reconnect")
    }

    @Test
    fun fatalCloseStopsRetrying() {
        val peer = peer()
        peer() // a second upgrade that must never be used
        val c = client()
        c.start()
        peer.next()
        peer.ws().send("""{"type":"error","code":"access_key","message":"Invalid access key"}""")
        peer.ws().close(4001, "unauthorized")
        val status = assertIs<RelayStatus.Error>(c.await { it is RelayStatus.Error })
        assertTrue(status.fatal)
        assertNull(status.retryInMs)
        assertTrue(status.message.contains("Invalid access key"), status.message)
        Thread.sleep(2_500)
        assertEquals(1, server.requestCount, "no reconnect after 4001")
        assertEquals(status, c.status.value)
    }

    @Test
    fun protocolVersionCloseIsFatal() {
        val peer = peer()
        val c = client()
        c.start()
        peer.next()
        peer.ws().close(4002, "unsupported protocol")
        val status = assertIs<RelayStatus.Error>(c.await { it is RelayStatus.Error })
        assertTrue(status.fatal)
        assertTrue(status.message.contains("protocol"), status.message)

        // start() again (e.g. after a settings change) reconnects.
        val again = peer()
        c.start()
        assertEquals("hello", again.next()["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun reconnectsAfterServerCloseWithBackoff() {
        val first = peer()
        val second = peer()
        val c = client()
        c.start()
        first.next()
        first.welcome()
        c.await { it is RelayStatus.Connected }

        first.ws().close(1001, "going away")
        val error = assertIs<RelayStatus.Error>(c.await { it is RelayStatus.Error })
        assertTrue(error.retryInMs!! in 1_000..60_000, "retry ${error.retryInMs}")
        assertTrue(!error.fatal)

        assertEquals("hello", second.next()["type"]!!.jsonPrimitive.content)
        second.welcome("https://relay.example.com/mcp2")
        assertEquals(RelayStatus.Connected("https://relay.example.com/mcp2"), c.await { it is RelayStatus.Connected })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun replacedConnectionCodeIsRetried() {
        val first = peer()
        val second = peer()
        val c = client()
        c.start()
        first.next()
        first.ws().close(4009, "replaced")
        assertIs<RelayStatus.Error>(c.await { it is RelayStatus.Error && !it.fatal })
        assertEquals("hello", second.next()["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun stopClosesNormallyAndStartAgainWorks() {
        val first = peer()
        val c = client()
        c.start()
        first.next()
        first.welcome()
        c.await { it is RelayStatus.Connected }

        c.start() // no-op while running
        c.stop()
        assertEquals(RelayStatus.Stopped, c.status.value)
        assertEquals(1000, first.closedCode.get(10, TimeUnit.SECONDS))
        c.stop() // idempotent

        val second = peer()
        c.start()
        assertEquals("hello", second.next()["type"]!!.jsonPrimitive.content)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun unreachableRelayKeepsRetrying() {
        val deadPort = MockWebServer().run {
            start()
            val p = port
            shutdown()
            p
        }
        val c = client(baseUrl = "http://127.0.0.1:$deadPort")
        c.start()
        val error = assertIs<RelayStatus.Error>(c.await { it is RelayStatus.Error })
        assertTrue(!error.fatal)
        assertNotNull(error.retryInMs)
        assertTrue(error.message.contains("Could not reach the relay"), error.message)
    }

    @Test
    fun invalidUrlIsFatal() {
        val c = client(baseUrl = "ftp://nope")
        c.start()
        val status = assertIs<RelayStatus.Error>(c.status.value)
        assertTrue(status.fatal)
        assertNull(status.retryInMs)
    }
}
