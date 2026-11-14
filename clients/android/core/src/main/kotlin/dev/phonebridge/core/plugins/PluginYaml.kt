package dev.phonebridge.core.plugins

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.schema.CoreSchema
import java.math.BigInteger

/**
 * Safe YAML loading for plugin files (pure). Plugin files come from pull requests and, for local plugins,
 * from the user's phone, so the loader accepts only plain YAML 1.2 data:
 *
 * * the Core schema (strings, integers, floats, booleans, null, maps, lists); custom tags such as
 *   `!!java.…` are errors (snakeyaml-engine never instantiates classes);
 * * at most [MAX_CHARS] characters, one document, no duplicate keys, at most [MAX_ALIASES] aliases
 *   and at most [MAX_NODES] values once aliases are expanded (no alias bombs), and no recursive keys;
 *   too-deep nesting is an error, not a crash.
 *
 * The result is a [JsonElement] tree that keeps key order and tells quoted strings (`"5"`) from
 * numbers (`5`). Errors are [PluginFormatException]s with the line and column when known.
 */
object PluginYaml {
    /** Largest accepted file, in characters (256 KB of ASCII). */
    const val MAX_CHARS = 256 * 1024

    /** Most aliases (`*name`) a file may use. */
    const val MAX_ALIASES = 20

    /**
     * Most values (scalars, maps and lists) in the expanded tree. Aliases share nodes, so a few nested
     * ones in a small file could otherwise expand to millions of values and exhaust memory.
     */
    const val MAX_NODES = 200_000

    private fun settings(): LoadSettings = LoadSettings.builder()
        .setSchema(CoreSchema())
        .setAllowDuplicateKeys(false)
        .setAllowRecursiveKeys(false)
        .setMaxAliasesForCollections(MAX_ALIASES)
        .setCodePointLimit(MAX_CHARS)
        .setUseMarks(true)
        .setLabel("plugin.yaml")
        .build()

    /** Parses [text] into JSON-like data. @throws PluginFormatException for anything that isn't plain YAML data. */
    fun load(text: String): JsonElement {
        if (text.length > MAX_CHARS) throw PluginFormatException("the file is larger than ${MAX_CHARS / 1024} KB")
        val raw = try {
            Load(settings()).loadFromString(text)
        } catch (e: YamlEngineException) {
            throw PluginFormatException("not valid YAML: ${e.message?.trim()?.replace(Regex("\\s+"), " ") ?: e.javaClass.simpleName}")
        } catch (e: StackOverflowError) {
            throw PluginFormatException("not valid YAML: nested too deeply")
        } catch (e: RuntimeException) {
            // Anything else the engine throws (it should only throw YamlEngineException) is still a bad file.
            throw PluginFormatException("not valid YAML: ${e.message ?: e.javaClass.simpleName}")
        }
        return toJson(raw, "", 0, intArrayOf(0))
    }

    /** [count] holds the number of values converted so far (see [MAX_NODES]). */
    private fun toJson(value: Any?, path: String, depth: Int, count: IntArray): JsonElement {
        if (depth > MAX_DEPTH) throw PluginFormatException("${where(path)}nested too deeply")
        if (++count[0] > MAX_NODES) throw PluginFormatException("too many values (aliases expand too far)")
        return when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value.toLong())
            is Long -> JsonPrimitive(value)
            is BigInteger -> JsonPrimitive(value)
            is Double -> if (value.isFinite()) JsonPrimitive(value) else throw PluginFormatException("${where(path)}numbers must be finite")
            is Float -> if (value.isFinite()) JsonPrimitive(value.toDouble()) else throw PluginFormatException("${where(path)}numbers must be finite")
            is Number -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(
                buildMap {
                    for ((k, v) in value) {
                        val key = k as? String ?: throw PluginFormatException("${where(path)}keys must be text, found ${describe(k)}")
                        put(key, toJson(v, PluginPath.key(path, key), depth + 1, count))
                    }
                },
            )
            is List<*> -> JsonArray(value.mapIndexed { i, v -> toJson(v, PluginPath.index(path, i), depth + 1, count) })
            else -> throw PluginFormatException("${where(path)}unsupported YAML value (${describe(value)}); use text, numbers, booleans, lists and maps")
        }
    }

    private fun where(path: String) = if (path.isEmpty()) "" else "$path: "

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is Number -> "the number $value"
        is Boolean -> "the boolean $value"
        is Map<*, *> -> "a map"
        is List<*> -> "a list"
        is Set<*> -> "a set"
        is ByteArray -> "binary data"
        else -> value.javaClass.simpleName
    }

    private const val MAX_DEPTH = 32
}

/** A plugin file that isn't plain, well-formed YAML data. */
class PluginFormatException(message: String) : Exception(message)

/** Builds the paths used in plugin error messages, e.g. `actions[1].android.intent.extras["android.intent.extra.TEXT"]`. */
internal object PluginPath {
    private val PLAIN_KEY = Regex("^[A-Za-z0-9_]+$")

    fun key(parent: String, key: String): String {
        val segment = if (PLAIN_KEY.matches(key)) key else "[\"${key.replace("\"", "\\\"")}\"]"
        return when {
            parent.isEmpty() -> if (segment.startsWith("[")) "root$segment" else segment
            segment.startsWith("[") -> "$parent$segment"
            else -> "$parent.$segment"
        }
    }

    fun index(parent: String, index: Int): String = "${parent.ifEmpty { "root" }}[$index]"
}
