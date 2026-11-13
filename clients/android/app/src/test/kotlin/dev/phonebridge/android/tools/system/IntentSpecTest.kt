package dev.phonebridge.android.tools.system

import dev.phonebridge.core.tools.ToolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentSpecTest {
    private val own = "dev.phonebridge.android"

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun parse(text: String) = IntentSpecParser.parse(json(text), own)

    private fun rejected(text: String): String = assertThrows(ToolException::class.java) { parse(text) }.message!!

    @Test
    fun `extras convert to typed values`() {
        val extras = IntentSpecParser.convertExtras(
            json(
                """{"s":"hi","t":"true","b":true,"i":42,"neg":-7,"big":3000000000,"d":1.5,"e":2e3,"one":1.0,"list":["a","b"]}""",
            ),
        )
        assertEquals(ExtraValue.Str("hi"), extras["s"])
        assertEquals(ExtraValue.Str("true"), extras["t"]) // quoted JSON stays a string
        assertEquals(ExtraValue.Bool(true), extras["b"])
        assertEquals(ExtraValue.IntValue(42), extras["i"])
        assertEquals(ExtraValue.IntValue(-7), extras["neg"])
        assertEquals(ExtraValue.LongValue(3_000_000_000L), extras["big"])
        assertEquals(ExtraValue.DoubleValue(1.5), extras["d"])
        assertEquals(ExtraValue.DoubleValue(2000.0), extras["e"])
        assertEquals(ExtraValue.DoubleValue(1.0), extras["one"])
        assertEquals(ExtraValue.StrList(listOf("a", "b")), extras["list"])
        assertEquals(listOf("s", "t", "b", "i", "neg", "big", "d", "e", "one", "list"), extras.keys.toList())
    }

    @Test
    fun `nested objects, nulls and non-string arrays are rejected`() {
        assertThrows(ToolException::class.java) { IntentSpecParser.convertExtras(json("""{"o":{"a":1}}""")) }
        assertThrows(ToolException::class.java) { IntentSpecParser.convertExtras(json("""{"n":null}""")) }
        assertThrows(ToolException::class.java) { IntentSpecParser.convertExtras(json("""{"l":[1,2]}""")) }
        assertThrows(ToolException::class.java) { IntentSpecParser.convertExtras(json("""{"l":["a",["b"]]}""")) }
        assertThrows(ToolException::class.java) { IntentSpecParser.convertExtras(json("""{"":"x"}""")) }
        assertTrue(IntentSpecParser.convertExtras(null).isEmpty())
    }

    @Test
    fun `extras are size-bounded in total`() {
        val half = "x".repeat(IntentSpecParser.MAX_STRING_CHARS / 2)
        assertEquals(1, IntentSpecParser.convertExtras(json("""{"a":"$half"}""")).size)
        assertThrows(ToolException::class.java) {
            IntentSpecParser.convertExtras(json("""{"a":"$half","b":["$half"]}"""))
        }
    }

    @Test
    fun `short actions and categories are expanded`() {
        assertEquals("android.intent.action.VIEW", IntentSpecParser.expandAction("VIEW"))
        assertEquals("android.settings.WIFI_SETTINGS", IntentSpecParser.expandAction("android.settings.WIFI_SETTINGS"))
        assertEquals("android.intent.category.BROWSABLE", IntentSpecParser.expandCategory("BROWSABLE"))
        assertEquals("com.example.CATEGORY", IntentSpecParser.expandCategory("com.example.CATEGORY"))
        assertThrows(ToolException::class.java) { IntentSpecParser.expandAction("view stuff") }
    }

    @Test
    fun `class names resolve like manifest names`() {
        assertEquals("com.example.app.Main", IntentSpecParser.resolveClassName("com.example.app", ".Main"))
        assertEquals("com.example.app.ui.Main", IntentSpecParser.resolveClassName("com.example.app", ".ui.Main"))
        assertEquals("com.example.app.Main", IntentSpecParser.resolveClassName("com.example.app", "Main"))
        assertEquals("com.other.Main", IntentSpecParser.resolveClassName("com.example.app", "com.other.Main"))
        assertEquals("com.example.app.Outer\$Inner", IntentSpecParser.resolveClassName("com.example.app", ".Outer\$Inner"))
        assertThrows(ToolException::class.java) { IntentSpecParser.resolveClassName("com.example.app", "Bad Name") }
    }

    @Test
    fun `full spec parses and normalizes`() {
        val spec = parse(
            """{"action":"SEND","data":" https://example.com ","mime_type":"Text/Plain","package":"com.example.app",
               "class_name":".ShareActivity","categories":["DEFAULT","DEFAULT"],"extras":{"android.intent.extra.TEXT":"hi"}}""",
        )
        assertEquals("android.intent.action.SEND", spec.action)
        assertEquals("https://example.com", spec.data)
        assertEquals("text/plain", spec.mimeType)
        assertEquals("com.example.app", spec.packageName)
        assertEquals("com.example.app.ShareActivity", spec.className)
        assertEquals(listOf("android.intent.category.DEFAULT"), spec.categories)
        assertEquals(ExtraValue.Str("hi"), spec.extras["android.intent.extra.TEXT"])
    }

    @Test
    fun `minimal spec`() {
        val spec = parse("""{"action":"android.settings.SETTINGS"}""")
        assertNull(spec.data)
        assertNull(spec.packageName)
        assertTrue(spec.extras.isEmpty())
    }

    @Test
    fun `invalid specs are rejected`() {
        rejected("""{}""")
        rejected("""{"action":"VIEW","class_name":".Main"}""").let { assertTrue(it.contains("package")) }
        rejected("""{"action":"VIEW","package":"not a package"}""")
        rejected("""{"action":"VIEW","mime_type":"plain"}""")
        rejected("""{"action":"VIEW","extras":"x=1"}""")
    }

    @Test
    fun `PhoneBridge itself cannot be targeted`() {
        assertTrue(rejected("""{"action":"VIEW","package":"dev.phonebridge.android"}""").contains("PhoneBridge"))
        assertTrue(
            rejected("""{"action":"VIEW","package":"com.example","class_name":"dev.phonebridge.android.confirm.ConfirmationActivity"}""")
                .contains("PhoneBridge"),
        )
    }

    @Test
    fun `describe summarizes the call`() {
        val text = IntentSpecParser.describe(
            parse("""{"action":"VIEW","data":"geo:0,0?q=pizza","package":"com.google.android.apps.maps","extras":{"n":5,"s":"x","l":["a"]}}"""),
        )
        assertEquals(
            "Start Android activity: action=android.intent.action.VIEW, data=geo:0,0?q=pizza, " +
                "package=com.google.android.apps.maps, extras={n=5, s=\"x\", l=[\"a\"]}",
            text,
        )
    }
}
