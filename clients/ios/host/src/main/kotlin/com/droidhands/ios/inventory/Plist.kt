package com.droidhands.ios.inventory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Property lists as JSON (pure). `plutil -convert json` refuses plists with dates or data, so XML plists
 * (`plutil -convert xml1`, `codesign --xml`) are parsed here: dates become their ISO text and data its base64. External
 * DTDs and entities are never loaded.
 */
object Plist {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parses JSON text (plutil's or pymobiledevice3's), or null if it isn't JSON. */
    fun parseJson(bytes: ByteArray): JsonElement? = try {
        json.parseToJsonElement(bytes.toString(Charsets.UTF_8).trim().ifEmpty { return null })
    } catch (e: Exception) {
        null
    }

    /**
     * Parses an XML plist (anything before `<?xml`, such as codesign's blob header, is skipped), or null if it isn't
     * one.
     */
    fun parseXml(bytes: ByteArray): JsonElement? {
        val text = bytes.toString(Charsets.UTF_8)
        val start = text.indexOf("<?xml").takeIf { it >= 0 } ?: text.indexOf("<plist").takeIf { it >= 0 } ?: return null
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                isExpandEntityReferences = false
                isNamespaceAware = false
                isValidating = false
            }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(text.substring(start).toByteArray(Charsets.UTF_8)))
            val root = doc.documentElement ?: return null
            val value = if (root.tagName == "plist") root.childElements().firstOrNull() else root
            value?.let(::element)
        } catch (e: Exception) {
            null
        }
    }

    /** JSON first, then XML. */
    fun parse(bytes: ByteArray): JsonElement? = parseJson(bytes) ?: parseXml(bytes)

    private fun element(e: Element): JsonElement = when (e.tagName) {
        "dict" -> {
            val out = LinkedHashMap<String, JsonElement>()
            val children = e.childElements()
            var i = 0
            while (i < children.size) {
                val key = children[i]
                if (key.tagName == "key" && i + 1 < children.size) {
                    out[key.textContent] = element(children[i + 1])
                    i += 2
                } else {
                    i++
                }
            }
            JsonObject(out)
        }
        "array" -> JsonArray(e.childElements().map(::element))
        "string", "date" -> JsonPrimitive(e.textContent)
        "integer" -> e.textContent.trim().toLongOrNull()?.let(::JsonPrimitive) ?: JsonPrimitive(e.textContent.trim())
        "real" -> e.textContent.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let(::JsonPrimitive) ?: JsonNull
        "true" -> JsonPrimitive(true)
        "false" -> JsonPrimitive(false)
        "data" -> JsonPrimitive(
            runCatching { Base64.getEncoder().encodeToString(Base64.getMimeDecoder().decode(e.textContent.trim())) }.getOrDefault(""),
        )
        else -> JsonNull
    }

    private fun Element.childElements(): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).map(nodes::item).filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }
    }
}

// Defensive readers: Apple's formats vary between tools and OS versions, so a wrong type reads as absent.

/** The string at [key], trimmed, or null when absent, blank or not a string. */
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

/** The boolean at [key] (also "true"/"YES" strings and 0/1), or null. */
fun JsonObject.bool(key: String): Boolean? {
    val p = this[key] as? JsonPrimitive ?: return null
    return when (p.content.lowercase()) {
        "true", "yes", "1" -> true
        "false", "no", "0" -> false
        else -> null
    }
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

/** Strings of the array at [key] (a single string counts as a one-item list). */
fun JsonObject.strings(key: String): List<String> = when (val v = this[key]) {
    is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.takeIf(String::isNotEmpty) }
    is JsonPrimitive -> listOfNotNull(v.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty))
    else -> emptyList()
}
