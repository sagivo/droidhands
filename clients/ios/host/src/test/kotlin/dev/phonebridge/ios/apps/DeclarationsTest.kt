package dev.phonebridge.ios.apps

import dev.phonebridge.core.plugins.ParamValues
import dev.phonebridge.core.plugins.RecipeRenderer
import dev.phonebridge.core.plugins.RenderedToolCall
import dev.phonebridge.core.plugins.Step
import dev.phonebridge.core.plugins.StepAction
import dev.phonebridge.core.plugins.StepsRecipe
import dev.phonebridge.core.plugins.ToolRecipe
import dev.phonebridge.core.plugins.ValueSpec
import dev.phonebridge.ios.Fixtures
import dev.phonebridge.ios.app
import dev.phonebridge.ios.inventory.InventoryLimits
import dev.phonebridge.ios.inventory.IosApp
import dev.phonebridge.ios.inventory.Plist
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeclarationsTest {
    private val spotify = IosApp(
        bundleId = "com.spotify.client",
        name = "Spotify",
        version = "8.9.0",
        build = "8.9.0.575",
        infoPlist = Fixtures.json("spotify-info.json"),
        entitlements = Plist.parseXml(Fixtures.bytes("spotify-entitlements.xml")) as JsonObject,
    )

    @Test
    fun `link forms come from CFBundleURLTypes and applinks domains`() {
        assertEquals(listOf("spotify", "spotify-action", "fb1234567890"), IosLinks.urlSchemes(spotify))
        assertEquals(listOf("open.spotify.com", "*.spotify.link"), IosLinks.appLinkDomains(spotify))
    }

    @Test
    fun `open is a launch screen recipe for iOS only`() {
        val open = IosDeclarations.actions(spotify).first()
        assertEquals("open", open.id)
        assertEquals(StepsRecipe(listOf(Step(StepAction.Launch()))), open.ios)
        assertNull(open.android)
        assertTrue(open.completesTask)
    }

    @Test
    fun `open_link is url_open pinned to the app, in the only shape the catalog accepts`() {
        val link = IosDeclarations.actions(spotify).single { it.id == "open_link" }
        val recipe = link.ios as ToolRecipe
        assertEquals("url_open", recipe.name)
        assertEquals(setOf("url", "package"), recipe.args.keys)
        assertEquals(ValueSpec.Template("com.spotify.client"), recipe.args["package"])
        assertEquals(ValueSpec.Template("{{url}}"), recipe.args["url"])
        assertNull(link.risk)
        assertNull(link.android)
        assertTrue("spotify:*" in link.description, link.description)
        assertTrue("https://open.spotify.com/*" in link.description, link.description)
        assertTrue("https://*.spotify.link/*" in link.description, link.description)
        assertEquals(listOf("url"), link.params.map { it.name })

        val rendered = RecipeRenderer("com.spotify.client").render(recipe, ParamValues.validate(link.params, buildJsonObject { put("url", "spotify:album:1") }))
        rendered as RenderedToolCall
        assertEquals("com.spotify.client", rendered.args["package"]!!.jsonPrimitive.content)
        assertEquals("spotify:album:1", rendered.args["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no links declared, no open_link`() {
        assertEquals(listOf("open"), IosDeclarations.actions(app("com.example.calc", "Calc")).map { it.id })
    }

    @Test
    fun `App Intents metadata - titles, parameters, phrases with the app's name, hidden intents skipped`() {
        val intents = IosDeclarations.appIntents(Fixtures.bytes("extract.actionsdata"), "Spotify")
        val play = intents.single()
        assertEquals("PlayPlaylistIntent", play["id"]!!.jsonPrimitive.content)
        assertEquals("Play Playlist", play["title"]!!.jsonPrimitive.content)
        assertEquals("Plays one of your playlists", play["description"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("Play \${playlist} in Spotify", "Start Spotify playlist"),
            play["phrases"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val params = play["params"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("playlist", "shuffle"), params.map { it["name"]!!.jsonPrimitive.content })
        assertEquals(JsonPrimitive(true), params[1]["optional"])
        assertNull(params[0]["optional"])
    }

    @Test
    fun `App Intents metadata in other shapes or broken is read defensively`() {
        val array = """{"actions":[{"identifier":"A","title":"Do A"}],"autoShortcuts":[{"actionIdentifier":"A","phraseTemplates":["A with ${'$'}{applicationName}"]}]}"""
        val a = IosDeclarations.appIntents(array.toByteArray(), "App").single()
        assertEquals("A with App", a["phrases"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(emptyList(), IosDeclarations.appIntents("not json".toByteArray(), "App"))
        assertEquals(emptyList(), IosDeclarations.appIntents("""{"actions": 3}""".toByteArray(), "App"))
    }

    @Test
    fun `declares - intents, quick actions (localized), document types, with how`() {
        val strings = mapOf("SHORTCUT_SEARCH" to "Search")
        val declared = IosDeclarations.declared(spotify, InventoryLimits(), listOf(Fixtures.bytes("extract.actionsdata"))) { strings[it] ?: it }
        assertNotNull(declared)
        val quick = declared["quick_actions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Search", "Liked Songs"), quick.map { it["title"]!!.jsonPrimitive.content })
        assertEquals("com.spotify.search", quick[0]["type"]!!.jsonPrimitive.content)
        assertEquals("Your favourites", quick[1]["subtitle"]!!.jsonPrimitive.content)
        val docs = declared["document_types"]!!.jsonArray.single().jsonObject
        assertEquals("Audio", docs["name"]!!.jsonPrimitive.content)
        assertEquals("viewer", docs["role"]!!.jsonPrimitive.content)
        assertEquals(1, declared["app_intents"]!!.jsonArray.size)
        val how = declared["how"]!!.jsonArray.map { it.jsonObject["kind"]!!.jsonPrimitive.content }
        assertEquals(listOf("app_intents", "quick_actions", "document_types"), how)
        assertTrue("Siri" in declared["how"]!!.jsonArray[0].jsonObject["how"]!!.jsonPrimitive.content)
        assertNull(declared["unreadable"])
    }

    @Test
    fun `declares says what the inventory can't read, once per kind`() {
        val limits = InventoryLimits(infoPlist = "only names", entitlements = "only names", bundleFiles = "not on device")
        val declared = IosDeclarations.declared(app("com.example.a", "A"), limits)!!
        val unreadable = declared["unreadable"] as JsonArray
        assertEquals(listOf("links, quick_actions, document_types", "app_intents"), unreadable.map { it.jsonObject["what"]!!.jsonPrimitive.content })
        assertNull(IosDeclarations.declared(app("com.example.a", "A"), InventoryLimits()))
    }
}
