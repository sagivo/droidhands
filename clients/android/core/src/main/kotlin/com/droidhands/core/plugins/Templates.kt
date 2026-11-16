package com.droidhands.core.plugins

import com.droidhands.core.tools.ToolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal

/**
 * A recipe template (docs/PLUGINS.md §6.1): text with `{{name}}` placeholders (spaces inside the braces
 * allowed). No other syntax: no logic, filters or escapes. Immutable.
 */
class Template private constructor(val source: String, val parts: List<Part>) {
    sealed interface Part {
        data class Text(val text: String) : Part

        data class Param(val name: String) : Part
    }

    /** Parameter names used, in order of first use. */
    val paramNames: List<String> = parts.filterIsInstance<Part.Param>().map { it.name }.distinct()

    /** The parameter name when the whole template is exactly one placeholder (`"{{minutes}}"`), else null. */
    val singleParam: String? = (parts.singleOrNull() as? Part.Param)?.name

    /**
     * Substitutes [values] (validated parameters). A parameter without a value becomes "". In a URL
     * context ([urlEncode]) every value is percent-encoded as UTF-8, everything except `A–Z a–z 0–9 - . _ ~`.
     */
    fun render(values: JsonObject, urlEncode: Boolean = false): String = buildString {
        for (part in parts) when (part) {
            is Part.Text -> append(part.text)
            is Part.Param -> {
                val value = Templates.valueOf(values, part.name)?.let(Templates::text).orEmpty()
                append(if (urlEncode) Templates.percentEncode(value) else value)
            }
        }
    }

    override fun toString(): String = source

    companion object {
        /** @throws TemplateException for a stray `{{` or `}}` or a placeholder that isn't a parameter name. */
        fun parse(source: String): Template {
            val parts = ArrayList<Part>()
            val text = StringBuilder()
            var i = 0
            while (i < source.length) {
                when {
                    source.startsWith("{{", i) -> {
                        val end = source.indexOf("}}", i + 2)
                        if (end < 0) throw TemplateException("'{{' without a closing '}}'")
                        val inner = source.substring(i + 2, end)
                        if (inner.contains("{{")) throw TemplateException("'{{' inside a placeholder")
                        val name = inner.trim()
                        if (!PluginRules.PARAM_NAME.matches(name)) {
                            throw TemplateException("'{{$inner}}' is not a parameter placeholder (use {{name}} with a parameter name)")
                        }
                        if (text.isNotEmpty()) {
                            parts += Part.Text(text.toString())
                            text.setLength(0)
                        }
                        parts += Part.Param(name)
                        i = end + 2
                    }
                    source.startsWith("}}", i) -> throw TemplateException("'}}' without an opening '{{'")
                    else -> {
                        text.append(source[i])
                        i++
                    }
                }
            }
            if (text.isNotEmpty()) parts += Part.Text(text.toString())
            return Template(source, parts)
        }

        /** A template with no placeholders, for literal text (expected values in examples). */
        fun literal(text: String): Template = Template(text, if (text.isEmpty()) emptyList() else listOf(Part.Text(text)))
    }
}

/** A malformed template. */
class TemplateException(message: String) : Exception(message)

/** Template helpers: value text, URL encoding and URL scheme rules (§6.1). Pure. */
object Templates {
    /** Schemes a rendered URL may never use (§6.1). */
    val FORBIDDEN_SCHEMES: Set<String> = setOf(
        "file", "content", "javascript", "data", "intent", "android-app", "about", "blob", "vbscript",
    )

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")

    /** The value of parameter [name] in [values], or null when it has none (absent or JSON null). */
    fun valueOf(values: JsonObject, name: String): JsonPrimitive? =
        (values[name] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }

    /** A parameter value as template text: strings as is, integral numbers without ".0", booleans as true/false. */
    fun text(value: JsonPrimitive): String {
        if (value.isString) return value.content
        value.longOrNull?.let { return it.toString() }
        value.booleanOrNull?.let { return it.toString() }
        value.doubleOrNull?.let { d ->
            return if (d.isFinite()) BigDecimal.valueOf(d).stripTrailingZeros().toPlainString() else d.toString()
        }
        return value.content
    }

    /** Percent-encodes [value] as UTF-8, leaving only `A–Z a–z 0–9 - . _ ~` (RFC 3986 unreserved). */
    fun percentEncode(value: String): String {
        val out = StringBuilder(value.length)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val unreserved = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
                c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
            if (unreserved) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[c shr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    /**
     * Problems with a URL-context template (§6.1): the scheme must be literal (the first `{{` after the
     * first `:`), a valid scheme, and not a forbidden one. Null when fine.
     */
    fun urlTemplateProblem(template: String): String? {
        val colon = template.indexOf(':')
        val brace = template.indexOf("{{")
        if (colon < 0) return "a URL needs a literal scheme such as https: (no ':' found)"
        if (brace in 0 until colon) return "the URL scheme must be literal: the first {{ must come after the first ':'"
        return schemeProblem(template.substring(0, colon))
    }

    /** @throws ToolException if the rendered [url] uses a forbidden or malformed scheme (§6.1). */
    fun requireAllowedUrl(url: String) {
        val colon = url.indexOf(':')
        val problem = if (colon < 0) "it has no scheme" else schemeProblem(url.substring(0, colon))
        if (problem != null) throw ToolException("This action built a link DroidHands won't open ($problem), so nothing was opened.")
    }

    private fun schemeProblem(scheme: String): String? {
        if (!SCHEME.matches(scheme)) return "'$scheme' is not a valid URL scheme"
        if (scheme.lowercase() in FORBIDDEN_SCHEMES) return "the '${scheme.lowercase()}:' scheme is not allowed"
        return null
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
