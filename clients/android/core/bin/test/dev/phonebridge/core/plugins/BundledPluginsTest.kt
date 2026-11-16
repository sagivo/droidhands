package dev.phonebridge.core.plugins

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every `plugins/<id>/plugin.yaml` in the repository is valid and its examples pass (docs/PLUGINS.md §7),
 * so `./gradlew :core:test` fails on a bad plugin. The Gradle test task sets `phonebridge.pluginsDir`.
 */
class BundledPluginsTest {
    private val pluginsDir: File = System.getProperty("phonebridge.pluginsDir")?.let(::File)
        ?: File("../../../plugins").takeIf { it.isDirectory }
        ?: fail("Set the phonebridge.pluginsDir system property to the repository's plugins/ folder")

    @Test
    fun everyPluginIsValidAndItsExamplesPass() {
        val report = PluginDirectoryCheck.check(pluginsDir, pluginsDir.parentFile)
        if (!report.ok) fail("Plugin check failed:\n$report")
    }

    @Test
    fun theTemplateAndSpotifyAreChecked() {
        val report = PluginDirectoryCheck.check(pluginsDir, pluginsDir.parentFile).toString()
        assertTrue("plugins/_template/plugin.yaml: OK" in report, report)
        assertTrue("plugins/spotify/plugin.yaml: OK" in report, report)
    }

    @Test
    fun bundledCatalogLeavesOutUnderscoreFolders() {
        val files = pluginsDir.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { dir -> File(dir, "plugin.yaml").takeIf { it.isFile }?.let { PluginFile("plugins/${dir.name}/plugin.yaml", it.readText()) } }
        val load = PluginCatalog.load(files)
        assertTrue(load.errors.isEmpty(), load.errors.joinToString("\n"))
        assertTrue(load.catalog.plugin("myapp") == null, "the template must never be bundled")
        assertTrue(load.catalog.plugin("spotify") != null)
    }
}
