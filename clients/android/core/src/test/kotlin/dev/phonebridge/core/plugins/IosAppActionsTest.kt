package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.DispatchTool
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.ToolContent
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolNames
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [AppActionCatalog] and the app-action tools on a platform that says it is iOS: `ios:` recipes, bundle ids, `declares`. */
class IosAppActionsTest {
    private val spotify = InstalledApp("com.spotify.client", "Spotify", "9.0")
    private val notes = InstalledApp("com.apple.mobilenotes", "Notes")

    private inner class FakeIosPlatform : AppActionsPlatform {
        override val os = DeviceOs.IOS
        var apps = listOf(spotify, notes)
        val discovered = HashMap<String, List<PluginAction>>()
        val declared = HashMap<String, JsonObject>()
        val executed: MutableList<ActionRequest> = Collections.synchronizedList(mutableListOf())

        override suspend fun installedApps() = apps

        override suspend fun resolveApp(query: String, access: AppAccess): AppResolution =
            apps.firstOrNull { it.label.equals(query, ignoreCase = true) }?.let { AppResolution.Found(it) } ?: AppResolution.NotFound()

        override suspend fun discoveredActions(packageName: String) = discovered[packageName].orEmpty()

        override suspend fun declaredCapabilities(packageName: String): JsonObject? = declared[packageName]

        override fun unavailableReason(packageName: String, action: PluginAction): String? = null

        override suspend fun execute(request: ActionRequest, ctx: CallContext): ActionResult {
            executed += request
            return ActionResult()
        }
    }

    private val platform = FakeIosPlatform()

    private val spotifyYaml = """
        id: spotify
        name: Spotify
        description: Music.
        category: music
        android:
          packages: [com.spotify.music]
        ios:
          bundle_ids: [com.spotify.client]
        actions:
          - id: play
            title: Play music
            description: Android only.
            risk: action
            params:
              query: {description: What to play, required: true}
            android:
              intent: {action: android.media.action.MEDIA_PLAY_FROM_SEARCH, extras: {query: "{{query}}"}}
          - id: search
            title: Search
            description: Opens search results.
            risk: action
            params:
              query: {description: Search text, required: true}
            android:
              url: "spotify:search:{{query}}"
            ios:
              url: "spotify:search:{{query}}"
          - id: radio
            title: Start a radio
            description: iOS only.
            risk: action
            ios:
              steps:
                - launch: true
                - tap: {id: RadioButton}
          - id: open_web
            title: Open on the web
            description: Opens a web page with url_open.
            params:
              path: {description: Path, required: true}
            ios:
              tool:
                name: url_open
                args: {url: "https://open.spotify.com/{{path}}"}
    """.trimIndent()

    private val plugins = PluginCatalog.EMPTY.with(PluginLoader.load(spotifyYaml).let { it.plugin ?: error(it.errors.joinToString("\n")) })
    private val registry = ToolRegistry()
    private val catalog = AppActionCatalog({ plugins }, platform, registry::get)

    init {
        registry.register(tool(name = "url_open", title = "Open a link", description = "d", scope = Scopes.APPS, risk = Risk.ACTION) { _, _ -> ToolResult.text("opened") })
        platform.discovered["com.spotify.client"] = listOf(
            PluginAction("open", "Open", "Opens the app.", risk = Risk.ACTION, ios = StepsRecipe(listOf(Step(StepAction.Launch())))),
            // Recipes for the other OS, and intents under ios:, are never offered on iOS.
            PluginAction("share_text", "Share", "Android only.", risk = Risk.ACTION, android = IntentRecipe("android.intent.action.SEND")),
            PluginAction("sneaky", "Sneaky", "An intent on iOS.", risk = Risk.ACTION, ios = IntentRecipe("android.intent.action.VIEW")),
            PluginAction(
                "open_link", "Open a link", "Opens links the app declares.", params = listOf(ParamSpec("url", "Link", required = true)),
                ios = ToolRecipe("url_open", linkedMapOf("url" to ValueSpec.Template("{{url}}"), "package" to ValueSpec.Template("com.spotify.client"))),
            ),
        )
        platform.declared["com.spotify.client"] = buildJsonObject {
            putJsonArray("app_intents") { add(buildJsonObject { put("id", "PlayPlaylistIntent"); put("title", "Play a playlist") }) }
        }
        platform.declared["com.apple.mobilenotes"] = JsonObject(emptyMap())
    }

    private val ctx = CallContext("t", "Claude", Scopes.ALL, "local", "r") { }

    private fun ToolResult.texts() = content.map { (it as ToolContent.Text).text }

    private fun ToolResult.json() = Json.parseToJsonElement(texts()[0]).jsonObject

    private suspend fun list(app: String): ToolResult =
        appActionTools(catalog).first { it.name == ToolNames.APP_ACTIONS_LIST }.call(buildJsonObject { put("app", app) }, ctx)

