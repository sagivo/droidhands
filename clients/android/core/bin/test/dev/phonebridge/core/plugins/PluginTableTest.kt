package dev.phonebridge.core.plugins

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class PluginTableTest {
    private val pluginsDir: File = System.getProperty("phonebridge.pluginsDir")?.let(::File)
        ?: File("../../../plugins").takeIf { it.isDirectory }
        ?: fail("Set the phonebridge.pluginsDir system property to the repository's plugins/ folder")

    private fun plugin(id: String, name: String, vararg titles: String, packages: String = "com.example.$id") = PluginLoader.load(
        buildString {
            append("id: $id\nname: \"$name\"\ndescription: Test.\ncategory: music\nandroid:\n  packages: [$packages]\nactions:\n")
            titles.forEachIndexed { i, t -> append("  - id: a$i\n    title: \"$t\"\n    description: Does it.\n    risk: action\n    android: {url: \"https://x/$i\"}\n") }
        },
    ).plugin!!

    @Test
    fun theReadmeTableIsUpToDate() {
        val readme = File(pluginsDir, "README.md")
        assertTrue(readme.isFile, "plugins/README.md is missing")
        if (readme.readText() != PluginTable.expected(pluginsDir)) {
            fail("plugins/README.md lists other plugins than plugins/ holds: ${PluginTable.HOW_TO_UPDATE}.")
        }
    }

    @Test
    fun tableRowsAreSortedByNameAndEscaped() {
        val table = PluginTable.table(
            listOf(
                plugin("zed", "Zed", "Play | pause"),
                plugin("alpha", "Alpha", "Search", "Open a playlist", packages = "com.alpha, com.alpha.lite"),
            ),
        )
        assertEquals(
            """
            | App | Android package | Category | Status | Actions |
            |---|---|---|---|---|
            | [Alpha](alpha/plugin.yaml) | `com.alpha`<br>`com.alpha.lite` | music | experimental | 2: Search, Open a playlist |
            | [Zed](zed/plugin.yaml) | `com.example.zed` | music | experimental | 1: Play \| pause |

            """.trimIndent(),
            table,
        )
        assertTrue("No bundled plugins yet" in PluginTable.table(emptyList()))
    }

    @Test
    fun updateReplacesOnlyTheGeneratedPart() {
        val readme = "# Plugins\n\nIntro.\n\n${PluginTable.START}\nold table\n${PluginTable.END}\n\nOutro.\n"
        val updated = PluginTable.update(readme, "| new |\n")
        assertEquals("# Plugins\n\nIntro.\n\n${PluginTable.START}\n| new |\n${PluginTable.END}\n\nOutro.\n", updated)
        assertEquals(updated, PluginTable.update(updated, "| new |\n"))
        assertFailsWith<IllegalArgumentException> { PluginTable.update("no markers", "| x |\n") }
    }
}
