package com.droidhands.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [RelayPairings] edge cases and the pairing frame helpers of [RelayProtocol]. */
class RelayPairingsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val now = 1_731_500_000_000L

    @After
    fun tearDown() = scope.cancel()

    private class Recorder : PairingHandler {
        val finished = LinkedBlockingQueue<Triple<String, String?, Boolean>>()
        var decision: PairingDecision = PairingDecision.Approved("pb1.secret", "abcdefghijkl")

        override suspend fun onPairRequest(request: PairingRequest): PairingDecision = decision

        override fun onPairFinished(id: String, tokenId: String?, completed: Boolean) {
            finished += Triple(id, tokenId, completed)
        }
    }

    private fun pairings(handler: PairingHandler?, sent: MutableList<String>, sendResult: Boolean = true) = RelayPairings(
        handler = handler,
        send = { sent += it; sendResult },
        launch = { start, block -> scope.launch(start = start, block = block) },
        relayNow = { now },
    )

    private fun frame(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun undeliverableTokenIsReportedAsAbandoned() {
        val handler = Recorder()
        val sent = CopyOnWriteArrayList<String>()
        pairings(handler, sent, sendResult = false).onRequest(frame("""{"type":"pair_request","id":"p_1","expiresAt":${now + 60_000}}"""))
        assertEquals(Triple("p_1", "abcdefghijkl", false), assertNotNull(handler.finished.poll(10, TimeUnit.SECONDS)))
        assertEquals(1, sent.size, "it was attempted")
    }

    @Test
    fun deliveredTokenIsNotReported() {
        val handler = Recorder()
        val sent = CopyOnWriteArrayList<String>()
        pairings(handler, sent).onRequest(frame("""{"type":"pair_request","id":"p_1","expiresAt":${now + 60_000}}"""))
        val deadline = System.currentTimeMillis() + 10_000
        while (sent.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(sent.single().contains("\"approved\":true"))
        assertNull(handler.finished.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun tooManyOpenPromptsAreBusy() {
        val handler = object : PairingHandler {
            override suspend fun onPairRequest(request: PairingRequest): PairingDecision = kotlinx.coroutines.awaitCancellation()
            override fun onPairFinished(id: String, tokenId: String?, completed: Boolean) = Unit
        }
        val sent = CopyOnWriteArrayList<String>()
        val p = pairings(handler, sent)
        repeat(RelayPairings.MAX_PENDING) { p.onRequest(frame("""{"type":"pair_request","id":"p_$it","expiresAt":${now + 60_000}}""")) }
        assertEquals(RelayPairings.MAX_PENDING, p.pendingCount)
        p.onRequest(frame("""{"type":"pair_request","id":"p_extra","expiresAt":${now + 60_000}}"""))
        val busy = Json.parseToJsonElement(sent.single()).jsonObject
        assertEquals("p_extra", busy["id"]!!.jsonPrimitive.content)
        assertEquals("busy", busy["reason"]!!.jsonPrimitive.content)
        p.cancelAll()
        assertEquals(0, p.pendingCount)
    }

    @Test
    fun handlerGetsExpiresAtConvertedToThePhonesClock() {
        val requests = LinkedBlockingQueue<PairingRequest>()
        val handler = object : PairingHandler {
            override suspend fun onPairRequest(request: PairingRequest): PairingDecision {
                requests += request
                return PairingDecision.Denied("denied")
            }
            override fun onPairFinished(id: String, tokenId: String?, completed: Boolean) = Unit
        }
        val phoneNow = now + 200_000 // The phone's clock runs 200 s ahead of the relay's.
        val p = RelayPairings(
            handler = handler,
            send = { true },
            launch = { start, block -> scope.launch(start = start, block = block) },
            relayNow = { now },
            localNow = { phoneNow },
        )
        p.onRequest(frame("""{"type":"pair_request","id":"p_1","expiresAt":${now + 60_000}}"""))
        assertEquals(phoneNow + 60_000, assertNotNull(requests.poll(10, TimeUnit.SECONDS)).expiresAt)

        // A far-future expiresAt is capped like the prompt itself.
        p.onRequest(frame("""{"type":"pair_request","id":"p_2","expiresAt":${now + 3_600_000}}"""))
        assertEquals(phoneNow + RelayPairings.MAX_PROMPT_MS, assertNotNull(requests.poll(10, TimeUnit.SECONDS)).expiresAt)
    }

    @Test
    fun parsesAndSanitizesRequests() {
        val request = RelayProtocol.pairingRequest(
            frame(
                """{"type":"pair_request","id":"p_abc","clientName":"Cl‮aude\u0000\n  Desktop ${"x".repeat(100)}",""" +
                    """"redirectHost":"localhost:33418","displayCode":"K7Q2","expiresAt":1731500180000}""",
            ),
            now,
        )!!
        assertEquals("p_abc", request.id)
        assertTrue(request.clientName.startsWith("Claude Desktop xxx"), request.clientName)
        assertEquals(60, request.clientName.length)
        assertEquals("localhost:33418", request.redirectHost)
        assertEquals("K7Q2", request.displayCode)
        assertNull(request.remoteAddress)
        assertNull(request.userAgent)
        assertEquals(1731500180000, request.expiresAt)
        assertFalse(request.viaAgent)

        val defaults = RelayProtocol.pairingRequest(frame("""{"type":"pair_request","id":"p_x","clientName":"  ","expiresAt":"soon"}"""), now)!!
        assertEquals("Unknown app", defaults.clientName)
        assertEquals("unknown", defaults.redirectHost)
        assertEquals("", defaults.displayCode)
        assertEquals(now + RelayProtocol.DEFAULT_PAIRING_TTL_MS, defaults.expiresAt)

        assertFalse(defaults.viaAgent)

        assertNull(RelayProtocol.pairingRequest(frame("""{"type":"pair_request"}"""), now))
        assertNull(RelayProtocol.pairingRequest(frame("""{"type":"pair_request","id":" "}"""), now))
        assertNull(RelayProtocol.pairingRequest(frame("""{"type":"pair_request","id":7}"""), now))
    }

    @Test
    fun agentSignInIsFlowDevice() {
        fun flow(json: String) = RelayProtocol.pairingRequest(
            frame("""{"type":"pair_request","id":"p_d","clientName":"Instinct","redirectHost":"agent sign-in","displayCode":"K7Q2"$json}"""),
            now,
        )!!
        val device = flow(""","flow":"device"""")
        assertTrue(device.viaAgent)
        assertEquals("Instinct", device.clientName)
        assertEquals("K7Q2", device.displayCode)
        assertFalse(flow("").viaAgent)
        assertFalse(flow(""","flow":"browser"""").viaAgent)
        assertFalse(flow(""","flow":"DEVICE"""").viaAgent)
        assertFalse(flow(""","flow":true""").viaAgent)
    }

    @Test
    fun pairResponseFrames() {
        val approved = RelayProtocol.pairResponse("p_1", PairingDecision.Approved("pb1.t", "abcdefghijkl"))
        assertEquals("pair_response", approved["type"]!!.jsonPrimitive.content)
        assertEquals(true, approved["approved"]!!.jsonPrimitive.boolean)
        assertEquals(JsonPrimitive("pb1.t"), approved["accessToken"])
        assertEquals(JsonPrimitive("abcdefghijkl"), approved["tokenId"])
        assertNull(approved["reason"])

        val denied = RelayProtocol.pairResponse("p_2", PairingDecision.Denied("timeout"))
        assertEquals(false, denied["approved"]!!.jsonPrimitive.boolean)
        assertEquals("timeout", denied["reason"]!!.jsonPrimitive.content)
        assertNull(denied["accessToken"])
        assertEquals("denied", RelayProtocol.pairResponse("p_3", PairingDecision.Denied("user closed it"))["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun displayText() {
        assertEquals("a b", RelayProtocol.displayText(" a \t\r\n b​ ", 10))
        assertEquals("ab", RelayProtocol.displayText("a‮b⁦", 10))
        assertEquals("line sep", RelayProtocol.displayText("line sep", 20))
        assertNull(RelayProtocol.displayText("\u0000 \u0007", 10))
        assertNull(RelayProtocol.displayText(null, 10))
        // Never splits a surrogate pair.
        assertEquals("ab", RelayProtocol.displayText("ab😀", 3))
    }
}
