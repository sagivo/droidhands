package dev.phonebridge.core.transport

import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.mcp.TransportResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One-tap pairing frames between the relay and RelayClient (docs/PROTOCOL.md §5.3). */
class RelayPairingTest {
    private val server = MockWebServer().apply { start() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val identity = DeviceIdentity(ByteArray(32) { it.toByte() })
    private val clients = mutableListOf<RelayClient>()

    private class Peer : WebSocketListener() {
        val opened = CompletableFuture<WebSocket>()
        val messages = LinkedBlockingQueue<String>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messages += text
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        fun ws(): WebSocket = opened.get(10, TimeUnit.SECONDS)

        fun next(): JsonObject {
            val text = assertNotNull(messages.poll(10, TimeUnit.SECONDS), "expected a frame from the device")
            return Json.parseToJsonElement(text).jsonObject
        }

        /** The next frame that is not a pong. */
        fun nextFrame(): JsonObject {
            while (true) {
                val frame = next()
                if (frame.type() != "pong") return frame
            }
        }

        /** Proves that nothing else was sent before now: a ping's pong is the next frame. */
        fun assertNothingSent() {
            ws().send("""{"type":"ping","t":42}""")
            val frame = next()
            assertEquals("pong", frame.type(), "unexpected frame $frame")
        }
    }

    /** Records calls; [decide] answers prompts. */
    private class Handler(var decide: suspend (PairingRequest) -> PairingDecision) : PairingHandler {
        val requests = CopyOnWriteArrayList<PairingRequest>()
        val finished = LinkedBlockingQueue<Triple<String, String?, Boolean>>()
        val cancelled = CompletableDeferred<String>()

        override suspend fun onPairRequest(request: PairingRequest): PairingDecision {
            requests += request
            try {
                return decide(request)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled.complete(request.id)
                throw e
            }
        }

        override fun onPairFinished(id: String, tokenId: String?, completed: Boolean) {
            finished += Triple(id, tokenId, completed)
        }

        fun awaitCancelled(): String = runBlocking { withTimeout(10_000) { cancelled.await() } }

        fun nextFinished() = assertNotNull(finished.poll(10, TimeUnit.SECONDS), "expected onPairFinished")
    }

    private fun connect(handler: PairingHandler?, serverTime: Long = System.currentTimeMillis()): Pair<RelayClient, Peer> {
        val peer = Peer().also { server.enqueue(MockResponse().withWebSocketUpgrade(it)) }
        val client = RelayClient(
            relayBaseUrl = server.url("/").toString(),
            identity = identity,
            hello = DeviceHello("android", "0.1.0", "Pixel 8"),
            handler = { TransportResponse(202, null) },
            scope = scope,
            pairingHandler = handler,
        ).also { clients += it }
        client.start()
        assertEquals("hello", peer.next().type())
        peer.ws().send(
            """{"type":"welcome","deviceId":"${identity.deviceId}","mcpUrl":"https://relay.example.com/mcp",""" +
                """"connectUrl":"https://relay.example.com/d/${identity.deviceId}/mcp","serverTime":$serverTime}""",
        )
        runBlocking { withTimeout(10_000) { client.status.first { it is RelayStatus.Connected } } }
        return client to peer
    }

    private fun pairRequest(id: String = "p_1", expiresAt: Long = System.currentTimeMillis() + 180_000, clientName: String = "Claude") =
        """{"type":"pair_request","id":"$id","clientName":"$clientName","redirectHost":"claude.ai","displayCode":"K7Q2",""" +
            """"remoteAddress":"203.0.113.7","userAgent":"Mozilla/5.0","expiresAt":$expiresAt}"""

    @After
    fun tearDown() {
        clients.forEach { it.stop() }
        scope.cancel()
        server.shutdown()
    }

    @Test
    fun welcomeConnectUrlIsReported() {
        val (client, _) = connect(null)
        assertEquals(
            RelayStatus.Connected("https://relay.example.com/mcp", "https://relay.example.com/d/${identity.deviceId}/mcp"),
            client.status.value,
        )
    }

    @Test
    fun approvedPairingSendsTheToken() {
        val handler = Handler { PairingDecision.Approved("pb1.token", "abcdefghijkl") }
        val (_, peer) = connect(handler)
        val expiresAt = System.currentTimeMillis() + 180_000
        peer.ws().send(pairRequest(expiresAt = expiresAt))
        val response = peer.nextFrame()
        assertEquals("pair_response", response.type())
        assertEquals("p_1", response["id"]!!.jsonPrimitive.content)
        assertTrue(response["approved"]!!.jsonPrimitive.boolean)
        assertEquals("pb1.token", response["accessToken"]!!.jsonPrimitive.content)
        assertEquals("abcdefghijkl", response["tokenId"]!!.jsonPrimitive.content)

        val request = handler.requests.single()
        assertEquals(PairingRequest("p_1", "Claude", "claude.ai", "K7Q2", "203.0.113.7", "Mozilla/5.0", expiresAt), request)
    }

    @Test
    fun deniedPairing() {
        val (_, peer) = connect(Handler { PairingDecision.Denied("denied") })
        peer.ws().send(pairRequest())
        val response = peer.nextFrame()
        assertEquals("pair_response", response.type())
        assertFalse(response["approved"]!!.jsonPrimitive.boolean)
        assertEquals("denied", response["reason"]!!.jsonPrimitive.content)
        assertNull(response["accessToken"])
    }

    @Test
    fun withoutAHandlerTheAnswerIsBusy() {
        val (_, peer) = connect(null)
        peer.ws().send(pairRequest())
        val response = peer.nextFrame()
        assertFalse(response["approved"]!!.jsonPrimitive.boolean)
        assertEquals("busy", response["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun handlerFailureIsBusy() {
        val (_, peer) = connect(Handler { throw IllegalStateException("no UI") })
        peer.ws().send(pairRequest())
        assertEquals("busy", peer.nextFrame()["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun pairCancelRemovesThePromptAndSendsNothing() {
        val handler = Handler { awaitCancellation() }
        val (_, peer) = connect(handler)
        peer.ws().send(pairRequest(id = "p_cancel"))
        waitFor { handler.requests.isNotEmpty() }
        peer.ws().send("""{"type":"pair_cancel","id":"p_cancel"}""")
        assertEquals("p_cancel", handler.awaitCancelled())
        peer.assertNothingSent()
    }

    @Test
    fun promptExpiresAtExpiresAtAndSendsNothing() {
        val handler = Handler { awaitCancellation() }
        val (_, peer) = connect(handler)
        peer.ws().send(pairRequest(id = "p_exp", expiresAt = System.currentTimeMillis() + 300))
        assertEquals("p_exp", handler.awaitCancelled())
        peer.assertNothingSent()
    }

    @Test
    fun alreadyExpiredRequestsAreIgnored() {
        val handler = Handler { PairingDecision.Approved("pb1.x", "abcdefghijkl") }
        val (_, peer) = connect(handler)
        peer.ws().send(pairRequest(expiresAt = System.currentTimeMillis() - 1_000))
        peer.assertNothingSent()
        assertTrue(handler.requests.isEmpty())
    }

    @Test
    fun expiryUsesTheRelayClock() {
        // The phone's clock is 10 days ahead of the relay: without the welcome's serverTime the request
        // would look expired.
        val relayNow = System.currentTimeMillis() - 10L * 24 * 3_600_000
        val handler = Handler { PairingDecision.Denied("denied") }
        val (_, peer) = connect(handler, serverTime = relayNow)
        peer.ws().send(pairRequest(expiresAt = relayNow + 60_000))
        assertEquals("denied", peer.nextFrame()["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun disconnectCancelsOpenPrompts() {
        val handler = Handler { awaitCancellation() }
        val (_, peer) = connect(handler)
        server.enqueue(MockResponse().withWebSocketUpgrade(Peer())) // the reconnect
        peer.ws().send(pairRequest(id = "p_drop"))
        waitFor { handler.requests.isNotEmpty() }
        peer.ws().close(1001, "going away")
        assertEquals("p_drop", handler.awaitCancelled())
    }

    @Test
    fun stopCancelsOpenPrompts() {
        val handler = Handler { awaitCancellation() }
        val (client, peer) = connect(handler)
        peer.ws().send(pairRequest(id = "p_stop"))
        waitFor { handler.requests.isNotEmpty() }
        client.stop()
        assertEquals("p_stop", handler.awaitCancelled())
    }

    @Test
    fun completeAndAbandonReachTheHandler() {
        val handler = Handler { PairingDecision.Denied("denied") }
        val (_, peer) = connect(handler)
        peer.ws().send("""{"type":"pair_complete","id":"p_ok","tokenId":"abcdefghijkl"}""")
        assertEquals(Triple("p_ok", "abcdefghijkl", true), handler.nextFinished())
        peer.ws().send("""{"type":"pair_abandoned","id":"p_gone","tokenId":"bbbbbbbbbbbb"}""")
        assertEquals(Triple("p_gone", "bbbbbbbbbbbb", false), handler.nextFinished())
        peer.ws().send("""{"type":"pair_abandoned","id":"p_never"}""")
        assertEquals(Triple("p_never", null, false), handler.nextFinished())
    }

    @Test
    fun abandonWhilePromptIsOpenCancelsIt() {
        val handler = Handler { awaitCancellation() }
        val (_, peer) = connect(handler)
        peer.ws().send(pairRequest(id = "p_open"))
        waitFor { handler.requests.isNotEmpty() }
        peer.ws().send("""{"type":"pair_abandoned","id":"p_open"}""")
        assertEquals("p_open", handler.awaitCancelled())
        assertEquals(Triple("p_open", null, false), handler.nextFinished())
        peer.assertNothingSent()
    }

    @Test
    fun duplicateRequestsShowOnePrompt() {
        val release = CompletableDeferred<Unit>()
        val handler = Handler {
            release.await()
            PairingDecision.Denied("denied")
        }
        val (_, peer) = connect(handler)
        peer.ws().send(pairRequest(id = "p_dup"))
        peer.ws().send(pairRequest(id = "p_dup"))
        waitFor { handler.requests.isNotEmpty() }
        release.complete(Unit)
        assertEquals("pair_response", peer.nextFrame().type())
        peer.assertNothingSent()
        assertEquals(1, handler.requests.size)
    }

    @Test
    fun malformedPairingFramesAreIgnored() {
        val handler = Handler { PairingDecision.Denied("denied") }
        val (client, peer) = connect(handler)
        peer.ws().send("""{"type":"pair_request"}""")
        peer.ws().send("""{"type":"pair_request","id":""}""")
        peer.ws().send("""{"type":"pair_cancel"}""")
        peer.ws().send("""{"type":"pair_complete"}""")
        peer.assertNothingSent()
        assertTrue(handler.requests.isEmpty())
        assertTrue(client.status.value is RelayStatus.Connected)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < deadline, "timed out waiting")
            Thread.sleep(10)
        }
    }

    private companion object {
        fun JsonObject.type(): String? = this["type"]?.jsonPrimitive?.content
    }
}
