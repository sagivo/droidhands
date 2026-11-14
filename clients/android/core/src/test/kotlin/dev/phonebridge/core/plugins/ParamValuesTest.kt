package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.ToolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParamValuesTest {
    private val params = listOf(
        ParamSpec("destination", "Address or place name", required = true, maxLength = 300),
        ParamSpec(
            "mode", "How to travel", enum = listOf("driving", "walking").map(::JsonPrimitive), default = JsonPrimitive("driving"),
        ),
        ParamSpec("minutes", "Timer length", type = ParamType.INTEGER, minimum = 1.0, maximum = 1440.0),
        ParamSpec("zoom", "Zoom", type = ParamType.NUMBER),
        ParamSpec("loud", "Loud", type = ParamType.BOOLEAN),
        ParamSpec("phone", "Phone", pattern = "^\\+?[0-9]{6,15}$", minLength = 6),
    )

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun fails(json: String): String = assertFailsWith<ToolException>(json) { ParamValues.validate(params, args(json)) }.message!!

    @Test
    fun fillsDefaultsAndTypesValuesInDeclarationOrder() {
        val v = ParamValues.validate(params, args("""{"minutes":"5","destination":"Eiffel Tower","zoom":"2.5","loud":"true"}"""))
        assertEquals("""{"destination":"Eiffel Tower","mode":"driving","minutes":5,"zoom":2.5,"loud":true}""", v.toString())
        // Numbers and booleans may stand in for text; nulls count as absent.
        assertEquals("""{"destination":"42","mode":"driving"}""", ParamValues.validate(params, args("""{"destination":42,"loud":null}""")).toString())
        assertEquals(5L.toString(), ParamValues.validate(params, args("""{"destination":"x","minutes":5.0}"""))["minutes"].toString())
    }

    @Test
    fun refusesUnknownAndMissingParameters() {
        val unknown = fails("""{"destination":"x","speed":"fast","color":1}""")
        assertTrue("Unknown parameters 'speed', 'color'" in unknown, unknown)
        assertTrue("destination (required), mode, minutes" in unknown, unknown)
        assertTrue("Missing required parameter 'destination' (Address or place name)" in fails("""{}"""))
        assertTrue("'destination'" in fails("""{"destination":"   "}"""), "blank counts as missing")
        assertTrue("takes no parameters" in assertFailsWith<ToolException> { ParamValues.validate(emptyList(), args("""{"a":1}""")) }.message!!)
    }

    @Test
    fun refusesWrongTypes() {
        assertTrue("'minutes' must be a whole number" in fails("""{"destination":"x","minutes":"five"}"""))
        assertTrue("'minutes' must be a whole number" in fails("""{"destination":"x","minutes":1.5}"""))
        assertTrue("'zoom' must be a number" in fails("""{"destination":"x","zoom":true}"""))
        assertTrue("'loud' must be true or false" in fails("""{"destination":"x","loud":"maybe"}"""))
        assertTrue("got an object" in fails("""{"destination":{"a":1}}"""))
        assertTrue("got a list" in fails("""{"destination":["a"]}"""))
    }

    @Test
    fun checksConstraints() {
        assertTrue("'mode' must be one of driving, walking" in fails("""{"destination":"x","mode":"flying"}"""))
        assertTrue("at most 300 characters" in fails("""{"destination":"${"x".repeat(301)}"}"""))
        assertTrue("at least 1" in fails("""{"destination":"x","minutes":0}"""))
        assertTrue("at most 1440" in fails("""{"destination":"x","minutes":1441}"""))
        assertTrue("expected format" in fails("""{"destination":"x","phone":"12345a7"}"""))
        assertTrue("at least 6 characters" in fails("""{"destination":"x","phone":"+12"}"""))
        ParamValues.validate(params, args("""{"destination":"x","phone":"+972501234567"}"""))
        // Lengths count characters, not UTF-16 units.
        ParamValues.validate(listOf(ParamSpec("e", "e", maxLength = 2)), args("""{"e":"🎵🎵"}"""))
        // Strings default to at most 2000 characters.
        assertFailsWith<ToolException> { ParamValues.validate(listOf(ParamSpec("t", "t")), args("""{"t":"${"x".repeat(2001)}"}""")) }
    }

    @Test
    fun numericEnumsCompareByValue() {
        val spec = ParamSpec("speed", "s", type = ParamType.NUMBER, enum = listOf(JsonPrimitive(1.0), JsonPrimitive(1.5)))
        ParamValues.validate(listOf(spec), args("""{"speed":1}"""))
        assertFailsWith<ToolException> { ParamValues.validate(listOf(spec), args("""{"speed":2}""")) }
    }

    @Test
    fun jsonSchemaMatchesTheParameters() {
        val schema = ParamSchemas.objectSchema(params).toString()
        assertEquals(
            """{"type":"object","properties":{""" +
                """"destination":{"type":"string","description":"Address or place name","maxLength":300},""" +
                """"mode":{"type":"string","description":"How to travel","enum":["driving","walking"],"default":"driving","maxLength":2000},""" +
                """"minutes":{"type":"integer","description":"Timer length","minimum":1,"maximum":1440},""" +
                """"zoom":{"type":"number","description":"Zoom"},""" +
                """"loud":{"type":"boolean","description":"Loud"},""" +
                """"phone":{"type":"string","description":"Phone","minLength":6,"maxLength":2000,"pattern":"^\\+?[0-9]{6,15}${'$'}"}},""" +
                """"required":["destination"],"additionalProperties":false}""",
            schema,
        )
        assertEquals("""{"type":"object","properties":{},"additionalProperties":false}""", ParamSchemas.objectSchema(emptyList()).toString())
    }
}
