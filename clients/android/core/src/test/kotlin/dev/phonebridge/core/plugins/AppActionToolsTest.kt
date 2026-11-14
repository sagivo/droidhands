package dev.phonebridge.core.plugins

import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.audit.InMemoryAuditLog
import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.auth.AppPolicy
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.auth.InMemoryTokenStore
import dev.phonebridge.core.auth.IssuedToken
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.auth.ToolRule
import dev.phonebridge.core.confirm.ConfirmationProvider
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.confirm.ConfirmationResult
import dev.phonebridge.core.mcp.McpServer
import dev.phonebridge.core.mcp.ServerInfo
import dev.phonebridge.core.mcp.TransportRequest
import dev.phonebridge.core.policy.GlobalSecurity
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.reqString
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `app_actions_list`, `app_action_run` and [AppActionCatalog] with a fake platform, through the MCP pipeline. */
class AppActionToolsTest {
    private val spotify = InstalledApp("com.spotify.music", "Spotify", "8.9")
    private val whatsapp = InstalledApp("com.whatsapp", "WhatsApp")
    private val maps = InstalledApp("com.maps", "Maps")
    private val notes = InstalledApp("com.notes", "Notes")
    private val notesPro = InstalledApp("com.notes.pro", "Notes Pro")
    private val bank = InstalledApp("com.bank", "Bank")

    private inner class FakePlatform : AppActionsPlatform {
        var apps = listOf(spotify, whatsapp, maps, notes, notesPro, bank)
        val discovered = HashMap<String, List<PluginAction>>()
        val unavailable = HashMap<String, String>()
        val executed: MutableList<ActionRequest> = Collections.synchronizedList(mutableListOf())
        var failure: String? = null

        override suspend fun installedApps() = apps

        override suspend fun resolveApp(query: String, access: AppAccess): AppResolution {
            val exact = apps.filter { it.label.equals(query, ignoreCase = true) }
            if (exact.size == 1) return if (access.isAllowed(exact[0].packageName)) AppResolution.Found(exact[0]) else AppResolution.Blocked(exact[0])
            val partial = apps.filter { it.label.contains(query, ignoreCase = true) }
            val allowed = partial.filter { access.isAllowed(it.packageName) }
            return when {
                allowed.size > 1 -> AppResolution.Ambiguous(allowed, someBlocked = allowed.size < partial.size)
                allowed.size == 1 -> AppResolution.Found(allowed[0])
                else -> AppResolution.NotFound(apps.filter { access.isAllowed(it.packageName) }.take(1))
            }
        }

        override suspend fun discoveredActions(packageName: String) = discovered[packageName].orEmpty()

        override fun unavailableReason(packageName: String, action: PluginAction): String? = unavailable["$packageName/${action.id}"]

        override suspend fun execute(request: ActionRequest, ctx: CallContext): ActionResult {
            ctx.ensureStillAllowed()
            failure?.let { throw ToolException(it) }
            executed += request
            return ActionResult(buildJsonObject { put("handled_by", request.app.packageName) })
        }
    }

    private val platform = FakePlatform()

    private val spotifyYaml = """
        id: spotify
        name: Spotify
        description: Music.
        category: music
        status: experimental
        android:
          packages: [com.spotify.lite, com.spotify.music]
        actions:
          - id: play
            title: Play music
            description: Plays the best match for a song, artist or playlist.
            keywords: [music, song, listen]
            risk: action
            params:
              query: {description: What to play, required: true, max_length: 200}
            confirm: Play "{{query}}" on Spotify
            completes: true
            android:
              intent:
                action: android.media.action.MEDIA_PLAY_FROM_SEARCH
                extras: {query: "{{query}}"}
          - id: search
            title: Search
            description: Opens search results.
            risk: action
            params:
              query: {description: Search text, required: true}
            android:
              url: "spotify:search:{{query}}"
          - id: radio
            title: Start a radio
            description: Starts a radio from what is playing.
            risk: action
            android:
              steps:
                - launch: true
                - tap: Radio
          - id: share_text
            title: Share text
            description: The plugin's own share action.
            risk: action
            params:
              text: {description: Text, required: true}
            android:
              intent: {action: android.intent.action.SEND, type: text/plain, extras: {android.intent.extra.TEXT: "{{text}}"}}
    """.trimIndent()

