package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.Risk
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginDraftTest {
    private val pkg = "com.example.music"

    private fun template(name: String) = ExtraSpec(ValueSpec.Template("{{$name}}"))

    // Shaped like the app's DiscoveredActions output (docs/PLUGINS.md §8).
    private val open = PluginAction(
        id = "open", title = "Open the app", description = "Opens Example Music on its main screen.", risk = Risk.ACTION,
        completes = true,
        android = IntentRecipe(action = "android.intent.action.MAIN", categories = listOf("android.intent.category.LAUNCHER"), className = "$pkg.MainActivity"),
    )
    private val share = PluginAction(
        id = "share_text", title = "Share text", description = "Opens Example Music's share screen with the text filled in.",
        keywords = listOf("share", "text", "send to", "post"), risk = Risk.ACTION,
        params = listOf(ParamSpec("text", "The text to share", required = true, maxLength = 5000)), completes = false,
        android = IntentRecipe(
            action = "android.intent.action.SEND", type = "text/plain", className = "$pkg.share.ShareActivity",
            extras = linkedMapOf("android.intent.extra.TEXT" to template("text")),
        ),
    )
    private val play = PluginAction(
        id = "play_from_search", title = "Play music", description = "Asks Example Music to find and play what the query names.",
        risk = Risk.ACTION, params = listOf(ParamSpec("query", "What to play", required = true, maxLength = 500)), completes = true,
        android = IntentRecipe(
            action = "android.media.action.MEDIA_PLAY_FROM_SEARCH", className = "$pkg.PlayActivity",
            extras = linkedMapOf(
                "query" to template("query"),
                "android.intent.extra.focus" to ExtraSpec(ValueSpec.Literal(JsonPrimitive("vnd.android.cursor.item/*"))),
            ),
        ),
    )
    private val processText = PluginAction(
        id = "process_text", title = "Translate text", description = "Hands the text to Example Music's text action \"Translate\".",
        risk = Risk.ACTION, params = listOf(ParamSpec("text", "The text to process", required = true, maxLength = 5000)), completes = false,
        android = IntentRecipe(
            action = "android.intent.action.PROCESS_TEXT", type = "text/plain", className = "com.other.lib.TranslateActivity",
            extras = linkedMapOf(
                "android.intent.extra.PROCESS_TEXT" to template("text"),
                "android.intent.extra.PROCESS_TEXT_READONLY" to ExtraSpec(ValueSpec.Literal(JsonPrimitive(true))),
            ),
        ),
    )
    private val map = PluginAction(
        id = "show_on_map", title = "Show on map", description = "Shows a place in Example Music.", risk = Risk.ACTION,
        params = listOf(ParamSpec("query", "A place", required = true, maxLength = 500)), completes = false,
        android = IntentRecipe(action = "android.intent.action.VIEW", data = "geo:0,0?q={{query}}", className = "$pkg.MapActivity"),
    )
    private val shortcut = PluginAction(
        id = "shortcut_new_playlist", title = "New \"chill\" playlist: #1", description = "Opens the app's \"New playlist\"\nshortcut.",
        keywords = listOf("shortcut", "playlist"), risk = Risk.ACTION, completes = false,
        android = IntentRecipe(
            action = "android.intent.action.VIEW", data = "examplemusic://playlist/new", className = "$pkg.ShortcutActivity",
            extras = linkedMapOf("weird key: x" to ExtraSpec(ValueSpec.Template("a \"quoted\" \\ value"))),
        ),
    )
    private val sendShortcut = PluginAction(
        id = "shortcut_send_song", title = "Send a song", description = "Opens the app's \"Send a song\" shortcut.",
        risk = Risk.ACTION, completes = false,
        android = IntentRecipe(action = "android.intent.action.VIEW", data = "examplemusic://send", className = "$pkg.ShortcutActivity"),
    )
    private val capability = PluginAction(
        id = "get_thing", title = "Get thing", description = "Runs Example Music's \"Get thing\" App Action.", risk = Risk.ACTION,
        params = listOf(ParamSpec("name", "Value for the thing.name value of this App Action", required = true, maxLength = 500)),
        completes = false,
        android = IntentRecipe(action = "android.intent.action.VIEW", data = "https://music.example.com/search?q={{name}}", className = "$pkg.SearchActivity"),
    )

    private fun load(yaml: String, folder: String?) = PluginLoader.load(yaml, PluginSource.BUNDLED, folder = folder, label = "draft")

    private fun assertValid(yaml: String, folder: String?): AppPlugin {
        val result = load(yaml, folder)
        assertTrue(result.isValid, "draft is invalid:\n${result.errors.joinToString("\n")}\n---\n$yaml")
        return result.plugin!!
    }

    /** What a contributor does: fill in the placeholder values and remove the TODO comments. */
    private fun fillPlaceholders(yaml: String): String = yaml.lines()
        .map { line ->
            when {
                line.trimStart().startsWith("#") && "TODO" in line && !line.trimStart().startsWith("# homepage") &&
                    !line.trimStart().startsWith("# tested") -> null
                "\"TODO: " in line -> line.replace(Regex("\"TODO: [^\"]*\""), "\"A real sentence written by the contributor.\"")
                "# TODO" in line -> line.substringBefore("# TODO").trimEnd()
                else -> line
            }
        }
        .filterNotNull()
        .joinToString("\n")

    @Test
    fun discoveredActionsBecomeAValidPluginWithPassingExamples() {
        val input = PluginDraft.Input("Example Music", pkg, listOf(open, share, play, processText, map, shortcut, sendShortcut, capability), "music", "8.9.0")
        val yaml = PluginDraft.yaml(input)
        val plugin = assertValid(yaml, folder = "example_music")
        assertEquals("example_music", plugin.id)
        assertEquals("Example Music", plugin.name)
        assertEquals("music", plugin.category)
        assertEquals(listOf(pkg), plugin.androidPackages)
        // `open` is discovered for every app anyway, so it is left out when there are other actions.
        assertEquals(
            listOf("share_text", "play_from_search", "process_text", "show_on_map", "shortcut_new_playlist", "shortcut_send_song", "get_thing"),
            plugin.actions.map { it.id },
        )
        // Every action is marked for review, and the plugin-level placeholders are there.
        assertEquals(7, Regex("(?m)^  # TODO: found from ").findAll(yaml).count())
        assertTrue("description: \"TODO: one sentence about Example Music.\"" in yaml)
        assertTrue("plugins/example_music/plugin.yaml" in yaml)
        assertTrue("version 8.9.0" in yaml)
        // Examples were generated from the rendered recipes (and passed: the loader ran them).
        val share = plugin.actions.first { it.id == "share_text" }
        assertEquals(2, share.examples.size)
        val shareIntent = share.android as IntentRecipe
        assertEquals(".share.ShareActivity", shareIntent.className)
        assertEquals(ValueSpec.Template("{{text}}"), shareIntent.extras["android.intent.extra.TEXT"]?.value)
        assertEquals(false, share.completes ?: PluginAction.completesByDefault(shareIntent))
        assertEquals(true, plugin.actions.first { it.id == "play_from_search" }.completes)
        // A class outside the package can't be named by a plugin: left out, with a note.
        assertEquals(null, (plugin.actions.first { it.id == "process_text" }.android as IntentRecipe).className)
        assertTrue("com.other.lib.TranslateActivity, which a plugin can't name" in yaml)
        // Odd characters survive quoting.
        val odd = plugin.actions.first { it.id == "shortcut_new_playlist" }
        assertEquals("New \"chill\" playlist: #1", odd.title)
        assertEquals("Opens the app's \"New playlist\" shortcut.", odd.description)
        assertEquals(ValueSpec.Template("a \"quoted\" \\ value"), (odd.android as IntentRecipe).extras["weird key: x"]?.value)
        // Risk floors (docs/PLUGINS.md §9) hold even when the discovered risk was lower.
        assertEquals(Risk.SENSITIVE, plugin.actions.first { it.id == "shortcut_send_song" }.risk)
    }

    @Test
    fun theDraftStaysValidOnceThePlaceholdersAreFilledIn() {
        val input = PluginDraft.Input("Example Music", pkg, listOf(open, share, play, processText, map, shortcut, capability), "music", "8.9.0")
        val filled = fillPlaceholders(PluginDraft.yaml(input))
        assertFalse("TODO" in filled, filled)
        val plugin = assertValid(filled, folder = "example_music")
        assertEquals("A real sentence written by the contributor.", plugin.description)
        assertEquals(6, plugin.actions.size)
    }

    @Test
    fun onlyOpenIsWrittenWhenNothingElseIsKnown() {
        val plugin = assertValid(PluginDraft.yaml(PluginDraft.Input("Calculator", "com.example.calc", listOf(open))), folder = "calculator")
        val action = plugin.actions.single()
        assertEquals("open", action.id)
        assertEquals(true, action.completes)
        assertEquals("other", plugin.category)
        assertEquals(1, action.examples.size)
    }

    @Test
    fun withNothingDiscoveredThereIsAPlaceholderAction() {
        val yaml = PluginDraft.yaml(PluginDraft.Input("Mystery", "com.example.mystery", emptyList(), category = "not a category"))
        val plugin = assertValid(yaml, folder = "mystery")
        assertEquals("search", plugin.actions.single().id)
        assertEquals("other", plugin.category)
        assertTrue("PhoneBridge found nothing to start from" in yaml)
    }

    @Test
    fun anExistingPluginKeepsItsIdAndItsActionsAreNotRepeated() {
        val existing = PluginLoader.load(
            """
            id: examplemusic
            name: Example Music
            description: Music.
            category: music
            android:
              packages: [$pkg]
            actions:
              - id: share_text
                title: Share a song
                description: Shares.
                risk: action
                android: {url: "https://music.example.com/share"}
            """.trimIndent(),
        ).plugin!!
        val yaml = PluginDraft.yaml(PluginDraft.Input("Example Music", pkg, listOf(open, share, play), existing = existing))
        val plugin = assertValid(yaml, folder = "examplemusic")
        assertEquals("examplemusic", plugin.id)
        assertEquals(listOf("play_from_search"), plugin.actions.map { it.id })
        assertTrue("PhoneBridge already has a plugin for this app: plugins/examplemusic/plugin.yaml" in yaml)
    }

    @Test
    fun atMostThirtyActionsAndTheRestAreNamed() {
        val many = (1..35).map { n ->
            sendShortcut.copy(id = "shortcut_item_$n", title = "Item $n", android = IntentRecipe(action = "android.intent.action.VIEW", data = "examplemusic://item/$n"))
        }
        val yaml = PluginDraft.yaml(PluginDraft.Input("Example Music", pkg, many))
        val plugin = assertValid(yaml, folder = "example_music")
        assertEquals(PluginRules.MAX_ACTIONS, plugin.actions.size)
        assertTrue("5 more discovered actions were left out" in yaml)
        assertTrue("shortcut_item_31, shortcut_item_32" in yaml)
    }

    @Test
    fun pluginIdsComeFromTheLabelOrThePackage() {
        assertEquals("google_maps", PluginDraft.pluginId("Google Maps", "com.google.android.apps.maps"))
        assertEquals("cafe_au_lait", PluginDraft.pluginId("Café au lait!", "com.example.cafe"))
        assertEquals("telegram_messenger", PluginDraft.pluginId("טלגרם", "org.telegram.messenger"))
        assertEquals("whatsapp", PluginDraft.pluginId("واتساب", "com.whatsapp"))
        assertEquals("password", PluginDraft.pluginId("1Password", "com.agilebits.onepassword"))
        assertEquals("myapp", PluginDraft.pluginId("", "com.x"))
        val long = PluginDraft.pluginId("A very long application name that goes on and on and on", "com.x.y")
        assertTrue(PluginRules.PLUGIN_ID.matches(long) && long.length <= 40, long)
    }

    @Test
    fun quotedEscapesWhatYamlWouldMisread() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", PluginDraft.quoted("a\"b\\c\nd\u0001"))
        assertEquals("\"שלום\"", PluginDraft.quoted("שלום"))
    }
}
