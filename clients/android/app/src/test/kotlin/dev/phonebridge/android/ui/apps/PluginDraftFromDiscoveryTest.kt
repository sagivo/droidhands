package dev.phonebridge.android.ui.apps

import dev.phonebridge.android.tools.actions.DeclaredCapability
import dev.phonebridge.android.tools.actions.DeclaredIntent
import dev.phonebridge.android.tools.actions.DeclaredParameter
import dev.phonebridge.android.tools.actions.DeclaredShortcut
import dev.phonebridge.android.tools.actions.DiscoveredActions
import dev.phonebridge.android.tools.actions.IntentHandler
import dev.phonebridge.android.tools.actions.ShortcutsDeclaration
import dev.phonebridge.android.tools.actions.StandardHandlers
import dev.phonebridge.core.plugins.PluginDraft
import dev.phonebridge.core.plugins.PluginLoader
import dev.phonebridge.core.plugins.PluginSource
import dev.phonebridge.core.tools.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Create plugin draft" end to end without a phone: what the scanner builds for an app ([DiscoveredActions])
 * becomes a draft ([PluginDraft]) that the core validator accepts, examples included, before and after a
 * contributor fills in the TODOs.
 */
class PluginDraftFromDiscoveryTest {
    private val pkg = "com.example.music"
    private val exported = setOf("$pkg.Share", "$pkg.Play", "$pkg.NewPlaylist", "$pkg.Order", "$pkg.Search")

    private fun discovered() = DiscoveredActions.build(
        appLabel = "Example Music",
        packageName = pkg,
        launcherClass = "$pkg.Main",
        handlers = StandardHandlers(
            share = IntentHandler("$pkg.Share"),
            processText = IntentHandler("org.lib.Translate", "Translate"),
            playFromSearch = IntentHandler("$pkg.Play"),
            webSearch = IntentHandler("$pkg.Search"),
            geo = IntentHandler("$pkg.Map"),
        ),
        declared = ShortcutsDeclaration(
            shortcuts = listOf(
                DeclaredShortcut("newPlaylist", shortLabel = "New playlist", intent = DeclaredIntent("android.intent.action.VIEW", pkg, "$pkg.NewPlaylist", "examplemusic://playlist/new")),
                DeclaredShortcut("orderMerch", shortLabel = "Order merch", intent = DeclaredIntent("android.intent.action.VIEW", pkg, "$pkg.Order")),
            ),
            capabilities = listOf(
                DeclaredCapability(
                    "actions.intent.GET_THING",
                    listOf(
                        DeclaredIntent(
                            action = "android.intent.action.VIEW",
                            targetPackage = pkg,
                            targetClass = "$pkg.Search",
                            urlTemplate = "https://music.example.com/search{?q}",
                            parameters = listOf(DeclaredParameter("thing.name", "q", required = true)),
                        ),
                    ),
                ),
            ),
        ),
        isExported = { it in exported },
    )

    @Test
    fun aDraftFromDiscoveredActionsIsAValidPlugin() {
        val actions = discovered()
        val yaml = PluginDraft.yaml(PluginDraft.Input("Example Music", pkg, actions, AppsPresentation.draftCategory(-1, actions.map { it.id }), "2.1"))
        val result = PluginLoader.load(yaml, PluginSource.BUNDLED, folder = "example_music")
        assertTrue(result.errors.joinToString("\n") + "\n" + yaml, result.isValid)
        val plugin = result.plugin!!
        assertEquals("music", plugin.category)
        assertEquals(
            listOf("share_text", "process_text", "play_from_search", "web_search", "show_on_map", "get_thing", "shortcut_new_playlist", "shortcut_order_merch"),
            plugin.actions.map { it.id },
        )
        assertEquals(Risk.SENSITIVE, plugin.actions.first { it.id == "shortcut_order_merch" }.risk)
        assertTrue("examples were generated and passed", plugin.actions.all { it.examples.isNotEmpty() })
        // Also fine as a local plugin imported on the phone (no folder to match).
        assertTrue(PluginLoader.load(yaml, PluginSource.LOCAL).isValid)
    }

    @Test
    fun filledInItIsStillValidAndHasNoTodosLeft() {
        val yaml = PluginDraft.yaml(PluginDraft.Input("Example Music", pkg, discovered(), "music", "2.1"))
        val filled = yaml.lines().mapNotNull { line ->
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("#") && "TODO" in line -> null
                "\"TODO: " in line -> line.replace(Regex("\"TODO: [^\"]*\""), "\"Streams music and podcasts.\"")
                "# TODO" in line -> line.substringBefore("# TODO").trimEnd()
                else -> line
            }
        }.joinToString("\n")
        assertFalse(filled, "TODO" in filled)
        val result = PluginLoader.load(filled, PluginSource.BUNDLED, folder = "example_music")
        assertTrue(result.errors.joinToString("\n"), result.isValid)
        assertEquals("Streams music and podcasts.", result.plugin!!.description)
    }
}