    private val whatsappYaml = """
        id: whatsapp
        name: WhatsApp
        description: Messaging.
        category: messaging
        status: stable
        tested: [{app_version: "2.24", android: 14, date: 2026-11-01, by: octocat}]
        android:
          packages: [com.whatsapp]
        actions:
          - id: send_message
            title: Send a message
            description: Sends a WhatsApp message.
            params:
              contact: {description: Contact name}
              message: {description: Text, required: true}
            android:
              tool:
                name: whatsapp_send
                args: {contact: "{{contact}}", message: "{{message}}"}
            examples:
              - params: {message: hi}
                expect: {tool: {name: whatsapp_send, args: {message: hi}}}
    """.trimIndent()

    private val mapsYaml = """
        id: my_maps
        name: Maps
        description: Maps.
        category: maps
        android:
          packages: [com.maps]
        actions:
          - id: directions
            title: Get directions
            description: Opens directions to a place.
            risk: action
            params:
              to: {description: Destination, required: true}
            confirm: Show directions to {{to}}
            android:
              url: "https://maps.example/dir?to={{to}}"
          - id: message_owner
            title: Message the owner
            description: Sends a WhatsApp message to the map's owner.
            params:
              message: {description: Text, required: true}
            android:
              tool:
                name: whatsapp_send
                args: {to: "+15550100", message: "{{message}}"}
    """.trimIndent()

    private fun load(yaml: String, source: PluginSource = PluginSource.BUNDLED) = PluginLoader.load(yaml, source = source).let {
        it.plugin ?: error(it.errors.joinToString("\n"))
    }

    @Volatile private var plugins: PluginCatalog = PluginCatalog.EMPTY.with(load(spotifyYaml)).with(load(whatsappYaml))
        .merge(listOf(load(mapsYaml, PluginSource.LOCAL))).catalog

