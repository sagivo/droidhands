package com.droidhands.ios.apps

import com.droidhands.core.plugins.ParamSpec
import com.droidhands.core.plugins.PluginAction
import com.droidhands.core.plugins.PluginRules
import com.droidhands.core.plugins.Step
import com.droidhands.core.plugins.StepAction
import com.droidhands.core.plugins.StepsRecipe
import com.droidhands.core.plugins.ToolRecipe
import com.droidhands.core.plugins.ValueSpec
import com.droidhands.core.tools.Risk
import com.droidhands.ios.inventory.InventoryLimits
import com.droidhands.ios.inventory.IosApp
import com.droidhands.ios.inventory.Plist
import com.droidhands.ios.inventory.arr
import com.droidhands.ios.inventory.bool
import com.droidhands.ios.inventory.str
import com.droidhands.ios.inventory.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

/**
 * What an installed iOS app declares, turned into discovered actions and `declares` (pure; docs/IOS.md "Discovered
 * actions"). Everything comes from the app's own Info.plist, entitlements and App Intents metadata; nothing is known
 * about any app in advance.
 *
 * * `open`: always, a screen recipe that launches the app.
 * * `open_link`: when the app declares URL schemes or universal-link domains, `url_open` pinned to the app (the only
 *   `tool` recipe the catalog accepts from discovery), described with the link forms the installed version declares.
 * * `declares`: `app_intents`, `quick_actions` and `document_types`, with `how` (how an agent gets them done) and
 *   `unreadable` (what this inventory can't read, so the agent knows the list may be incomplete).
 */
object IosDeclarations {
    const val OPEN = "open"
    const val OPEN_LINK = "open_link"
    const val URL_OPEN = "url_open"

    /** At most this many link forms are listed in `open_link`'s description (as on Android). */
    const val MAX_PATTERNS = 14
    const val MAX_INTENTS = 40
    private const val MAX_PHRASES = 4
    private const val MAX_INTENT_PARAMS = 8
    private const val MAX_ITEMS = 30
    private const val MAX_TEXT = 200
    private const val URL_MAX = 4_000

    /** The App Intents metadata Xcode writes into a bundle (and into each of its extensions). */
    const val APP_INTENTS_FILE = "Metadata.appintents/extract.actionsdata"

    const val HOW_APP_INTENTS =
        "Only Siri, Spotlight and the Shortcuts app can run App Intents on iOS, so they can't be started from here: they show " +
            "what the app can do. Do it with phone use in the app (screen_observe, ui_tap)."
    const val HOW_QUICK_ACTIONS =
        "The menu of the app's Home Screen icon: go Home (ui_global_action home), long-press the icon, then tap the item."
    const val HOW_DOCUMENT_TYPES = "File types the app opens (for information)."

    /** `open`, then `open_link` when [app] declares links. */
    fun actions(app: IosApp): List<PluginAction> = listOfNotNull(open(app), openLink(app))

    fun open(app: IosApp) = PluginAction(
        id = OPEN,
        title = "Open the app",
        description = "Opens ${app.name} like tapping its icon on the Home Screen (it comes back where it was if it was running).",
        keywords = listOf("open", "launch", "start"),
        risk = Risk.ACTION,
        completes = true,
        ios = StepsRecipe(listOf(Step(StepAction.Launch()))),
    )

