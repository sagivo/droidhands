package dev.phonebridge.core.plugins

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginCatalogTest {
    private fun yaml(id: String, packages: String, vararg actions: String) = buildString {
        append("id: $id\nname: ${id.replaceFirstChar { it.uppercase() }}\ndescription: Test.\ncategory: other\n")
        append("android:\n  packages: [$packages]\nactions:\n")
        for (a in actions) {
            append("  - id: $a\n    title: Do $a\n    description: Does $a.\n    risk: action\n    android: {url: \"https://x/$a\"}\n")
        }
    }

    private fun file(id: String, packages: String, vararg actions: String, folder: String = id) =
        PluginFile("plugins/$folder/plugin.yaml", yaml(id, packages, *actions))

    private fun plugin(id: String, packages: String, vararg actions: String, source: PluginSource = PluginSource.BUNDLED) =
        PluginLoader.load(yaml(id, packages, *actions), source = source).plugin!!

    @Test
    fun filesKnowTheirFolder() {
        assertEquals("spotify", PluginFile("plugins/spotify/plugin.yaml", "").folder)
        assertEquals("spotify", PluginFile("C:\\repo\\plugins\\spotify\\plugin.yaml", "").folder)
        assertNull(PluginFile("plugin.yaml", "").folder)
    }

    @Test
    fun loadsByIdAndPackageAndSkipsUnderscoreFolders() {
        val load = PluginCatalog.load(
            listOf(
                file("spotify", "com.spotify.music", "play", "search"),
                file("maps", "com.google.android.apps.maps", "navigate"),
                file("myapp", "com.example.myapp", "search", folder = "_template"),
            ),
        )
        assertEquals(emptyList(), load.errors)
        val catalog = load.catalog
        assertEquals(listOf("maps", "spotify"), catalog.plugins.map { it.id })
        assertEquals("spotify", catalog.pluginsFor("com.spotify.music").single().id)
        assertEquals(emptyList(), catalog.pluginsFor("com.example.myapp"))
        assertNull(catalog.plugin("myapp"))
        assertEquals(setOf("com.spotify.music", "com.google.android.apps.maps"), catalog.packages)
    }

    @Test
    fun invalidFilesAndClashesAreReportedAndLeftOut() {
        val load = PluginCatalog.load(
            listOf(
                file("whatsapp", "com.whatsapp, com.whatsapp.w4b", "msg", "open_chat"),
                file("wa_extra", "com.whatsapp.w4b", "status", "msg"),
                file("wa_more", "com.whatsapp", "status"),
                file("broken", "com.broken", "x").copy(text = "id: broken\n"),
                file("wrongdir", "com.x", "a", folder = "other"),
            ),
        )
        // Files load in path order, so wa_extra claims com.whatsapp.w4b's "msg" first.
        assertEquals(listOf("wa_extra", "wa_more"), load.catalog.plugins.map { it.id })
        assertTrue(load.errors.any { "whatsapp: actions msg clash with plugin 'wa_extra' for com.whatsapp.w4b" in it }, load.errors.toString())
        assertTrue(load.errors.any { it.startsWith("broken: name: required") }, load.errors.toString())
        assertTrue(load.errors.any { "must equal the folder name 'other'" in it }, load.errors.toString())
        // Two plugins may share a package when their action ids differ.
        val shared = PluginCatalog.load(listOf(file("whatsapp", "com.whatsapp", "msg"), file("wa_more", "com.whatsapp", "status")))
        assertEquals(emptyList(), shared.errors)
        assertEquals(listOf("wa_more", "whatsapp"), shared.catalog.pluginsFor("com.whatsapp").map { it.id })
    }

    @Test
    fun duplicateIdsAreReported() {
        val load = PluginCatalog.load(listOf(file("spotify", "com.a", "x"), PluginFile("plugins/spotify/../spotify/plugin.yaml", yaml("spotify", "com.b", "y"))))
        assertEquals(1, load.catalog.plugins.size)
        assertTrue(load.errors.single().contains("a plugin with id 'spotify' is already loaded"), load.errors.toString())
    }

    @Test
    fun localPluginsReplaceBundledOnesWithTheSameIdAndAreAlwaysLocal() {
        val bundled = PluginCatalog.load(listOf(file("spotify", "com.spotify.music", "play"), file("maps", "com.maps", "navigate"))).catalog
        val local = plugin("spotify", "com.spotify.music", "play", "radio")
        val merged = bundled.merge(listOf(local))
        assertEquals(emptyList(), merged.errors)
        val spotify = merged.catalog.plugin("spotify")!!
        assertEquals(PluginSource.LOCAL, spotify.source)
        assertEquals(listOf("play", "radio"), spotify.actions.map { it.id })
        assertEquals(listOf("spotify"), merged.catalog.pluginsFor("com.spotify.music").map { it.id })
        // The bundled catalog itself is unchanged.
        assertEquals(PluginSource.BUNDLED, bundled.plugin("spotify")!!.source)
    }

    @Test
    fun aLocalPluginWhoseActionsClashIsAnImportError() {
        val bundled = PluginCatalog.load(listOf(file("spotify", "com.spotify.music", "play"))).catalog
        val clash = plugin("my_spotify", "com.spotify.music", "play", source = PluginSource.LOCAL)
        assertEquals(listOf("my_spotify: actions play clash with plugin 'spotify' for com.spotify.music; rename them or merge the plugins"), bundled.conflicts(clash))
        val merged = bundled.merge(listOf(clash, plugin("extra", "com.spotify.music", "radio")))
        assertEquals(1, merged.errors.size)
        assertEquals(listOf("spotify", "extra"), merged.catalog.plugins.map { it.id })
        assertFailsWith<IllegalArgumentException> { bundled.with(clash) }
    }

    @Test
    fun withAddsOrReplaces() {
        val a = plugin("alpha", "com.a", "x")
        val catalog = PluginCatalog.EMPTY.with(a)
        assertSame(a, catalog.plugin("alpha"))
        val a2 = plugin("alpha", "com.a2", "y")
        val replaced = catalog.with(a2)
        assertEquals(listOf(a2), replaced.plugins)
        assertEquals(emptyList(), replaced.pluginsFor("com.a"))
    }
}
