package dev.phonebridge.core.mcp

import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.ToolContent
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilitiesToolTest {
    private enum class Ringer { NORMAL }

    private val registry = ToolRegistry()

    private fun ctx(scopes: Set<String>) = CallContext("tokid", "Claude", scopes, "local", "req1") { }

    private suspend fun run(scopes: Set<String>, status: () -> Map<String, Any?> = { emptyMap() }): JsonObject {
        val cap = capabilitiesTool(registry, status)
        if (registry.get(cap.name) == null) registry.register(cap)
        val result = registry.get(CAPABILITIES_TOOL_NAME)!!.call(JsonObject(emptyMap()), ctx(scopes))
        assertFalse(result.isError)
        return Json.parseToJsonElement((result.content.single() as ToolContent.Text).text).jsonObject
    }

    init {
        registry.register(tool("device_status", "Device status", "d", Scopes.DEVICE, Risk.READ) { _, _ -> ToolResult.text("") })
        registry.register(
            tool("sms_send", "Send SMS", "d", Scopes.MESSAGES_SEND, Risk.SENSITIVE, unavailable = { "SEND_SMS not granted" }) { _, _ ->
                ToolResult.text("")
            },
        )
        registry.register(
            tool("ui_tap", "Tap", "d", Scopes.SCREEN_CONTROL, Risk.ACTION, unavailable = { error("boom") }) { _, _ -> ToolResult.text("") },
        )
    }

    @Test
    fun metadata() {
        val cap = capabilitiesTool(registry)
        assertEquals("bridge_capabilities", cap.name)
        assertEquals("", cap.scope)
        assertEquals(Risk.READ, cap.risk)
        assertFalse(cap.confirmsInternally)
        assertEquals("object", cap.inputSchema["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun reportsTokenStatusAndVisibleTools() = runTest {
        val out = run(setOf(Scopes.MESSAGES_SEND, Scopes.DEVICE)) {
            mapOf("accessibility_enabled" to false, "locked" to true, "battery" to 81)
        }
        val token = out["token"]!!.jsonObject
        assertEquals("Claude", token["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("device", "messages.send"), token["scopes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(
            JsonObject(mapOf("accessibility_enabled" to JsonPrimitive(false), "locked" to JsonPrimitive(true), "battery" to JsonPrimitive(81))),
            out["status"],
        )
        val tools = out["tools"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("device_status", "sms_send", "bridge_capabilities"), tools.map { it["name"]!!.jsonPrimitive.content })

        val status = tools[0]
        assertEquals("Device status", status["title"]!!.jsonPrimitive.content)
        assertEquals("READ", status["risk"]!!.jsonPrimitive.content)
        assertEquals("device", status["scope"]!!.jsonPrimitive.content)
        assertTrue(status["available"]!!.jsonPrimitive.boolean)
        assertNull(status["unavailable_reason"])

        val sms = tools[1]
        assertEquals("SENSITIVE", sms["risk"]!!.jsonPrimitive.content)
        assertFalse(sms["available"]!!.jsonPrimitive.boolean)
        assertEquals("SEND_SMS not granted", sms["unavailable_reason"]!!.jsonPrimitive.content)

        assertEquals("", tools[2]["scope"]!!.jsonPrimitive.content)
    }

    @Test
    fun failingAvailabilityCheckIsReported() = runTest {
        val tap = run(Scopes.ALL)["tools"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == "ui_tap" }
        assertFalse(tap["available"]!!.jsonPrimitive.boolean)
        assertTrue(tap["unavailable_reason"]!!.jsonPrimitive.content.contains("boom"))
    }

    @Test
    fun failingStatusIsReportedNotThrown() = runTest {
        val out = run(emptySet()) { throw IllegalStateException("no context") }
        assertTrue(out["status"]!!.jsonObject["error"]!!.jsonPrimitive.content.contains("no context"))
        assertEquals(listOf("bridge_capabilities"), out["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })
    }

    @Test
    fun convertsStatusValuesSafely() {
        val json = StatusJson.toJsonObject(
            mapOf(
                "s" to "x", "i" to 3, "l" to 4L, "d" to 1.5, "nan" to Double.NaN, "inf" to Float.POSITIVE_INFINITY, "b" to true,
                "n" to null, "list" to listOf(1, "a", null), "map" to mapOf("k" to listOf(true)), "enum" to Ringer.NORMAL,
                "arr" to arrayOf("x"), "ints" to intArrayOf(1, 2), "c" to 'z', "other" to StringBuilder("sb"),
                "json" to JsonPrimitive("raw"),
            ),
        )
        assertEquals(JsonPrimitive("x"), json["s"])
        assertEquals(JsonPrimitive(3), json["i"])
        assertEquals(JsonPrimitive(4L), json["l"])
        assertEquals(JsonPrimitive(1.5), json["d"])
        assertEquals(JsonNull, json["nan"])
        assertEquals(JsonNull, json["inf"])
        assertEquals(JsonPrimitive(true), json["b"])
        assertEquals(JsonNull, json["n"])
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("a"), JsonNull)), json["list"])
        assertEquals(JsonObject(mapOf("k" to JsonArray(listOf(JsonPrimitive(true))))), json["map"])
        assertEquals(JsonPrimitive("NORMAL"), json["enum"])
        assertEquals(JsonArray(listOf(JsonPrimitive("x"))), json["arr"])
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), json["ints"])
        assertEquals(JsonPrimitive("z"), json["c"])
        assertEquals(JsonPrimitive("sb"), json["other"])
        assertEquals(JsonPrimitive("raw"), json["json"])
        // Encodes to valid JSON.
        Json.parseToJsonElement(json.toString())
    }

    @Test
    fun deepNestingIsCut() {
        var nested: Any = "leaf"
        repeat(20) { nested = listOf(nested) }
        val encoded = StatusJson.toJsonObject(mapOf("deep" to nested)).toString()
        Json.parseToJsonElement(encoded)
    }
}
