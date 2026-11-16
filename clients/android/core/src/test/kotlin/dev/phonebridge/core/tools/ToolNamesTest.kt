package dev.phonebridge.core.tools

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolNamesTest {
    @Test
    fun namesAreValidRegistryNames() {
        for (name in ToolNames.ALL) assertTrue(Regex("^[a-z][a-z0-9_]{0,63}$").matches(name), name)
    }

    @Test
    fun recipesMayUseOrdinaryToolsOnly() {
        for (ok in listOf("sms_send", "phone_call", "calendar_event_create", "alarm_set", "url_open")) {
            assertNull(ToolNames.recipeRefusal(ok), ok)
        }
        for (refused in listOf(
            "intent_start", "ui_tap", "ui_wait", "screen_read", "screen_screenshot", "app_action_run", "app_actions_list",
            "apps_list", "bridge_capabilities", "no_such_tool",
        )) {
            assertNotNull(ToolNames.recipeRefusal(refused), refused)
        }
    }

    /** docs/TOOLS.md documents exactly these tools (each as a table row starting with | `name` |). */
    @Test
    fun matchesTheToolCatalogDoc() {
        val pluginsDir = System.getProperty("phonebridge.pluginsDir")?.let(::File) ?: return
        val doc = File(pluginsDir.parentFile, "docs/TOOLS.md").readText()
        val documented = Regex("^\\| `([a-z_]+)` \\|", RegexOption.MULTILINE).findAll(doc).map { it.groupValues[1] }.toSet()
        assertEquals(ToolNames.ALL.sorted(), documented.sorted())
    }
}
