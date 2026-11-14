package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.ToolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.math.BigDecimal

/**
 * Action parameters (docs/PLUGINS.md §5): the JSON Schema agents see, and the check every call goes
 * through before anything runs. Pure.
 */
object ParamValues {
    /**
     * Checks [args] against [params] and returns the values to use, in declaration order: defaults filled
     * in, values typed (integers as whole numbers, numbers as doubles), parameters without a value left out.
     *
     * Lenient like the built-in tools (agents sometimes send `"5"` or `"true"` as text): an integer may come
     * as text of a whole number, a number as numeric text, a boolean as `"true"`/`"false"`, and a string
     * parameter accepts a number or boolean as its text. JSON null counts as absent, and so does a blank
     * string for a required parameter.
     *
     * @throws ToolException naming the parameter: unknown parameters, missing required ones, wrong types,
     *   values outside `enum`, lengths, bounds or `pattern`.
     */
    fun validate(params: List<ParamSpec>, args: JsonObject): JsonObject {
        val byName = params.associateBy { it.name }
        val unknown = args.keys.filter { it !in byName }
        if (unknown.isNotEmpty()) {
            val names = unknown.joinToString(", ") { "'$it'" }
            val takes = if (params.isEmpty()) "This action takes no parameters." else "This action takes: ${describe(params)}."
            throw ToolException("Unknown parameter${if (unknown.size > 1) "s" else ""} $names. $takes")
        }
        val out = LinkedHashMap<String, JsonElement>()
        for (spec in params) {
            val raw = args[spec.name]
            val given = raw != null && raw !is JsonNull && !(spec.type == ParamType.STRING && spec.required && raw is JsonPrimitive && raw.isString && raw.content.isBlank())
            if (!given) {
                when {
                    spec.default != null -> out[spec.name] = spec.default
                    spec.required -> throw ToolException("Missing required parameter '${spec.name}' (${spec.description}).")
                }
                continue
            }
            val value = coerce(spec, raw!!) ?: throw ToolException(
                "Parameter '${spec.name}' must be ${article(spec.type)}, got ${shown(raw)}.",
            )
            check(spec, value)?.let { throw ToolException("Parameter '${spec.name}' $it.") }
            out[spec.name] = value
        }
        return JsonObject(out)
    }

    /** Null if [value] (already of [spec]'s type) satisfies its constraints; otherwise what is wrong, e.g. "must be at most 200 characters". */
    fun check(spec: ParamSpec, value: JsonPrimitive): String? {
        spec.enum?.let { allowed ->
            if (allowed.none { sameValue(it, value) }) return "must be one of ${allowed.joinToString(", ") { Templates.text(it) }}"
        }
        when (spec.type) {
            ParamType.STRING -> {
                val s = value.content
                val length = s.codePointCount(0, s.length)
                spec.minLength?.let { if (length < it) return "must be at least $it characters" }
                if (length > spec.effectiveMaxLength) return "must be at most ${spec.effectiveMaxLength} characters (got $length)"
                spec.pattern?.let { p ->
                    val matches = try {
                        Regex(p).matches(s)
                    } catch (e: Exception) {
                        false
                    }
                    if (!matches) return "doesn't have the expected format (it must match $p)"
                }
            }
            ParamType.INTEGER, ParamType.NUMBER -> {
                val d = value.doubleOrNull ?: return "must be a number"
                spec.minimum?.let { if (d < it) return "must be at least ${number(it)}" }
                spec.maximum?.let { if (d > it) return "must be at most ${number(it)}" }
            }
            ParamType.BOOLEAN -> Unit
        }
        return null
    }

    /** [raw] as [spec]'s type, or null if it isn't one (see [validate] for what is accepted). */
    private fun coerce(spec: ParamSpec, raw: JsonElement): JsonPrimitive? {
        val p = raw as? JsonPrimitive ?: return null
        return when (spec.type) {
            ParamType.STRING -> JsonPrimitive(p.content)
            ParamType.INTEGER -> {
                val n = if (p.isString) p.content.trim().toLongOrNull() else p.longOrNull ?: p.doubleOrNull?.takeIf { it == Math.floor(it) && kotlin.math.abs(it) < 9.0E15 }?.toLong()
                n?.let(::JsonPrimitive)
            }
            ParamType.NUMBER -> {
                val d = if (p.isString) p.content.trim().toDoubleOrNull() else p.takeIf { it.booleanOrNull == null }?.doubleOrNull
                d?.takeIf { it.isFinite() }?.let(::JsonPrimitive)
            }
            ParamType.BOOLEAN -> {
                val b = if (p.isString) {
                    when (p.content.trim().lowercase()) {
                        "true" -> true
                        "false" -> false
                        else -> null
                    }
                } else {
                    p.booleanOrNull
                }
                b?.let(::JsonPrimitive)
            }
        }
    }

    /** Enum membership: strings by text, numbers by value (so 2 equals 2.0). */
    internal fun sameValue(a: JsonPrimitive, b: JsonPrimitive): Boolean {
        if (a.isString || b.isString) return a.isString && b.isString && a.content == b.content
        val x = a.content.toBigDecimalOrNull()
        val y = b.content.toBigDecimalOrNull()
        return if (x != null && y != null) x.compareTo(y) == 0 else a.content == b.content
    }

    /** "query (required), mode" for error messages. */
    fun describe(params: List<ParamSpec>): String =
        params.joinToString(", ") { if (it.required) "${it.name} (required)" else it.name }

    private fun article(type: ParamType) = when (type) {
        ParamType.STRING -> "text"
        ParamType.INTEGER -> "a whole number"
        ParamType.NUMBER -> "a number"
        ParamType.BOOLEAN -> "true or false"
    }

    private fun shown(e: JsonElement): String = when (e) {
        is JsonObject -> "an object"
        is JsonArray -> "a list"
        is JsonPrimitive -> if (e.isString) "\"${e.content.take(40)}\"" else e.content
    }

    internal fun number(d: Double): String = BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
}

/** JSON Schemas for action parameters (§5). Pure. */
object ParamSchemas {
    /**
     * `{"type":"object","properties":{…},"required":[…],"additionalProperties":false}` for [params], in
     * declaration order. Each property has `type`, `description` and, when declared, `enum`, `default`,
     * `minLength`/`maxLength` (strings always get `maxLength`), `minimum`/`maximum` and `pattern`.
     */
    fun objectSchema(params: List<ParamSpec>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for (p in params) put(p.name, property(p))
        }
        val required = params.filter { it.required }
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it.name)) } })
        put("additionalProperties", false)
    }

    private fun property(p: ParamSpec): JsonObject = buildJsonObject {
        put("type", p.type.id)
        put("description", p.description)
        p.enum?.let { values -> put("enum", JsonArray(values)) }
        p.default?.let { put("default", it) }
        if (p.type == ParamType.STRING) {
            p.minLength?.let { put("minLength", it) }
            put("maxLength", p.effectiveMaxLength)
            p.pattern?.let { put("pattern", it) }
        }
        p.minimum?.let { put("minimum", numberJson(it, p.type)) }
        p.maximum?.let { put("maximum", numberJson(it, p.type)) }
    }

    private fun numberJson(d: Double, type: ParamType): JsonPrimitive =
        if (type == ParamType.INTEGER && d == Math.floor(d) && kotlin.math.abs(d) < 9.0E15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
}
