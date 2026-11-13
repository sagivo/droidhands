package dev.phonebridge.android.tools.system

import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.optObject
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.optStringList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Locale

/** A typed intent extra converted from JSON (`intent_start.extras`). */
sealed interface ExtraValue {
    data class Str(val value: String) : ExtraValue

    data class Bool(val value: Boolean) : ExtraValue

    data class IntValue(val value: Int) : ExtraValue

    data class LongValue(val value: Long) : ExtraValue

    data class DoubleValue(val value: Double) : ExtraValue

    data class StrList(val values: List<String>) : ExtraValue
}

/** Validated, platform-neutral description of the activity `intent_start` will start. */
data class IntentSpec(
    val action: String,
    val data: String? = null,
    /** Lowercase MIME type, e.g. "image/png" or "text/plain". */
    val mimeType: String? = null,
    val packageName: String? = null,
    /** Fully qualified class name (relative names already expanded against [packageName]). */
    val className: String? = null,
    val categories: List<String> = emptyList(),
    val extras: Map<String, ExtraValue> = emptyMap(),
)

/** Parses and validates `intent_start` arguments (pure; the Android Intent is built by IntentFactory). */
object IntentSpecParser {
    const val MAX_EXTRAS = 50

    /** Per value and for all extras together. */
    const val MAX_STRING_CHARS = 100_000
    private const val MAX_DATA_CHARS = 8_192

    private val ACTION_SHORT = Regex("^[A-Z][A-Z0-9_]*$")
    private val NAME = Regex("^[A-Za-z0-9_.$]+$")
    private val MIME = Regex("^[a-z0-9*][a-z0-9!#$&^_.+*-]*/[a-z0-9*][a-z0-9!#$&^_.+*-]*$")

