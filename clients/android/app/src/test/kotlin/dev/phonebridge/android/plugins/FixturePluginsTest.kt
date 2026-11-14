package dev.phonebridge.android.plugins

import dev.phonebridge.core.plugins.PluginFile
import dev.phonebridge.core.plugins.PluginLoader
import dev.phonebridge.core.plugins.PluginSource
import dev.phonebridge.core.plugins.StepsRecipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The E2E test plugins in clients/android/fixtures/plugins are pushed to the emulator as local plugins
 * (scripts/plugin-push.sh): they must pass the same checks, examples included, as an import on the phone.
 */
class FixturePluginsTest {
    private val dir = File(System.getProperty("phonebridge.fixturePluginsDir") ?: "../fixtures/plugins")

    @Test
    fun fixturePluginsAreValidLocalPlugins() {
        val files = dir.listFiles().orEmpty().filter { it.isDirectory }.map { File(it, "plugin.yaml") }.filter { it.isFile }
        assertTrue("no fixture plugins in $dir", files.isNotEmpty())
        for (file in files) {
            val result = PluginLoader.load(PluginFile("${file.parentFile.name}/plugin.yaml", file.readText()), PluginSource.LOCAL)
            assertTrue(result.errors.joinToString("\n"), result.isValid)
        }
    }

    @Test
    fun fakemailTestSendsWithAScreenRecipe() {
        val plugin = PluginLoader.load(PluginFile("fakemail_test/plugin.yaml", File(dir, "fakemail_test/plugin.yaml").readText()), PluginSource.LOCAL).plugin!!
        assertEquals(listOf("dev.phonebridge.fixture.mail"), plugin.androidPackages)
        val send = plugin.actions.single { it.id == "send_email" }
        assertTrue(send.android is StepsRecipe)
        assertEquals(listOf("to", "subject", "body"), send.params.map { it.name })
    }
}
