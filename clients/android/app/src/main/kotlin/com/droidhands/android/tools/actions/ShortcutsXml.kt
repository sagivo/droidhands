package com.droidhands.android.tools.actions

import org.xmlpull.v1.XmlPullParser

/** What an app declares in its `shortcuts.xml`: static launcher shortcuts and App Actions capabilities. */
data class ShortcutsDeclaration(val shortcuts: List<DeclaredShortcut> = emptyList(), val capabilities: List<DeclaredCapability> = emptyList())

/** A static `<shortcut>`. [intent] is the one it starts (the last `<intent>`; earlier ones only form the back stack). */
data class DeclaredShortcut(
    val id: String,
    val enabled: Boolean = true,
    val shortLabel: String? = null,
    val longLabel: String? = null,
    val intent: DeclaredIntent? = null,
)

/** An App Actions `<capability>` (e.g. `actions.intent.GET_THING`) with its `<intent>`s, in declaration order. */
data class DeclaredCapability(val name: String, val intents: List<DeclaredIntent> = emptyList())

/** An `<intent>` of a shortcut or capability. Every value is as declared (resource references resolved). */
data class DeclaredIntent(
    val action: String? = null,
    val targetPackage: String? = null,
    val targetClass: String? = null,
    val data: String? = null,
    val mimeType: String? = null,
    val categories: List<String> = emptyList(),
    /** `<extra android:name|key android:value>`, in declaration order. */
    val extras: Map<String, String> = emptyMap(),
    /** Capabilities: `<url-template android:value>` (RFC 6570). */
    val urlTemplate: String? = null,
    /** Capabilities: `<parameter android:name android:key>`. */
    val parameters: List<DeclaredParameter> = emptyList(),
)

/** A capability parameter: [name] is the built-in intent's (e.g. `thing.name`), [key] what the app receives. */
data class DeclaredParameter(val name: String?, val key: String, val required: Boolean = false)

/**
 * Reads `shortcuts.xml` (the `android.app.shortcuts` meta-data of a launcher activity) from an [XmlPullParser]
 * (pure): on the phone Android's compiled-XML parser, in tests any XmlPullParser. Unknown elements (slices,
 * capability bindings, ...) are skipped. Bounded: at most [MAX_ELEMENTS] elements are read, and at most
 * [MAX_ITEMS] shortcuts and capabilities kept.
 */
object ShortcutsXml {
    /** The meta-data name launcher activities declare their shortcuts under. */
    const val META_DATA = "android.app.shortcuts"
    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    const val MAX_ELEMENTS = 5_000
    const val MAX_ITEMS = 100

    /**
     * Parses the document [parser] is positioned at (START_DOCUMENT). Attribute values that are resource
     * references (`@2131755203` in compiled XML) go through [resolve], which returns their text or null.
     * @throws org.xmlpull.v1.XmlPullParserException or java.io.IOException on malformed XML.
     */
    fun parse(parser: XmlPullParser, resolve: (String) -> String? = { null }): ShortcutsDeclaration {
        val shortcuts = ArrayList<DeclaredShortcut>()
        val capabilities = ArrayList<DeclaredCapability>()
        var shortcut: DeclaredShortcut? = null
        var capability: DeclaredCapability? = null
        var intent: IntentBuilder? = null
        var elements = 0

        fun attr(name: String): String? {
            val raw = parser.getAttributeValue(ANDROID_NS, name)?.trim() ?: return null
            val value = if (raw.startsWith("@") && raw != "@null") resolve(raw) else raw
            return value?.trim()?.takeIf { it.isNotEmpty() && it != "@null" }
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                if (++elements > MAX_ELEMENTS) break
                when (parser.name) {
                    "shortcut" -> if (parser.depth == 2) {
                        shortcut = attr("shortcutId")?.let { id ->
                            DeclaredShortcut(
                                id = id,
                                enabled = attr("enabled")?.lowercase() != "false",
                                shortLabel = attr("shortcutShortLabel"),
                                longLabel = attr("shortcutLongLabel"),
                            )
                        }
                    }
                    "capability" -> if (parser.depth == 2) capability = attr("name")?.let { DeclaredCapability(it) }
                    "intent" -> if (parser.depth == 3 && (shortcut != null || capability != null)) {
                        intent = IntentBuilder(attr("action"), attr("targetPackage"), attr("targetClass"), attr("data"), attr("mimeType"))
                    }
                    "categories" -> if (parser.depth == 4) attr("name")?.let { intent?.categories?.add(it) }
                    "extra" -> if (parser.depth == 4) {
                        val key = attr("name") ?: attr("key")
                        val value = attr("value")
                        if (key != null && value != null) intent?.extras?.put(key, value)
                    }
                    "url-template" -> if (parser.depth == 4) intent?.let { it.urlTemplate = it.urlTemplate ?: attr("value") }
                    "parameter" -> if (parser.depth == 4) {
                        attr("key")?.let { key ->
                            intent?.parameters?.add(DeclaredParameter(attr("name"), key, attr("required")?.lowercase() == "true"))
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                when (parser.name) {
                    "intent" -> if (parser.depth == 3) {
                        val built = intent?.build()
                        intent = null
                        if (built != null) {
                            shortcut = shortcut?.copy(intent = built)
                            capability = capability?.let { it.copy(intents = it.intents + built) }
                        }
                    }
                    "shortcut" -> if (parser.depth == 2) {
                        shortcut?.takeIf { shortcuts.size < MAX_ITEMS }?.let(shortcuts::add)
                        shortcut = null
                    }
                    "capability" -> if (parser.depth == 2) {
                        capability?.takeIf { capabilities.size < MAX_ITEMS }?.let(capabilities::add)
                        capability = null
                    }
                }
            }
            event = parser.next()
        }
        return ShortcutsDeclaration(shortcuts, capabilities)
    }

    private class IntentBuilder(
        val action: String?,
        val targetPackage: String?,
        val targetClass: String?,
        val data: String?,
        val mimeType: String?,
    ) {
        val categories = ArrayList<String>()
        val extras = LinkedHashMap<String, String>()
        val parameters = ArrayList<DeclaredParameter>()
        var urlTemplate: String? = null

        fun build() = DeclaredIntent(action, targetPackage, targetClass, data, mimeType, categories.toList(), extras.toMap(), urlTemplate, parameters.toList())
    }
}