    @Test
    fun pluginsApplyByBundleIdAndOnlyIosRecipesAreOffered() = runTest {
        val actions = catalog.actions(spotify)
        assertEquals(listOf("search", "radio", "open_web", "open", "open_link"), actions.map { it.action.id })
        assertTrue(actions.all { it.os == DeviceOs.IOS })
        assertEquals(UrlRecipe("spotify:search:{{query}}"), actions.first().recipe)
        assertEquals(listOf("spotify"), catalog.applicablePlugins("com.spotify.client", setOf("com.spotify.client")).map { it.id })
        // The Android package means nothing on iOS.
        assertEquals(emptyList(), catalog.applicablePlugins("com.spotify.music", setOf("com.spotify.music")))
        assertEquals(Integration.BASIC, catalog.integration(catalog.applicablePlugins(notes.packageName, setOf(notes.packageName))))
    }

    @Test
    fun listingShowsIosCompletesAndDeclares() = runTest {
        val result = list("Spotify")
        val json = result.json()
        assertEquals("plugin", json["integration"]!!.jsonPrimitive.content)
        val actions = json["actions"]!!.jsonArray.associate { it.jsonObject["action"]!!.jsonPrimitive.content to it.jsonObject }
        assertEquals(false, actions.getValue("search")["completes"]!!.jsonPrimitive.boolean)
        assertEquals(true, actions.getValue("radio")["completes"]!!.jsonPrimitive.boolean)
        assertEquals(
            buildJsonObject { put("app_intents", buildJsonArray { add(buildJsonObject { put("id", "PlayPlaylistIntent"); put("title", "Play a playlist") }) }) },
            json["declares"],
        )
        assertTrue(result.texts().drop(1).any { "'declares'" in it && "phone use" in it }, result.texts().toString())
        // Nothing declared (empty or null): no key and no footer.
        val notesResult = list("Notes")
        assertNull(notesResult.json()["declares"])
        assertTrue(notesResult.texts().none { "'declares'" in it }, notesResult.texts().toString())
    }

    @Test
    fun aFailingDeclarationReadKeepsTheActions() = runTest {
        val failing = object : AppActionsPlatform by platform {
            override suspend fun declaredCapabilities(packageName: String): JsonObject? = throw IllegalStateException("boom")
        }
        val json = appActionTools(AppActionCatalog({ plugins }, failing, registry::get)).first().call(buildJsonObject { put("app", "Spotify") }, ctx).json()
        assertNull(json["declares"])
        assertEquals(5, json["actions"]!!.jsonArray.size)
    }

    @Test
    fun runningRendersTheIosRecipeForTheBundleId() = runTest {
        val run = appActionTools(catalog).first { it.name == ToolNames.APP_ACTION_RUN } as DispatchTool
        val search = run.resolve(buildJsonObject { put("app", "Spotify"); put("action", "search"); put("params", buildJsonObject { put("query", "daft punk") }) }, ctx)
        val json = Json.parseToJsonElement((search.tool.call(search.args, ctx).content[0] as ToolContent.Text).text).jsonObject
        assertEquals(RenderedUrl("spotify:search:daft%20punk", "com.spotify.client"), platform.executed.single().recipe)
        assertEquals(false, json["done"]!!.jsonPrimitive.boolean)

        val radio = run.resolve(buildJsonObject { put("app", "Spotify"); put("action", "radio") }, ctx)
        assertEquals(PluginRules.STEPS_TIMEOUT_MS, radio.tool.timeoutMs)
        radio.tool.call(radio.args, ctx)
        assertEquals(
            RenderedSteps("com.spotify.client", listOf(RenderedStep(RenderedStepAction.Launch()), RenderedStep(RenderedStepAction.Tap(Selector(id = "RadioButton"))))),
            platform.executed.last().recipe,
        )

        // A tool recipe runs the built-in tool with the rendered arguments.
        val web = run.resolve(buildJsonObject { put("app", "Spotify"); put("action", "open_web"); put("params", buildJsonObject { put("path", "artist/1") }) }, ctx)
        assertEquals("url_open", web.tool.name)
        assertEquals(buildJsonObject { put("url", "https://open.spotify.com/artist/1") }, web.args)

        // An Android-only action does not exist on iOS.
        val e = assertFailsWith<ToolException> { run.resolve(buildJsonObject { put("app", "Spotify"); put("action", "play") }, ctx) }
        assertTrue("has no action 'play'" in e.message!!, e.message)
    }

    @Test
    fun androidPlatformsKeepUsingAndroidRecipes() = runTest {
        val android = object : AppActionsPlatform by platform {
            override val os = DeviceOs.ANDROID
        }
        val c = AppActionCatalog({ plugins }, android, registry::get)
        val app = InstalledApp("com.spotify.music", "Spotify")
        platform.apps = listOf(app)
        platform.discovered[app.packageName] = platform.discovered.getValue(spotify.packageName)
        assertEquals(listOf("play", "search", "share_text"), c.actions(app).map { it.action.id })
    }
}
