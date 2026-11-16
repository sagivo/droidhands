package com.droidhands.core.transport

import com.droidhands.core.auth.DeviceIdentity
import com.droidhands.core.mcp.TransportResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayProtocolTest {
    @Test
    fun deviceUrlMapping() {
        val cases = mapOf(
            "https://relay.example.com" to "wss://relay.example.com/v1/device",
            "https://relay.example.com/" to "wss://relay.example.com/v1/device",
            "http://10.0.2.2:8080" to "ws://10.0.2.2:8080/v1/device",
            "https://example.com/pb/" to "wss://example.com/pb/v1/device",
            "https://example.com/a/b//" to "wss://example.com/a/b/v1/device",
            "HTTPS://Example.com/x?y=1#z" to "wss://Example.com/x/v1/device",
            "wss://relay.example.com/base" to "wss://relay.example.com/base/v1/device",
            "ws://localhost:1234" to "ws://localhost:1234/v1/device",
            "  https://relay.example.com  " to "wss://relay.example.com/v1/device",
            "http://[::1]:8080/" to "ws://[::1]:8080/v1/device",
        )
        cases.forEach { (input, expected) -> assertEquals(expected, RelayProtocol.deviceUrl(input), input) }
        listOf("", "relay.example.com", "ftp://x", "https://", "not a url").forEach { assertNull(RelayProtocol.deviceUrl(it), it) }
    }

    @Test
    fun deviceUrlCarriesTheDeviceId() {
        val id = DeviceIdentity(ByteArray(32) { it.toByte() }).deviceId
        assertEquals("wss://example.com/pb/v1/device?deviceId=$id", RelayProtocol.deviceUrl("https://example.com/pb/", id))
        assertEquals("ws://10.0.2.2:8080/v1/device?deviceId=$id", RelayProtocol.deviceUrl("http://10.0.2.2:8080?x=1", id))
        assertNull(RelayProtocol.deviceUrl("ftp://x", id))
    }

    @Test
    fun heartbeatFrameIsFixed() {
        // Relays may match these exact bytes (Cloudflare auto-response); never reformat them.
        assertEquals("""{"type":"hb"}""", RelayProtocol.HEARTBEAT_FRAME)
        assertEquals(30_000L, RelayProtocol.HEARTBEAT_INTERVAL_MS)
    }

    @Test
    fun mcpUrlFallback() {
        assertEquals("https://example.com/pb/mcp", RelayProtocol.mcpUrl("https://example.com/pb/"))
        assertEquals("http://10.0.2.2:8080/mcp", RelayProtocol.mcpUrl("http://10.0.2.2:8080"))
    }

    @Test
    fun backoffGrowsWithJitterWithinBounds() {
        val random = Random(1)
        assertEquals(1_000, RelayProtocol.backoffDelayMs(0, random))
        for (attempt in 0..20) {
            repeat(50) {
                val d = RelayProtocol.backoffDelayMs(attempt, random)
                assertTrue(d in 1_000..60_000, "attempt $attempt: $d")
                val ceiling = (1_000L shl attempt.coerceAtMost(6)).coerceAtMost(60_000)
                assertTrue(d <= ceiling && d >= maxOf(1_000, ceiling / 2), "attempt $attempt: $d")
            }
        }
        assertTrue((1..100).map { RelayProtocol.backoffDelayMs(10, random) }.toSet().size > 10, "jittered")
    }

    @Test
    fun framesHaveProtocolShapes() {
        val identity = DeviceIdentity(ByteArray(32) { it.toByte() })
        val hello = RelayProtocol.hello(identity, DeviceHello("android", "0.1.0", "Pixel", null))
        assertEquals(setOf("type", "protocolVersion", "deviceId", "deviceSecret", "platform", "appVersion", "deviceName"), hello.keys)

        val response = RelayProtocol.response("r1", TransportResponse(202, null, mapOf("Content-Type" to "x")))
        assertEquals(JsonNull, response["body"])
        assertEquals(JsonPrimitive("x"), response["headers"]!!.jsonObject["content-type"])

        assertEquals(JsonObject(mapOf("type" to JsonPrimitive("pong"), "t" to JsonPrimitive(5))), RelayProtocol.pong(JsonPrimitive(5)))
        assertEquals(JsonObject(mapOf("type" to JsonPrimitive("pong"))), RelayProtocol.pong(null))

        val req = RelayProtocol.transportRequest(
            Json.parseToJsonElement("""{"type":"request","id":"x","headers":{"Authorization":"Bearer t","x":5},"remoteAddress":"1.2.3.4"}""").jsonObject,
        )
        assertEquals("Bearer t", req.authorization)
        assertEquals("", req.body)
        assertEquals("relay", req.transport)
        assertEquals("1.2.3.4", req.remoteAddress)
    }

    @Test
    fun utf8Length() {
        assertEquals(0, RelayProtocol.utf8Length(""))
        listOf("abc", "héllo", "日本", "a😀b").forEach {
            assertEquals(it.toByteArray(Charsets.UTF_8).size.toLong(), RelayProtocol.utf8Length(it), it)
        }
        // A lone surrogate counts as 3 bytes: an upper bound (encoders substitute a 1-byte '?').
        assertEquals(3, RelayProtocol.utf8Length("\uD800"))
    }
}