    private val registry = ToolRegistry()
    private val catalog = AppActionCatalog({ plugins }, platform, registry::get)
    private val whatsappCalls: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())

    init {
        registry.register(
            tool(
                name = "whatsapp_send", title = "Send WhatsApp message", description = "d", scope = Scopes.MESSAGES_SEND,
                risk = Risk.SENSITIVE, confirmsInternally = true,
            ) { args, ctx ->
                ctx.requireConfirmation("Send '${args.reqString("message")}' on WhatsApp")
                whatsappCalls += args
                ToolResult.text("sent")
            },
        )
        registry.registerAll(appActionTools(catalog))
        platform.discovered["com.spotify.music"] = listOf(
            PluginAction("open", "Open", "Opens the app.", risk = Risk.ACTION, android = IntentRecipe("android.intent.action.MAIN")),
            PluginAction("share_text", "Share", "Discovered share.", risk = Risk.ACTION, android = IntentRecipe("android.intent.action.SEND")),
            PluginAction("Bad Id", "Bad", "Bad.", risk = Risk.ACTION, android = UrlRecipe("https://x")),
            PluginAction("sneaky", "Sneaky", "A discovered tool recipe.", android = ToolRecipe("whatsapp_send")),
        )
        platform.discovered["com.notes"] = listOf(
            PluginAction("open", "Open", "Opens the app.", risk = Risk.ACTION, android = IntentRecipe("android.intent.action.MAIN")),
            PluginAction("share_text", "Share text", "Shares text into a new note.", keywords = listOf("note"), risk = Risk.ACTION,
                params = listOf(ParamSpec("text", "Text", required = true)),
                android = IntentRecipe("android.intent.action.SEND", type = "text/plain", extras = mapOf("android.intent.extra.TEXT" to ExtraSpec(ValueSpec.Template("{{text}}"))))),
        )
        platform.unavailable["com.spotify.music/radio"] = "Turn on PhoneBridge's accessibility service to run screen recipes."
    }

    private val tokens = TokenManager(InMemoryTokenStore(), DeviceIdentity(ByteArray(32) { it.toByte() }).deviceId)
    private val audit = InMemoryAuditLog()
    private val prompts: MutableList<ConfirmationRequest> = Collections.synchronizedList(mutableListOf())

    private val server = McpServer(
        registry = registry,
        tokens = tokens,
        policy = PolicyEngine(),
        confirmations = ConfirmationProvider { req ->
            prompts += req
            ConfirmationResult.APPROVED
        },
        audit = audit,
        serverInfo = ServerInfo("phonebridge", "0.1.0"),
        instructions = null,
        globalSecurity = { GlobalSecurity(neverAllowApps = setOf("com.bank")) },
    )

    private fun issue(
        scopes: Set<String> = Scopes.ALL,
        policy: ConfirmPolicy = ConfirmPolicy.SENSITIVE,
        appPolicy: AppPolicy = AppPolicy(),
        change: (TokenRecord) -> TokenRecord = { it },
    ): IssuedToken {
        val issued = tokens.issue("Claude", scopes, policy, null, appPolicy = appPolicy)
        return issued.copy(record = tokens.update(issued.record.id, change))
    }

    private suspend fun call(token: IssuedToken, name: String, args: String): JsonObject {
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
        val response = server.handle(TransportRequest("Bearer ${token.plaintext}", body, "relay"))
        return Json.parseToJsonElement(assertNotNull(response.body)).jsonObject["result"]!!.jsonObject
    }

    private fun JsonObject.texts() = this["content"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }

    private fun JsonObject.json() = Json.parseToJsonElement(texts()[0]).jsonObject

    private fun JsonObject.isError() = this["isError"]!!.jsonPrimitive.boolean

    private fun lastAudit() = audit.entries.value.first()

    private fun ctx(token: TokenRecord, access: AppAccess = AppAccess.of(token.appPolicy, setOf("com.bank"))) =
        CallContext(token.id, token.name, token.scopes, "local", "r", access, toolRules = token.toolRules, maxRisk = token.maxRisk) { }

    // Catalog

    @Test
    fun pluginActionsComeFirstAndWinOnIdClashes() = runTest {
        val ids = catalog.actions(spotify).map { it.action.id to (it.plugin?.id ?: "discovered") }
        assertEquals(listOf("play" to "spotify", "search" to "spotify", "radio" to "spotify", "share_text" to "spotify", "open" to "discovered"), ids)
        assertEquals("com.spotify.music/play", catalog.actions(spotify).first().toolName)
    }

    @Test
    fun aPluginAppliesToItsFirstInstalledPackageOnly() = runTest {
        val lite = InstalledApp("com.spotify.lite", "Spotify Lite")
        platform.apps = platform.apps + lite
        assertEquals(listOf("play", "search", "radio", "share_text"), catalog.actions(lite).map { it.action.id })
        assertEquals(listOf("open", "share_text"), catalog.actions(spotify).map { it.action.id }, "com.spotify.music is the plugin's second package")
        assertEquals(Integration.BASIC, catalog.integration(catalog.applicablePlugins(spotify.packageName, platform.apps.map { it.packageName }.toSet())))
    }

    @Test
    fun aFailingScanKeepsThePluginActions() = runTest {
        val failing = object : AppActionsPlatform by platform {
            override suspend fun discoveredActions(packageName: String): List<PluginAction> = throw IllegalStateException("boom")
        }
        val c = AppActionCatalog({ plugins }, failing, registry::get)
        assertEquals(4, c.actions(spotify).size)
    }

    @Test
    fun summariesGiveIntegrationAndTheActionsThisAgentMayRun() = runTest {
        val full = issue().record
        val summary = catalog.summarize(listOf(spotify, whatsapp, maps, notes), ctx(full))
        assertEquals(AppSummary(Integration.PLUGIN, listOf("play", "search", "radio", "share_text", "open"), PluginStatus.EXPERIMENTAL), summary["com.spotify.music"])
        assertEquals(AppSummary(Integration.PLUGIN, listOf("send_message"), PluginStatus.STABLE), summary["com.whatsapp"])
        assertEquals(Integration.LOCAL, summary.getValue("com.maps").integration)
        assertEquals(AppSummary(Integration.BASIC, listOf("open", "share_text")), summary["com.notes"])

        // No messages.send: the tool recipe's built-in tool is out of scope. A BLOCK rule on an action name hides it.
        val limited = issue(scopes = setOf(Scopes.APPS)) { it.copy(toolRules = mapOf("com.spotify.music/play" to ToolRule.BLOCK)) }.record
        val s2 = catalog.summarize(listOf(spotify, whatsapp), ctx(limited))
        assertEquals(listOf("search", "radio", "share_text", "open"), s2.getValue("com.spotify.music").actionIds)
        assertEquals(emptyList(), s2.getValue("com.whatsapp").actionIds)

        // Read-only agents can't run app actions at all.
        val readOnly = issue { it.copy(maxRisk = Risk.READ) }.record
        assertTrue(catalog.summarize(listOf(spotify), ctx(readOnly)).getValue("com.spotify.music").actionIds.isEmpty())
        val runBlocked = issue { it.copy(toolRules = mapOf("app_action_run" to ToolRule.BLOCK)) }.record
        assertTrue(catalog.summarize(listOf(spotify), ctx(runBlocked)).getValue("com.spotify.music").actionIds.isEmpty())
    }

    @Test
    fun virtualToolsCarryTheActionsPolicy() = runTest {
        val play = assertNotNull(catalog.toolFor(catalog.action(spotify, "play")!!))
        assertEquals("com.spotify.music/play", play.name)
        assertEquals("Spotify: Play music", play.title)
        assertEquals(Scopes.APPS, play.scope)
        assertEquals(Risk.ACTION, play.risk)
        assertFalse(play.alwaysConfirm)
        assertEquals(PluginRules.LAUNCH_TIMEOUT_MS, play.timeoutMs)
        assertEquals(listOf("query"), play.inputSchema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("Play \"lofi\" on Spotify", play.describeCall(buildJsonObject { put("query", "lofi") }))

        val search = catalog.toolFor(catalog.action(spotify, "search")!!)!!
        assertEquals("Spotify: Search (query=daft punk)", search.describeCall(buildJsonObject { put("query", "daft punk") }))
        assertEquals(PluginRules.STEPS_TIMEOUT_MS, catalog.toolFor(catalog.action(spotify, "radio")!!)!!.timeoutMs)
        assertEquals("Turn on PhoneBridge's accessibility service to run screen recipes.", catalog.toolFor(catalog.action(spotify, "radio")!!)!!.unavailableReason())

        val local = catalog.toolFor(catalog.action(maps, "directions")!!)!!
        assertTrue(local.alwaysConfirm)
        assertEquals("Maps: Get directions (unreviewed plugin)", local.title)
        assertEquals("Show directions to Paris [opens https://maps.example/dir?to=Paris]", local.describeCall(buildJsonObject { put("to", "Paris") }))

        // Tool recipes run the built-in tool itself, marked unreviewed for local plugins.
        assertEquals("whatsapp_send", catalog.toolFor(catalog.action(whatsapp, "send_message")!!)!!.name)
        val unreviewed = catalog.toolFor(catalog.action(maps, "message_owner")!!)!!
        assertEquals("whatsapp_send", unreviewed.name)
        assertTrue(unreviewed.alwaysConfirm)
        assertEquals("Send WhatsApp message (unreviewed plugin)", unreviewed.title)
        assertTrue(unreviewed.confirmsInternally)
    }

    @Test
    fun resolvingAppsHonoursAppAccess() = runTest {
        val access = AppAccess(AppAccessMode.BLOCKLIST, setOf("com.notes.pro"), setOf("com.bank"))
        assertEquals(spotify, catalog.resolveApp("com.spotify.music", access))
        assertEquals(spotify, catalog.resolveApp(" spotify ", access))
        assertEquals(notes, catalog.resolveApp("notes", access))
        assertIs<dev.phonebridge.core.tools.AppBlockedException>(runCatching { catalog.resolveApp("com.bank", access) }.exceptionOrNull())
        assertIs<dev.phonebridge.core.tools.AppBlockedException>(runCatching { catalog.resolveApp("Bank", access) }.exceptionOrNull())
        // A package that isn't installed: refused like an installed one when blocked.
        assertIs<dev.phonebridge.core.tools.AppBlockedException>(runCatching { catalog.resolveApp("com.notes.pro", AppAccess(AppAccessMode.BLOCKLIST, setOf("com.notes.pro"), emptySet())) }.exceptionOrNull())
        val missing = runCatching { catalog.resolveApp("com.not.installed", access) }.exceptionOrNull()
        assertEquals("No app with package 'com.not.installed' is installed. Use apps_list to see installed apps.", missing?.message)
        val ambiguous = runCatching { catalog.resolveApp("Note", AppAccess.UNRESTRICTED) }.exceptionOrNull()
        assertEquals(
            "'Note' matches several apps: Notes (com.notes), Notes Pro (com.notes.pro). Call again with 'app' set to one of these packages, " +
                "or ask the user which one they mean.",
            ambiguous?.message,
        )
        val none = runCatching { catalog.resolveApp("Booking.com", access) }.exceptionOrNull()
        assertTrue(none!!.message!!.startsWith("No installed app matches 'Booking.com'. Closest: Spotify (com.spotify.music)."), none.message)
    }

    // app_actions_list

    @Test
    fun listingAnAppShowsItsRunnableActions() = runTest {
        val token = issue(scopes = setOf(Scopes.APPS)) { it.copy(toolRules = mapOf("com.spotify.music/search" to ToolRule.BLOCK)) }
        val result = call(token, "app_actions_list", """{"app":"Spotify"}""")
        assertFalse(result.isError())
        val json = result.json()
        assertEquals("Spotify", json["app"]!!.jsonPrimitive.content)
        assertEquals("com.spotify.music", json["package"]!!.jsonPrimitive.content)
        assertEquals("plugin", json["integration"]!!.jsonPrimitive.content)
        assertEquals("experimental", json["status"]!!.jsonPrimitive.content)
        assertTrue("fall back to the screen tools" in json["note"]!!.jsonPrimitive.content)
        val actions = json["actions"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("play", "radio", "share_text", "open"), actions.map { it["action"]!!.jsonPrimitive.content })
        val play = actions[0]
        assertEquals("Play music", play["title"]!!.jsonPrimitive.content)
        assertEquals("ACTION", play["risk"]!!.jsonPrimitive.content)
        assertEquals(true, play["completes"]!!.jsonPrimitive.boolean)
        assertEquals(ParamSchemas.objectSchema(catalog.action(spotify, "play")!!.action.params), play["params"])
        assertNull(play["available"])
        val radio = actions[1]
        assertEquals(false, radio["available"]!!.jsonPrimitive.boolean)
        assertTrue("accessibility" in radio["unavailable_reason"]!!.jsonPrimitive.content)
        assertTrue("app_action_run" in json["how_to_run"]!!.jsonPrimitive.content)
        assertEquals("1 action is hidden: this agent may not use it.", result.texts()[1])
        assertEquals(AuditOutcome.OK, lastAudit().outcome)
        assertEquals("app_actions_list", lastAudit().tool)
    }

    @Test
    fun listingWithAQueryRanksWithinTheApp() = runTest {
        val json = call(issue(), "app_actions_list", """{"app":"com.spotify.music","query":"listen to a song"}""").json()
        assertEquals(listOf("play"), json["actions"]!!.jsonArray.map { it.jsonObject["action"]!!.jsonPrimitive.content })
        val none = call(issue(), "app_actions_list", """{"app":"com.spotify.music","query":"zzzz"}""")
        assertEquals(5, none.json()["actions"]!!.jsonArray.size)
        assertTrue("No action of Spotify matched 'zzzz'" in none.texts()[1])
    }

    @Test
    fun searchingRanksAcrossVisibleAppsAndHidesBlockedOnes() = runTest {
        platform.discovered["com.bank"] = listOf(
            PluginAction("share_text", "Share text", "Share into the bank.", risk = Risk.ACTION, android = IntentRecipe("android.intent.action.SEND")),
        )
        val token = issue()
        val result = call(token, "app_actions_list", """{"query":"share text"}""")
        val matches = result.json()["matches"]!!.jsonArray.map { it.jsonObject }
        val found = matches.map { it["package"]!!.jsonPrimitive.content + "/" + it["action"]!!.jsonPrimitive.content }
        assertEquals(listOf("com.spotify.music/share_text", "com.notes/share_text"), found)
        assertEquals("Spotify", matches[0]["app"]!!.jsonPrimitive.content)
        assertEquals("basic", matches[1]["integration"]!!.jsonPrimitive.content)
        assertFalse("com.bank" in result.texts().joinToString())
        assertTrue(result.texts().any { "Some apps are hidden by this agent's app access settings." in it })

        val music = call(token, "app_actions_list", """{"query":"play some music","limit":1}""")
        assertEquals("com.spotify.music", music.json()["matches"]!!.jsonArray.single().jsonObject["package"]!!.jsonPrimitive.content)
        assertTrue(music.texts()[1].contains("more matches not shown"), music.texts().toString())
    }

    @Test
    fun listingNeedsAppOrQuery() = runTest {
        val result = call(issue(), "app_actions_list", "{}")
        assertTrue(result.isError())
        assertTrue("Pass 'app'" in result.texts()[0])
    }

    @Test
    fun listingABlockedAppIsDeniedApp() = runTest {
        val result = call(issue(), "app_actions_list", """{"app":"Bank"}""")
        assertTrue(result.isError())
        assertTrue("never-allow list" in result.texts()[0], result.texts().toString())
        assertEquals(AuditOutcome.DENIED_APP, lastAudit().outcome)
    }

    @Test
    fun agentsThatCannotRunActionsAreToldSo() = runTest {
        val token = issue { it.copy(toolRules = mapOf("app_action_run" to ToolRule.BLOCK)) }
        val result = call(token, "app_actions_list", """{"app":"Spotify"}""")
        assertEquals(0, result.json()["actions"]!!.jsonArray.size)
        assertTrue("may not run app actions" in result.texts()[1])
    }

    // app_action_run

    @Test
    fun runningAnIntentActionIsPinnedAndAuditedAsTheAction() = runTest {
        val result = call(issue(), "app_action_run", """{"app":"Spotify","action":"play","params":{"query":"lofi beats"}}""")
        assertFalse(result.isError(), result.texts().toString())
        val request = platform.executed.single()
        assertEquals(spotify, request.app)
        assertEquals("spotify", request.plugin?.id)
        assertEquals(
            RenderedIntent(
                "android.media.action.MEDIA_PLAY_FROM_SEARCH", "com.spotify.music",
                extras = mapOf("query" to ExtraValue.StringValue("lofi beats")),
            ),
            request.recipe,
        )
        val json = result.json()
        assertEquals(true, json["done"]!!.jsonPrimitive.boolean)
        assertEquals("com.spotify.music", json["handled_by"]!!.jsonPrimitive.content)
        assertTrue("experimental community plugin" in json["integration_note"]!!.jsonPrimitive.content)
        assertNull(json["next"])
        val entry = lastAudit()
        assertEquals(1, audit.entries.value.size)
        assertEquals("com.spotify.music/play", entry.tool)
        assertEquals("""{"query":"lofi beats"}""", entry.argsPreview)
        assertEquals("via app_action_run", entry.detail)
        assertEquals(AuditOutcome.OK, entry.outcome)
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun actionsThatDoNotCompleteSayWhatToDoNext() = runTest {
        val json = call(issue(), "app_action_run", """{"app":"com.spotify.music","action":"com.spotify.music/search","params":"{\"query\":\"daft punk\"}"}""").json()
        assertEquals(RenderedUrl("spotify:search:daft%20punk", "com.spotify.music"), platform.executed.single().recipe)
        assertEquals(false, json["done"]!!.jsonPrimitive.boolean)
        assertEquals(true, json["opened"]!!.jsonPrimitive.boolean)
        assertTrue("screen_read" in json["next"]!!.jsonPrimitive.content && "ask the user" in json["next"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolRecipesRunTheBuiltInToolAsADirectCall() = runTest {
        val result = call(issue(), "app_action_run", """{"app":"WhatsApp","action":"send_message","params":{"contact":"Tania","message":"hi"}}""")
        assertFalse(result.isError(), result.texts().toString())
        assertEquals("sent", result.texts()[0])
        assertEquals(buildJsonObject { put("contact", "Tania"); put("message", "hi") }, whatsappCalls.single())
        val prompt = prompts.single()
        assertEquals("whatsapp_send", prompt.toolName)
        assertEquals("Send 'hi' on WhatsApp", prompt.summary)
        assertEquals("whatsapp_send", lastAudit().tool)
        assertEquals("""{"contact":"Tania","message":"hi"}""", lastAudit().argsPreview)
        assertEquals("approved by user; via app_action_run (com.whatsapp/send_message)", lastAudit().detail)
        assertTrue(platform.executed.isEmpty())
    }

    @Test
    fun toolRecipesKeepTheBuiltInToolsScopeAndRules() = runTest {
        val noSend = issue(scopes = setOf(Scopes.APPS))
        val refused = call(noSend, "app_action_run", """{"app":"WhatsApp","action":"send_message","params":{"message":"hi"}}""")
        assertTrue(refused.isError())
        assertEquals(AuditOutcome.DENIED_SCOPE, lastAudit().outcome)
        assertEquals("whatsapp_send", lastAudit().tool)

        val blocked = issue { it.copy(toolRules = mapOf("whatsapp_send" to ToolRule.BLOCK)) }
        assertTrue(call(blocked, "app_action_run", """{"app":"WhatsApp","action":"send_message","params":{"message":"hi"}}""").isError())
        assertEquals(AuditOutcome.DENIED_POLICY, lastAudit().outcome)

        val aliasBlocked = issue { it.copy(toolRules = mapOf("com.whatsapp/send_message" to ToolRule.BLOCK)) }
        assertTrue(call(aliasBlocked, "app_action_run", """{"app":"WhatsApp","action":"send_message","params":{"message":"hi"}}""").isError())
        assertEquals(AuditOutcome.DENIED_POLICY, lastAudit().outcome)
        assertTrue(whatsappCalls.isEmpty())
    }

    @Test
    fun localPluginActionsAlwaysNeedApprovalAndSaySo() = runTest {
        val token = issue(policy = ConfirmPolicy.NEVER) { it.copy(toolRules = mapOf("com.maps/directions" to ToolRule.ALLOW)) }
        val result = call(token, "app_action_run", """{"app":"Maps","action":"directions","params":{"to":"Paris"}}""")
        assertFalse(result.isError(), result.texts().toString())
        val prompt = prompts.single()
        assertEquals("com.maps/directions", prompt.toolName)
        assertEquals("Maps: Get directions (unreviewed plugin)", prompt.toolTitle)
        assertEquals("Show directions to Paris [opens https://maps.example/dir?to=Paris]", prompt.summary)
        assertTrue("local plugin that has not been reviewed" in result.json()["integration_note"]!!.jsonPrimitive.content)

        call(token, "app_action_run", """{"app":"Maps","action":"message_owner","params":{"message":"hello"}}""")
        assertEquals("Send WhatsApp message (unreviewed plugin)", prompts[1].toolTitle)
        assertEquals("whatsapp_send", lastAudit().tool)
        assertEquals(buildJsonObject { put("to", "+15550100"); put("message", "hello") }, whatsappCalls.single())
    }

    @Test
    fun unknownActionsListWhatTheAgentMayRun() = runTest {
        val token = issue(scopes = setOf(Scopes.APPS))
        val result = call(token, "app_action_run", """{"app":"Spotify","action":"plai"}""")
        assertTrue(result.isError())
        assertEquals(
            "Spotify (com.spotify.music) has no action 'plai'. Its actions: play, search, radio, share_text, open. " +
                "Call app_actions_list with app=\"com.spotify.music\" for their parameters.",
            result.texts()[0],
        )
        assertEquals(AuditOutcome.TOOL_ERROR, lastAudit().outcome)
        assertEquals("app_action_run", lastAudit().tool)
    }

    @Test
    fun badParametersAreRefusedBeforeAnyPrompt() = runTest {
        val token = issue(policy = ConfirmPolicy.ALL_ACTIONS)
        for ((params, expected) in listOf(
            """{}""" to "Missing required parameter 'query'",
            """{"query":"x","volume":3}""" to "Unknown parameter 'volume'",
            """{"query":"${"x".repeat(201)}"}""" to "at most 200 characters",
            """[1]""" to "'params' must be an object",
        )) {
            val result = call(token, "app_action_run", """{"app":"Spotify","action":"play","params":$params}""")
            assertTrue(result.isError())
            assertTrue(expected in result.texts()[0], result.texts()[0])
        }
        assertTrue(prompts.isEmpty())
        assertTrue(platform.executed.isEmpty())
    }

    @Test
    fun unavailableActionsAreRefusedBeforeAnyPrompt() = runTest {
        val result = call(issue(policy = ConfirmPolicy.ALL_ACTIONS), "app_action_run", """{"app":"Spotify","action":"radio"}""")
        assertTrue(result.isError())
        assertEquals("Spotify's 'radio' action can't run right now: Turn on PhoneBridge's accessibility service to run screen recipes.", result.texts()[0])
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun blockedAppsAreDeniedAppAndNothingRuns() = runTest {
        val token = issue(appPolicy = AppPolicy(AppAccessMode.ALLOWLIST, setOf("com.whatsapp")))
        val result = call(token, "app_action_run", """{"app":"com.spotify.music","action":"play","params":{"query":"x"}}""")
        assertTrue(result.isError())
        assertTrue("blocked 'Spotify' (com.spotify.music) for this agent" in result.texts()[0], result.texts()[0])
        assertEquals(AuditOutcome.DENIED_APP, lastAudit().outcome)
        assertEquals("app_action_run", lastAudit().tool)
        assertEquals("blocked app com.spotify.music", lastAudit().detail)
        assertTrue(platform.executed.isEmpty())
    }

    @Test
    fun ruleOnAnActionNameApplies() = runTest {
        val token = issue { it.copy(toolRules = mapOf("com.spotify.music/play" to ToolRule.ASK)) }
        call(token, "app_action_run", """{"app":"Spotify","action":"play","params":{"query":"x"}}""")
        assertEquals("Play \"x\" on Spotify", prompts.single().summary)
        val blocked = issue { it.copy(toolRules = mapOf("com.spotify.music/play" to ToolRule.BLOCK)) }
        val result = call(blocked, "app_action_run", """{"app":"Spotify","action":"play","params":{"query":"x"}}""")
        assertTrue(result.isError())
        assertEquals(AuditOutcome.DENIED_POLICY, lastAudit().outcome)
        assertEquals("com.spotify.music/play", lastAudit().tool)
        assertEquals(1, platform.executed.size)
    }

    @Test
    fun experimentalFailuresSuggestTheScreenTools() = runTest {
        platform.failure = "Spotify did not open."
        val result = call(issue(), "app_action_run", """{"app":"Spotify","action":"play","params":{"query":"x"}}""")
        assertTrue(result.isError())
        assertEquals("Spotify did not open. (Spotify support is an experimental community plugin: if this keeps failing, use the screen tools instead.)", result.texts()[0])
        assertEquals(AuditOutcome.TOOL_ERROR, lastAudit().outcome)
        assertEquals("com.spotify.music/play", lastAudit().tool)
    }

    @Test
    fun missingArgumentsAreExplained() = runTest {
        val token = issue()
        assertTrue("Pass 'app'" in call(token, "app_action_run", """{"action":"play"}""").texts()[0])
        assertTrue("Pass 'action'" in call(token, "app_action_run", """{"app":"Spotify"}""").texts()[0])
    }

    @Test
    fun discoveredActionsRunToo() = runTest {
        val result = call(issue(), "app_action_run", """{"app":"Notes","action":"share_text","params":{"text":"buy milk"}}""")
        assertFalse(result.isError(), result.texts().toString())
        val request = platform.executed.single()
        assertNull(request.plugin)
        assertEquals(mapOf("android.intent.extra.TEXT" to ExtraValue.StringValue("buy milk")), (request.recipe as RenderedIntent).extras)
        assertNull(result.json()["integration_note"])
        assertEquals("com.notes/share_text", lastAudit().tool)
    }

    @Test
    fun importedPluginsApplyAtOnce() = runTest {
        val token = issue()
        assertTrue(call(token, "app_action_run", """{"app":"Notes","action":"new_note"}""").isError())
        val notesPlugin = """
            id: notes
            name: Notes
            description: Notes.
            category: productivity
            android:
              packages: [com.notes]
            actions:
              - id: new_note
                title: New note
                description: Opens a new note.
                risk: action
                android:
                  url: "notes://new"
        """.trimIndent()
        plugins = plugins.merge(listOf(load(notesPlugin))).catalog
        assertFalse(call(token, "app_action_run", """{"app":"Notes","action":"new_note"}""").isError())
        assertEquals(RenderedUrl("notes://new", "com.notes"), platform.executed.single().recipe)
        assertEquals(JsonPrimitive(true), JsonPrimitive(prompts.single().toolTitle.endsWith("(unreviewed plugin)")))
    }
}
