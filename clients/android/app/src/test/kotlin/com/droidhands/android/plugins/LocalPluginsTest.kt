package com.droidhands.android.plugins

import com.droidhands.core.plugins.PluginCatalog
import com.droidhands.core.plugins.PluginFile
import com.droidhands.core.plugins.PluginSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/** Local plugins (docs/PLUGINS.md §10): storage, validation on import and load, and the merged catalog. */
class LocalPluginsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun plugin(id: String, pkg: String = "com.example.app", actionId: String = "search", name: String = "Example") = """
        id: $id
        name: $name
        description: A test plugin.
        category: other
        android:
          packages: [$pkg]
        actions:
          - id: $actionId
            title: Search
            description: Opens search results.
            risk: action
            params:
              query: {description: Search text, required: true}
            android:
              url: "https://example.com/search?q={{query}}"
            examples:
              - params: {query: a b}
                expect: {url: "https://example.com/search?q=a%20b"}
    """.trimIndent()

    private val bundledSpotify = PluginFile("plugins/spotify/plugin.yaml", plugin("spotify", pkg = "com.spotify.music", actionId = "play", name = "Spotify"))

    private fun bundled(vararg files: PluginFile): PluginCatalog = PluginCatalog.load(files.toList(), PluginSource.BUNDLED, runExamples = false).catalog

    private fun dir(): File = File(temp.root, LocalPlugins.DIR)

    @Test
    fun importValidatesAndSavesUnderTheId() {
        val local = LocalPlugins(dir())
        val result = local.import(plugin("myapp"), "plugin.yaml", PluginCatalog.EMPTY)
        assertTrue(result is ImportResult.Imported)
        result as ImportResult.Imported
        assertEquals("myapp", result.plugin.id)
        assertEquals(PluginSource.LOCAL, result.plugin.source)
        assertFalse(result.replaced)
        assertTrue(File(dir(), "myapp.yaml").isFile)
        assertEquals(listOf("myapp"), local.entries().map { it.id })
        assertTrue(local.entries().single().isValid)

        val again = local.import(plugin("myapp", name = "Renamed"), "plugin.yaml", PluginCatalog.EMPTY) as ImportResult.Imported
        assertTrue(again.replaced)
        assertEquals("Renamed", local.entries().single().plugin?.name)
        assertFalse("no temporary files are left", dir().listFiles()!!.any { it.name.startsWith(".") })
    }

    @Test
    fun importRefusesInvalidFilesWithTheValidatorsMessages() {
        val local = LocalPlugins(dir())
        val broken = local.import(plugin("myapp").replace("risk: action", "risk: read").replace("title: Search", "title: Send an order"), "x.yaml", PluginCatalog.EMPTY)
        assertTrue(broken is ImportResult.Rejected)
        assertTrue((broken as ImportResult.Rejected).errors.joinToString(), broken.errors.any { it.contains("must be sensitive") })
        val notYaml = local.import("{{{", "notes.yaml", PluginCatalog.EMPTY) as ImportResult.Rejected
        assertTrue(notYaml.errors.single(), notYaml.errors.single().startsWith("notes.yaml:"))
        val bridge = local.import(plugin("evil", pkg = "com.droidhands.android"), null, PluginCatalog.EMPTY) as ImportResult.Rejected
        assertTrue(bridge.errors.joinToString(), bridge.errors.any { it.contains("can't target DroidHands itself") })
        val failingExample = local.import(plugin("myapp").replace("q=a%20b", "q=a+b"), null, PluginCatalog.EMPTY)
        assertTrue("examples run on import", failingExample is ImportResult.Rejected)
        assertFalse(dir().exists() && dir().listFiles()!!.isNotEmpty())
    }

    @Test
    fun importMayReplaceABundledPluginButNotClashWithAnother() {
        val local = LocalPlugins(dir())
        val catalog = bundled(bundledSpotify)
        assertTrue(local.import(plugin("spotify", pkg = "com.spotify.music", actionId = "play"), null, catalog) is ImportResult.Imported)
        val clash = local.import(plugin("spotify_extra", pkg = "com.spotify.music", actionId = "play"), null, catalog) as ImportResult.Rejected
        assertTrue(clash.errors.single(), clash.errors.single().contains("clash with plugin 'spotify'"))
        assertTrue(local.import(plugin("spotify_extra", pkg = "com.spotify.music", actionId = "queue"), null, catalog) is ImportResult.Imported)
        val clashLocal = local.import(plugin("third", pkg = "com.spotify.music", actionId = "queue"), null, catalog) as ImportResult.Rejected
        assertTrue(clashLocal.errors.single().contains("spotify_extra"))
    }

    @Test
    fun pushedFilesAreValidatedWhenLoaded() {
        val local = LocalPlugins(dir())
        dir().mkdirs()
        File(dir(), "good.yaml").writeText(plugin("good"))
        File(dir(), "wrong_name.yaml").writeText(plugin("other"))
        File(dir(), "bad.yaml").writeText("id: bad\n")
        File(dir(), "notes.txt").writeText("ignored")
        val entries = local.entries()
        assertEquals(listOf("bad", "good", "wrong_name"), entries.map { it.id })
        assertTrue(entries[1].isValid)
        assertNull(entries[0].plugin)
        assertTrue(entries[0].errors.isNotEmpty())
        assertTrue(entries[2].errors.single(), entries[2].errors.single().contains("must be named after the plugin id: other.yaml"))
    }

    @Test
    fun signatureChangesWhenFilesChange() {
        val local = LocalPlugins(dir())
        val none = local.signature()
        dir().mkdirs()
        val empty = local.signature()
        assertNotEquals(none, empty)
        val file = File(dir(), "good.yaml").apply { writeText(plugin("good")) }
        val one = local.signature()
        assertNotEquals(empty, one)
        // A re-push rewrites the file in place (the folder's own time may not change).
        file.writeText(plugin("good", name = "Good, longer name"))
        file.setLastModified(file.lastModified() + 2_000)
        assertNotEquals(one, local.signature())
        assertTrue(local.remove("good"))
        assertFalse(local.remove("good"))
        assertFalse(local.remove("../escape"))
        assertEquals(empty, local.signature())
    }

    @Test
    fun repositoryMergesLocalPluginsOnlyWhenEnabled() {
        var enabled = false
        val logged = ArrayList<String>()
        var bundledReads = 0
        val local = LocalPlugins(dir())
        val repo = PluginRepository(
            bundledFiles = {
                bundledReads++
                listOf(bundledSpotify, PluginFile("plugins/broken/plugin.yaml", "id: broken\n"))
            },
            local = local,
            localEnabled = { enabled },
            log = { logged += it },
        )
        assertEquals(listOf("spotify"), repo.catalog().plugins.map { it.id })
        assertTrue("a bad bundled file is skipped and logged", logged.any { it.startsWith("Bundled plugin skipped: broken:") })

        dir().mkdirs()
        File(dir(), "spotify.yaml").writeText(plugin("spotify", pkg = "com.spotify.music", actionId = "play", name = "Spotify (dev)"))
        assertEquals("Spotify", repo.catalog().plugin("spotify")?.name)

        enabled = true
        val withLocal = repo.catalog()
        assertEquals("Spotify (dev)", withLocal.plugin("spotify")?.name)
        assertEquals(PluginSource.LOCAL, withLocal.plugin("spotify")?.source)
        assertTrue("cached while nothing changed", repo.catalog() === withLocal)

        File(dir(), "extra.yaml").writeText(plugin("extra", pkg = "com.example.extra"))
        assertEquals(listOf("spotify", "extra"), repo.catalog().plugins.map { it.id })
        File(dir(), "clash.yaml").writeText(plugin("clash", pkg = "com.spotify.music", actionId = "play"))
        val entries = repo.localEntries()
        val clash = entries.single { it.id == "clash" }
        assertNull("left out of the catalog", clash.plugin)
        assertTrue(clash.errors.single(), clash.errors.single().contains("clash with plugin 'spotify'"))
        assertNull(repo.catalog().plugin("clash"))

        enabled = false
        assertEquals("Spotify", repo.catalog().plugin("spotify")?.name)
        assertEquals(1, bundledReads)
    }

    @Test
    fun bundledAssetNamesMapToRepositoryPaths() {
        assertEquals("plugins/spotify/plugin.yaml", BundledPlugins.repositoryPath("spotify.yaml"))
        assertEquals("spotify", PluginFile(BundledPlugins.repositoryPath("spotify.yaml"), "").folder)
    }

    @Test
    fun importHelpers() {
        assertNull(PluginImports.nameProblem("plugin.yaml"))
        assertNull(PluginImports.nameProblem("Spotify.YML"))
        assertNull(PluginImports.nameProblem(null))
        assertTrue(PluginImports.nameProblem("photo.jpg")!!.contains("plugin.yaml"))
        assertEquals("héllo", PluginImports.readText(ByteArrayInputStream("héllo".toByteArray()), 100))
        val big = ByteArray(10_000) { 'a'.code.toByte() }
        assertEquals(10_000, PluginImports.readText(ByteArrayInputStream(big), 10_000).length)
        try {
            PluginImports.readText(ByteArrayInputStream(big), 9_999)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("larger than"))
        }
    }
}
