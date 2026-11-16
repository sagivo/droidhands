package dev.phonebridge.android.tools.actions

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** An element of a decoded binary XML document. Attributes are keyed by their resource id when they have one. */
class XmlElement(val name: String, val attributes: List<XmlAttribute>, val children: List<XmlElement>) {
    /** The attribute with resource id [id] (e.g. `android.R.attr.name`), else the one named [name]. */
    fun attr(id: Int, name: String): XmlAttribute? =
        attributes.firstOrNull { it.resourceId == id } ?: attributes.firstOrNull { it.resourceId == 0 && it.name == name }

    fun children(name: String): List<XmlElement> = children.filter { it.name == name }
}

/** One attribute: its [value] as text, or a [reference] (resource id) to resolve. */
data class XmlAttribute(val name: String, val resourceId: Int, val value: String?, val reference: Int? = null, val bool: Boolean? = null)

/**
 * Decodes Android's compiled binary XML (the `AndroidManifest.xml` inside an APK, "AXML") into an [XmlElement] tree
 * (pure). Android has no public API that lists another app's intent filters, so PhoneBridge reads them from the app's
 * own manifest; this decoder follows the documented chunk format (`ResourceTypes.h`) and nothing app-specific.
 * Bounded by [MAX_ELEMENTS] and [MAX_DEPTH]; anything malformed throws [IllegalArgumentException].
 */
object BinaryXml {
    const val MAX_ELEMENTS = 20_000
    const val MAX_DEPTH = 32

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val UTF8_FLAG = 1 shl 8
    private const val NO_INDEX = -1

    private const val TYPE_REFERENCE = 0x01
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    fun decode(bytes: ByteArray): XmlElement {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 8 && buf.getShort(0).toInt() and 0xFFFF == RES_XML_TYPE) { "not a binary XML document" }
        val headerSize = buf.getShort(2).toInt() and 0xFFFF
        val end = minOf(buf.getInt(4), bytes.size)
        var strings: List<String> = emptyList()
        var resourceIds = IntArray(0)
        val stack = ArrayList<Builder>()
        var root: XmlElement? = null
        var elements = 0
        var pos = headerSize
        while (pos + 8 <= end) {
            val type = buf.getShort(pos).toInt() and 0xFFFF
            val chunkHeader = buf.getShort(pos + 2).toInt() and 0xFFFF
            val size = buf.getInt(pos + 4)
            require(size >= 8 && pos + size <= end) { "bad chunk size" }
            when (type) {
                RES_STRING_POOL_TYPE -> strings = stringPool(buf, pos)
                RES_XML_RESOURCE_MAP_TYPE -> resourceIds = IntArray((size - chunkHeader) / 4) { buf.getInt(pos + chunkHeader + it * 4) }
                RES_XML_START_ELEMENT_TYPE -> {
                    require(++elements <= MAX_ELEMENTS && stack.size < MAX_DEPTH) { "document too large" }
                    stack += startElement(buf, pos + chunkHeader, strings, resourceIds)
                }
                RES_XML_END_ELEMENT_TYPE -> {
                    val done = stack.removeLastOrNull()?.build() ?: throw IllegalArgumentException("unbalanced end tag")
                    if (stack.isEmpty()) root = done else stack.last().children += done
                }
            }
            pos += size
        }
        return root ?: throw IllegalArgumentException("no root element")
    }

    private class Builder(val name: String, val attributes: List<XmlAttribute>) {
        val children = ArrayList<XmlElement>()

        fun build() = XmlElement(name, attributes, children)
    }

    private fun startElement(buf: ByteBuffer, ext: Int, strings: List<String>, resourceIds: IntArray): Builder {
        val name = string(strings, buf.getInt(ext + 4)) ?: ""
        val attributeStart = buf.getShort(ext + 8).toInt() and 0xFFFF
        val attributeSize = buf.getShort(ext + 10).toInt() and 0xFFFF
        val count = buf.getShort(ext + 12).toInt() and 0xFFFF
        require(attributeSize >= 20) { "bad attribute size" }
        val attributes = (0 until count).map { i ->
            val at = ext + attributeStart + i * attributeSize
            val nameIndex = buf.getInt(at + 4)
            val raw = buf.getInt(at + 8)
            val dataType = buf.get(at + 15).toInt() and 0xFF
            val data = buf.getInt(at + 16)
            val resourceId = if (nameIndex in resourceIds.indices) resourceIds[nameIndex] else 0
            val attrName = string(strings, nameIndex) ?: ""
            when (dataType) {
                TYPE_REFERENCE -> XmlAttribute(attrName, resourceId, null, reference = data)
                TYPE_INT_BOOLEAN -> XmlAttribute(attrName, resourceId, (data != 0).toString(), bool = data != 0)
                TYPE_STRING -> XmlAttribute(attrName, resourceId, string(strings, data) ?: string(strings, raw))
                TYPE_INT_DEC -> XmlAttribute(attrName, resourceId, data.toString())
                TYPE_INT_HEX -> XmlAttribute(attrName, resourceId, "0x" + Integer.toHexString(data))
                else -> XmlAttribute(attrName, resourceId, string(strings, raw))
            }
        }
        return Builder(name, attributes)
    }

    private fun string(strings: List<String>, index: Int): String? = if (index == NO_INDEX) null else strings.getOrNull(index)

    private fun stringPool(buf: ByteBuffer, chunk: Int): List<String> {
        val headerSize = buf.getShort(chunk + 2).toInt() and 0xFFFF
        val count = buf.getInt(chunk + 8)
        val flags = buf.getInt(chunk + 16)
        val stringsStart = buf.getInt(chunk + 20)
        require(count in 0..1_000_000) { "bad string pool" }
        val utf8 = flags and UTF8_FLAG != 0
        return (0 until count).map { i ->
            val offset = buf.getInt(chunk + headerSize + i * 4)
            val at = chunk + stringsStart + offset
            if (utf8) utf8String(buf, at) else utf16String(buf, at)
        }
    }

    private fun utf8String(buf: ByteBuffer, start: Int): String {
        var at = start
        // UTF-16 length (skipped), then UTF-8 byte length; each 1 or 2 bytes (high bit = 2 bytes).
        at += if (buf.get(at).toInt() and 0x80 != 0) 2 else 1
        val first = buf.get(at).toInt() and 0xFF
        val length = if (first and 0x80 != 0) {
            (((first and 0x7F) shl 8) or (buf.get(at + 1).toInt() and 0xFF)).also { at += 2 }
        } else {
            first.also { at += 1 }
        }
        val bytes = ByteArray(length)
        for (k in 0 until length) bytes[k] = buf.get(at + k)
        return String(bytes, Charsets.UTF_8)
    }

    private fun utf16String(buf: ByteBuffer, start: Int): String {
        var at = start
        val first = buf.getShort(at).toInt() and 0xFFFF
        val length = if (first and 0x8000 != 0) {
            (((first and 0x7FFF) shl 16) or (buf.getShort(at + 2).toInt() and 0xFFFF)).also { at += 4 }
        } else {
            first.also { at += 2 }
        }
        val chars = CharArray(length) { k -> buf.getChar(at + k * 2) }
        return String(chars)
    }
}
