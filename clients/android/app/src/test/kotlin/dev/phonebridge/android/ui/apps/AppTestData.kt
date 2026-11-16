package dev.phonebridge.android.ui.apps

import dev.phonebridge.core.plugins.ActionRequest
import dev.phonebridge.core.plugins.ActionResult
import dev.phonebridge.core.plugins.AppActionCatalog
import dev.phonebridge.core.plugins.AppActionsPlatform
import dev.phonebridge.core.plugins.AppResolution
import dev.phonebridge.core.plugins.InstalledApp
import dev.phonebridge.core.plugins.IntentRecipe
import dev.phonebridge.core.plugins.Integration
import dev.phonebridge.core.plugins.PluginAction
import dev.phonebridge.core.plugins.PluginCatalog
import dev.phonebridge.core.plugins.PluginFile
import dev.phonebridge.core.plugins.PluginLoader
import dev.phonebridge.core.plugins.PluginSource
import dev.phonebridge.core.plugins.PluginStatus
import dev.phonebridge.core.tools.AppAccess
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.tool

/** Shared fixtures of the Apps UI tests: a fake platform, a few plugins and ready-made listings. */
internal object AppTestData {
    const val SPOTIFY = "com.spotify.music"
    const val WHATSAPP = "com.whatsapp"
    const val NOTES = "com.example.notes"
    const val BANK = "com.example.bank"

    fun discoveredOpen(label: String) = PluginAction(
        id = "open", title = "Open the app", description = "Opens $label.", risk = Risk.ACTION, completes = true,
        android = IntentRecipe(action = "android.intent.action.MAIN", categories = listOf("android.intent.category.LAUNCHER")),
    )

    class FakePlatform(
        var apps: List<InstalledApp> = listOf(
            InstalledApp(BANK, "Bank"),
            InstalledApp(NOTES, "Notes"),
            InstalledApp(SPOTIFY, "Spotify"),
            InstalledApp(WHATSAPP, "WhatsApp"),
        ),
    ) : AppActionsPlatform {
        val failing = HashSet<String>()
        val unavailable = HashMap<String, String>()

        override suspend fun installedApps() = apps

        override suspend fun resolveApp(query: String, access: AppAccess) = AppResolution.NotFound()

        override suspend fun discoveredActions(packageName: String): List<PluginAction> {
            if (packageName in failing) throw IllegalStateException("scan failed")
            val label = apps.firstOrNull { it.packageName == packageName }?.label ?: packageName
            return listOf(discoveredOpen(label))
        }

        override fun unavailableReason(packageName: String, action: PluginAction): String? = unavailable["$packageName/${action.id}"]

        override suspend fun execute(request: ActionRequest, ctx: CallContext) = ActionResult()
    }

    private val spotifyYaml = """
        id: spotify
        name: Spotify
        description: Music.
        category: music
        status: experimental
        android:
          packages: [$SPOTIFY]
        actions:
          - id: play
            title: Play music
            description: Plays the best match.
            risk: action
            params:
              query: {description: What to play, required: true}
            android:
              intent:
                action: android.media.action.MEDIA_PLAY_FROM_SEARCH
                extras: {query: "{{query}}"}
    """.trimIndent()

    private val whatsappYaml = """
        id: whatsapp
        name: WhatsApp
        description: Messaging.
        category: messaging
        status: beta
        android:
          packages: [$WHATSAPP]
        actions:
          - id: send_message
            title: Send a message
            description: Sends a WhatsApp message.
            params:
              contact: {description: Contact name}
              message: {description: Message text, required: true}
            android:
              tool:
                name: sms_send
                args: {contact: "{{contact}}", message: "{{message}}"}
          - id: missing_tool
            title: Make a video call
            description: Needs a tool this phone lacks.
            android:
              tool: {name: call_status}
    """.trimIndent()

    private val notesLocalYaml = """
        id: notes
        name: Notes
        description: Notes.
        category: productivity
        android:
          packages: [$NOTES]
        actions:
          - id: new_note
            title: New note
            description: Opens a new note.
            risk: action
            android: {url: "notes://new"}
    """.trimIndent()

    fun catalog(platform: AppActionsPlatform = FakePlatform()): AppActionCatalog {
        val bundled = PluginCatalog.load(
            listOf(PluginFile("plugins/spotify/plugin.yaml", spotifyYaml), PluginFile("plugins/whatsapp/plugin.yaml", whatsappYaml)),
            runExamples = false,
        )
        check(bundled.errors.isEmpty()) { bundled.errors.joinToString() }
        val local = PluginLoader.load(notesLocalYaml, PluginSource.LOCAL).plugin!!
        val merged = bundled.catalog.merge(listOf(local)).catalog
        val whatsappSend: Tool = tool(
            name = "sms_send", title = "Send WhatsApp message", description = "Sends.", scope = Scopes.MESSAGES_SEND, risk = Risk.SENSITIVE,
        ) { _, _ -> ToolResult.text("sent") }
        return AppActionCatalog({ merged }, platform, builtInTool = { name -> whatsappSend.takeIf { it.name == name } })
    }

    fun action(
        pkg: String,
        id: String,
        title: String = id,
        risk: Risk = Risk.ACTION,
        scope: String = Scopes.APPS,
        builtInTool: String? = null,
        unreviewed: Boolean = false,
    ) = ActionInfo(id = id, title = title, description = "$title.", risk = risk, scope = scope, toolName = "$pkg/$id", builtInTool = builtInTool, unreviewed = unreviewed)

    fun listing(
        pkg: String,
        label: String,
        integration: Integration = Integration.BASIC,
        vararg actions: ActionInfo,
        status: PluginStatus? = null,
    ) = AppListing(pkg, label, integration, pluginName = if (integration == Integration.BASIC) null else label, pluginStatus = status, actions = actions.toList())
}