    /** Null when [app] declares no links. */
    fun openLink(app: IosApp): PluginAction? {
        val forms = IosLinks.forms(app)
        if (forms.isEmpty) return null
        val all = forms.patterns
        val shown = all.take(MAX_PATTERNS)
        val list = shown.joinToString(", ") + if (all.size > shown.size) " and ${all.size - shown.size} more" else ""
        val description = (
            "Opens a link in ${app.name}. The installed version declares that it opens: $list (* = anything). Build a link of " +
                "one of these forms from what you know about ${app.name}'s links. iOS, not DroidHands, picks the app for a link: " +
                "the result says which app came to the front."
            ).take(PluginRules.MAX_ACTION_DESCRIPTION)
        val example = shown.first().replace("*", "…")
        return PluginAction(
            id = OPEN_LINK,
            title = "Open a link",
            description = description,
            keywords = (listOf("link", "url", "deep link", "open", "go to") + hostWords(forms.domains)).distinct().take(PluginRules.MAX_KEYWORDS),
            params = listOf(ParamSpec("url", "The link to open, e.g. $example".take(PluginRules.MAX_PARAM_DESCRIPTION), required = true, maxLength = URL_MAX)),
            completes = false,
            ios = ToolRecipe(
                name = URL_OPEN,
                args = linkedMapOf("url" to ValueSpec.Template("{{url}}"), "package" to ValueSpec.Template(app.bundleId)),
            ),
        )
    }

    /**
     * `declares` for [app]: [appIntentsFiles] are the contents of its App Intents metadata files (main bundle and
     * extensions), [localize] maps Info.plist strings through the app's `InfoPlist.strings`. Null when there is
     * nothing to say.
     */
    fun declared(
        app: IosApp,
        limits: InventoryLimits,
        appIntentsFiles: List<ByteArray> = emptyList(),
        localize: (String) -> String = { it },
    ): JsonObject? {
        val intents = appIntentsFiles.flatMap { appIntents(it, app.name) }.distinctBy { it["id"] }.take(MAX_INTENTS)
        val quick = quickActions(app.infoPlist, localize)
        val docs = documentTypes(app.infoPlist, localize)
        val unreadable = buildList {
            limits.infoPlist?.let { add("links, quick_actions, document_types" to it) }
            limits.entitlements?.takeIf { it != limits.infoPlist }?.let { add("https links" to it) }
            limits.bundleFiles?.let { add("app_intents" to it) }
        }
        if (intents.isEmpty() && quick.isEmpty() && docs.isEmpty() && unreadable.isEmpty()) return null
        return buildJsonObject {
            if (intents.isNotEmpty()) put("app_intents", JsonArray(intents))
            if (quick.isNotEmpty()) put("quick_actions", JsonArray(quick))
            if (docs.isNotEmpty()) put("document_types", JsonArray(docs))
            val how = listOfNotNull(
                if (intents.isNotEmpty()) "app_intents" to HOW_APP_INTENTS else null,
                if (quick.isNotEmpty()) "quick_actions" to HOW_QUICK_ACTIONS else null,
                if (docs.isNotEmpty()) "document_types" to HOW_DOCUMENT_TYPES else null,
            )
            if (how.isNotEmpty()) {
                putJsonArray("how") {
                    for ((kind, text) in how) addJsonObject {
                        put("kind", kind)
                        put("how", text)
                    }
                }
            }
            if (unreadable.isNotEmpty()) {
                putJsonArray("unreadable") {
                    for ((what, why) in unreadable) addJsonObject {
                        put("what", what)
                        put("why", why)
                    }
                }
            }
        }
    }

