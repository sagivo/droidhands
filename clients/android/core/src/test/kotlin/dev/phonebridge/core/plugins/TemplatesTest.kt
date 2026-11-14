package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.ToolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TemplatesTest {
    private val values = buildJsonObject {
        put("query", "lofi beats & chill/50%")
        put("n", 15L)
        put("x", 2.0)
        put("y", 2.5)
        put("on", true)
    }

    @Test
    fun placeholdersMayHaveSpaces() {
        val t = Template.parse("Play {{query}} ({{ n }}x)")
        assertEquals(listOf("query", "n"), t.paramNames)
        assertEquals("Play lofi beats & chill/50% (15x)", t.render(values))
        assertNull(t.singleParam)
        assertEquals("n", Template.parse("{{ n }}").singleParam)
        assertNull(Template.parse(" {{n}}").singleParam)
    }

    @Test
    fun strayBracesAndBadNamesAreErrors() {
        for (bad in listOf("{{query", "query}}", "a {{ }} b", "{{Query}}", "{{1x}}", "{{a.b}}", "{{a {{b}} }}", "{{{query}}}")) {
            assertFailsWith<TemplateException>(bad) { Template.parse(bad) }
        }
        // Single braces are plain text.
        assertEquals("{a} }", Template.parse("{a} }").render(values))
    }

    @Test
    fun missingValuesBecomeEmpty() {
        assertEquals("q= & n=15", Template.parse("q={{missing}} & n={{n}}").render(values))
    }

    @Test
    fun valuesAreTextNumbersWithoutTrailingZerosAndBooleans() {
        assertEquals("2 2.5 true", Template.parse("{{x}} {{y}} {{on}}").render(values))
    }

    @Test
    fun urlContextPercentEncodesEverythingButUnreserved() {
        val url = Template.parse("https://example.com/search?q={{query}}&n={{n}}").render(values, urlEncode = true)
        assertEquals("https://example.com/search?q=lofi%20beats%20%26%20chill%2F50%25&n=15", url)
        assertEquals("caf%C3%A9%20%F0%9F%8E%B5", Templates.percentEncode("café 🎵"))
        assertEquals("AZaz09-._~", Templates.percentEncode("AZaz09-._~"))
        assertEquals("%3A%2F%3F%23%5B%5D%40%21%24%27%28%29%2A%2B%2C%3B%3D", Templates.percentEncode(":/?#[]@!$'()*+,;="))
    }

    @Test
    fun urlSchemesMustBeLiteralAndAllowed() {
        assertNull(Templates.urlTemplateProblem("spotify:search:{{q}}"))
        assertNull(Templates.urlTemplateProblem("https://x.com/{{q}}"))
        assertNull(Templates.urlTemplateProblem("geo:0,0?q={{q}}"))
        assertNotNull(Templates.urlTemplateProblem("{{scheme}}://x"))
        assertNotNull(Templates.urlTemplateProblem("http{{s}}://x"))
        assertNotNull(Templates.urlTemplateProblem("no-colon-{{q}}"))
        assertNotNull(Templates.urlTemplateProblem("1http://x"))
        for (scheme in Templates.FORBIDDEN_SCHEMES) {
            assertTrue(Templates.urlTemplateProblem("$scheme:x")!!.contains("not allowed"), scheme)
            assertTrue(Templates.urlTemplateProblem("${scheme.uppercase()}:x")!!.contains("not allowed"), scheme)
        }
    }

    @Test
    fun renderedUrlsAreCheckedAgain() {
        Templates.requireAllowedUrl("https://example.com")
        for (bad in listOf("javascript:alert(1)", "INTENT:#Intent;end", "file:///sdcard", " content://x", "nothing")) {
            val e = assertFailsWith<ToolException>(bad) { Templates.requireAllowedUrl(bad) }
            assertTrue("nothing was opened" in e.message!!, e.message)
        }
    }

    @Test
    fun valueOfTreatsJsonNullAsMissing() {
        val v = JsonObject(mapOf("a" to kotlinx.serialization.json.JsonNull, "b" to JsonPrimitive("x")))
        assertNull(Templates.valueOf(v, "a"))
        assertEquals(JsonPrimitive("x"), Templates.valueOf(v, "b"))
        assertEquals("[]", Template.parse("[{{a}}]").render(v))
    }
}
