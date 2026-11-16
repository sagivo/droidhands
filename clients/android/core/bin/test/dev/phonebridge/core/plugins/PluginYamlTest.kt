package dev.phonebridge.core.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PluginYamlTest {
    @Test
    fun plainDataKeepsOrderAndTellsQuotedTextFromNumbers() {
        val root = PluginYaml.load(
            """
            b: 1
            a: "1"
            c: [x, 2.5, true, null]
            d: {e: yes, f: on, g: 0x10}
            """.trimIndent(),
        ).jsonObject
        assertEquals(listOf("b", "a", "c", "d"), root.keys.toList())
        assertFalse(root.getValue("b").jsonPrimitive.isString)
        assertTrue(root.getValue("a").jsonPrimitive.isString)
        val c = assertIs<JsonArray>(root.getValue("c"))
        assertEquals(listOf(JsonPrimitive("x"), JsonPrimitive(2.5), JsonPrimitive(true), JsonNull), c.toList())
        val d = root.getValue("d").jsonObject
        // YAML 1.2: yes/on are text, not booleans.
        assertEquals(JsonPrimitive("yes"), d["e"])
        assertEquals(JsonPrimitive("on"), d["f"])
        assertEquals(JsonPrimitive(16L), d["g"])
    }

    @Test
    fun customTagsAreRejected() {
        for (yaml in listOf("a: !!java.io.File /tmp", "a: !custom x", "a: !!binary aGVsbG8=", "a: !!set {x}")) {
            assertFailsWith<PluginFormatException>(yaml) { PluginYaml.load(yaml) }
        }
    }

    @Test
    fun aliasBombsAreRejected() {
        val bomb = buildString {
            append("a: &a [x, x, x, x, x, x, x, x, x, x]\n")
            for (i in 1..9) {
                append("${'a' + i}: &${'a' + i} [")
                append((1..10).joinToString(", ") { "*${'a' + i - 1}" })
                append("]\n")
            }
        }
        val e = assertFailsWith<PluginFormatException> { PluginYaml.load(bomb) }
        assertTrue("alias" in e.message!!.lowercase(), e.message)
        // A few aliases are fine.
        PluginYaml.load("a: &p {description: d}\nb: *p\n")
    }

    @Test
    fun aliasesMayNotExpandTooFar() {
        // 18 aliases (under the engine's limit) over 6 levels: 3^6 copies of a 1000-value list.
        val bomb = buildString {
            append("l0: &l0 [").append((1..1000).joinToString(", ") { "x" }).append("]\n")
            for (i in 1..6) append("l$i: &l$i [*l${i - 1}, *l${i - 1}, *l${i - 1}]\n")
        }
        val e = assertFailsWith<PluginFormatException> { PluginYaml.load(bomb) }
        assertEquals("too many values (aliases expand too far)", e.message)
    }

    @Test
    fun largeFilesAreRejected() {
        val big = "a: \"" + "x".repeat(PluginYaml.MAX_CHARS) + "\"\n"
        val e = assertFailsWith<PluginFormatException> { PluginYaml.load(big) }
        assertTrue("larger than" in e.message!!, e.message)
    }

    @Test
    fun duplicateKeysAndSeveralDocumentsAreRejected() {
        assertFailsWith<PluginFormatException> { PluginYaml.load("a: 1\na: 2\n") }
        assertFailsWith<PluginFormatException> { PluginYaml.load("a: 1\n---\nb: 2\n") }
    }

    @Test
    fun deepNestingIsAnErrorNotACrash() {
        assertFailsWith<PluginFormatException> { PluginYaml.load("a: " + "[".repeat(5000) + "]".repeat(5000)) }
        assertFailsWith<PluginFormatException> { PluginYaml.load("a: " + "[".repeat(40) + "]".repeat(40)) }
    }

    @Test
    fun nonTextKeysAndSyntaxErrorsAreReported() {
        val keys = assertFailsWith<PluginFormatException> { PluginYaml.load("actions:\n  - 1: x\n") }
        assertTrue("actions[0]: keys must be text" in keys.message!!, keys.message)
        val syntax = assertFailsWith<PluginFormatException> { PluginYaml.load("a: [1, 2\nb: c") }
        assertTrue(syntax.message!!.startsWith("not valid YAML"), syntax.message)
        assertTrue("line" in syntax.message!!, syntax.message)
    }

    @Test
    fun emptyFileIsNull() {
        assertEquals(JsonNull, PluginYaml.load(""))
        assertIs<JsonObject>(PluginYaml.load("{}"))
    }

    @Test
    fun pathsQuoteDottedKeys() {
        assertEquals("actions[1].android.intent.extras[\"android.intent.extra.TEXT\"]", PluginPath.key("actions[1].android.intent.extras", "android.intent.extra.TEXT"))
        assertEquals("extras.count", PluginPath.key("extras", "count"))
        assertEquals("id", PluginPath.key("", "id"))
        assertEquals("root[\"a.b\"]", PluginPath.key("", "a.b"))
        assertEquals("actions[0]", PluginPath.index("actions", 0))
    }
}