    /**
     * The App Intents in one `extract.actionsdata` (JSON written by Xcode's metadata processor):
     * `actions` (an object keyed by identifier, or an array) with `identifier`, `title`, `descriptionMetadata`,
     * `parameters` (`name`, `title`, `isOptional`) and `isDiscoverable`; `autoShortcuts` with `actionIdentifier` and
     * `phraseTemplates`, whose `${applicationName}` becomes [appName]. Titles may be plain strings or localized-string
     * objects (`{key, …}`). Anything unreadable is skipped. Returns `{id, title, description?, phrases?, params?}`.
     */
    fun appIntents(data: ByteArray, appName: String): List<JsonObject> {
        val root = Plist.parseJson(data) as? JsonObject ?: return emptyList()
        val actions: List<Pair<String?, JsonObject>> = when (val a = root["actions"]) {
            is JsonObject -> a.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
            is JsonArray -> a.mapNotNull { (it as? JsonObject)?.let { o -> null to o } }
            else -> emptyList()
        }
        val phrases = HashMap<String, MutableList<String>>()
        for (s in root.arr("autoShortcuts")) {
            val o = s as? JsonObject ?: continue
            val id = o.str("actionIdentifier") ?: continue
            val templates = o.arr("phraseTemplates").mapNotNull { text(it) }
            phrases.getOrPut(id) { ArrayList() } += templates.map { it.replace("\${applicationName}", appName) }
        }
        return actions.mapNotNull { (key, o) ->
            val id = o.str("identifier") ?: key?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (o.bool("isDiscoverable") == false) return@mapNotNull null
            val title = text(o["title"]) ?: return@mapNotNull null
            val description = text((o["descriptionMetadata"] as? JsonObject)?.get("descriptionText")) ?: text(o["description"])
            val params = o.arr("parameters").mapNotNull { p ->
                val po = p as? JsonObject ?: return@mapNotNull null
                val name = po.str("name") ?: return@mapNotNull null
                buildJsonObject {
                    put("name", clip(name))
                    text(po["title"])?.let { put("title", clip(it)) }
                    if (po.bool("isOptional") == true) put("optional", true)
                }
            }.take(MAX_INTENT_PARAMS)
            buildJsonObject {
                put("id", clip(id))
                put("title", clip(title))
                description?.let { put("description", clip(it)) }
                phrases[id]?.distinct()?.take(MAX_PHRASES)?.takeIf { it.isNotEmpty() }?.let { list ->
                    putJsonArray("phrases") { list.forEach { add(JsonPrimitive(clip(it))) } }
                }
                if (params.isNotEmpty()) put("params", JsonArray(params))
            }
        }
    }

    /** `UIApplicationShortcutItems` (static Home Screen quick actions): `{type, title, subtitle?}`. */
    fun quickActions(info: JsonObject, localize: (String) -> String = { it }): List<JsonObject> =
        info.arr("UIApplicationShortcutItems").mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val type = o.str("UIApplicationShortcutItemType") ?: return@mapNotNull null
            val title = o.str("UIApplicationShortcutItemTitle")?.let(localize) ?: return@mapNotNull null
            buildJsonObject {
                put("type", clip(type))
                put("title", clip(title))
                o.str("UIApplicationShortcutItemSubtitle")?.let(localize)?.let { put("subtitle", clip(it)) }
            }
        }.take(MAX_ITEMS)

    /** `CFBundleDocumentTypes`: `{name, types, role?}` (types are UTIs such as `public.mp3`). */
    fun documentTypes(info: JsonObject, localize: (String) -> String = { it }): List<JsonObject> =
        info.arr("CFBundleDocumentTypes").mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val types = o.strings("LSItemContentTypes").ifEmpty { o.strings("CFBundleTypeExtensions") }
            val name = o.str("CFBundleTypeName")?.let(localize) ?: types.firstOrNull() ?: return@mapNotNull null
            buildJsonObject {
                put("name", clip(name))
                if (types.isNotEmpty()) put("types", buildJsonArray { types.take(10).forEach { add(JsonPrimitive(clip(it))) } })
                o.str("CFBundleTypeRole")?.let { put("role", it.lowercase(Locale.ROOT)) }
            }
        }.take(MAX_ITEMS)

    /** A plain or localized string: a string, or an object's `key` / `defaultValue` / `value` (nested at most 3 deep). */
    fun text(e: JsonElement?, depth: Int = 0): String? {
        if (e == null || depth > 3) return null
        return when (e) {
            is JsonPrimitive -> e.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            is JsonObject -> sequenceOf("key", "defaultValue", "value", "string").mapNotNull { text(e[it], depth + 1) }.firstOrNull()
            else -> null
        }
    }

    private fun clip(s: String): String = if (s.length <= MAX_TEXT) s else s.take(MAX_TEXT - 1) + "…"

    /** Words of the declared domains (`open.spotify.com` → `spotify`), as keywords. */
    private fun hostWords(domains: List<String>): List<String> =
        domains.flatMap { d -> d.removePrefix("*.").split('.').dropLast(1) }
            .filter { it.length >= 3 && it !in setOf("www", "app", "open", "link", "links") }
}