    /**
     * Builds an [IntentSpec] from tool arguments. [ownPackage] (PhoneBridge itself) can never be
     * targeted: its non-exported components would otherwise be reachable as a confused deputy.
     */
    fun parse(args: JsonObject, ownPackage: String): IntentSpec {
        val action = expandAction(args.optString("action")?.trim() ?: throw ToolException("Missing required argument 'action'"))
        val data = args.optString("data")?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > MAX_DATA_CHARS) throw ToolException("Argument 'data' is too long (max $MAX_DATA_CHARS characters).")
            if (it.any(Character::isISOControl)) throw ToolException("Argument 'data' must not contain control characters.")
        }
        val mimeType = args.optString("mime_type")?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }?.also {
            if (!MIME.matches(it)) throw ToolException("Argument 'mime_type' must look like type/subtype (e.g. text/plain, image/*), got '$it'.")
        }
        val packageName = args.optString("package")?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (!isValidPackageName(it)) throw ToolException("Argument 'package' is not a valid Android package name: '$it'.")
        }
        val className = args.optString("class_name")?.trim()?.takeIf { it.isNotEmpty() }?.let { cls ->
            packageName ?: throw ToolException("Argument 'class_name' needs 'package' too (the app that owns the activity).")
            resolveClassName(packageName, cls)
        }
        if (packageName == ownPackage || className?.startsWith("$ownPackage.") == true) {
            throw ToolException("intent_start cannot target PhoneBridge itself.")
        }
        val categories = args.optStringList("categories").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.map(::expandCategory).distinct()
        val extras = convertExtras(args.optObject("extras"))
        return IntentSpec(action, data, mimeType, packageName, className, categories, extras)
    }

    /** "VIEW" -> "android.intent.action.VIEW"; dotted names are kept as given. */
    fun expandAction(raw: String): String {
        if (raw.isEmpty()) throw ToolException("Missing required argument 'action'")
        if (ACTION_SHORT.matches(raw)) return "android.intent.action.$raw"
        if (!NAME.matches(raw)) throw ToolException("Argument 'action' is not a valid intent action: '$raw'.")
        return raw
    }

    /** "BROWSABLE" -> "android.intent.category.BROWSABLE"; dotted names are kept as given. */
    fun expandCategory(raw: String): String {
        if (ACTION_SHORT.matches(raw)) return "android.intent.category.$raw"
        if (!NAME.matches(raw)) throw ToolException("Argument 'categories' contains an invalid category: '$raw'.")
        return raw
    }

    /** Manifest rules: ".Foo" and "Foo" are relative to [packageName]; dotted names are absolute. */
    fun resolveClassName(packageName: String, className: String): String {
        if (!NAME.matches(className) || className.endsWith('.')) {
            throw ToolException("Argument 'class_name' is not a valid class name: '$className'.")
        }
        return when {
            className.startsWith('.') -> packageName + className
            '.' !in className -> "$packageName.$className"
            else -> className
        }
    }

    /**
     * JSON extras -> typed values: strings, booleans, integral numbers (Int when it fits, else
     * Long), other numbers (Double) and arrays of strings. Nested objects, nulls and mixed arrays
     * are rejected: there is no unambiguous Android type for them.
     */
    fun convertExtras(extras: JsonObject?): Map<String, ExtraValue> {
        if (extras == null || extras.isEmpty()) return emptyMap()
        if (extras.size > MAX_EXTRAS) throw ToolException("Too many extras (${extras.size}); at most $MAX_EXTRAS.")
        val out = LinkedHashMap<String, ExtraValue>()
        var totalChars = 0
        for ((key, value) in extras) {
            if (key.isBlank() || key.length > 256) throw ToolException("Extra keys must be 1-256 characters, got '${truncate(key, 40)}'.")
            val converted = convertValue(key, value)
            totalChars += key.length + when (converted) {
                is ExtraValue.Str -> converted.value.length
                is ExtraValue.StrList -> converted.values.sumOf { it.length }
                else -> 0
            }
            // Android's binder transaction limit is ~1 MB for the whole intent.
            if (totalChars > MAX_STRING_CHARS) throw ToolException("Extras are too large (more than $MAX_STRING_CHARS characters in total).")
            out[key] = converted
        }
        return out
    }

    private fun convertValue(key: String, value: JsonElement): ExtraValue = when (value) {
        JsonNull -> throw ToolException("Extra '$key' is null; omit it or pass a string, number, boolean or array of strings.")
        is JsonObject -> throw ToolException("Extra '$key' is an object; only strings, numbers, booleans and arrays of strings are supported.")
        is JsonArray -> ExtraValue.StrList(
            value.map { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.also { checkLength(key, it) }
                    ?: throw ToolException("Extra '$key' must be an array of strings only.")
            },
        )
        is JsonPrimitive -> convertPrimitive(key, value)
    }

    private fun convertPrimitive(key: String, p: JsonPrimitive): ExtraValue {
        if (p.isString) return ExtraValue.Str(checkLength(key, p.content))
        p.booleanOrNull?.let { return ExtraValue.Bool(it) }
        val text = p.content
        val integral = text.none { it == '.' || it == 'e' || it == 'E' }
        if (integral) {
            p.longOrNull?.let { n ->
                return if (n in Int.MIN_VALUE..Int.MAX_VALUE) ExtraValue.IntValue(n.toInt()) else ExtraValue.LongValue(n)
            }
        }
        val d = p.doubleOrNull?.takeIf { it.isFinite() } ?: throw ToolException("Extra '$key' has an unsupported number '$text'.")
        return ExtraValue.DoubleValue(d)
    }

    private fun checkLength(key: String, s: String): String {
        if (s.length > MAX_STRING_CHARS) throw ToolException("Extra '$key' is too long (max $MAX_STRING_CHARS characters).")
        return s
    }

    /** Human summary for the confirmation prompt (one line, bounded). */
    fun describe(spec: IntentSpec): String {
        val parts = buildList {
            add("action=${spec.action}")
            spec.data?.let { add("data=${truncate(it, 120)}") }
            spec.mimeType?.let { add("type=$it") }
            spec.packageName?.let { add("package=$it") }
            spec.className?.let { add("class=$it") }
            if (spec.categories.isNotEmpty()) add("categories=${spec.categories.joinToString(",", "[", "]")}")
            if (spec.extras.isNotEmpty()) {
                add("extras={" + spec.extras.entries.joinToString(", ") { (k, v) -> "$k=${describeValue(v)}" } + "}")
            }
        }
        return truncate("Start Android activity: " + parts.joinToString(", "), 700)
    }

    private fun describeValue(v: ExtraValue): String = when (v) {
        is ExtraValue.Str -> quoteForSummary(v.value, 60)
        is ExtraValue.Bool -> v.value.toString()
        is ExtraValue.IntValue -> v.value.toString()
        is ExtraValue.LongValue -> "${v.value}L"
        is ExtraValue.DoubleValue -> v.value.toString()
        is ExtraValue.StrList -> v.values.joinToString(", ", "[", "]") { quoteForSummary(it, 30) }
    }
}
