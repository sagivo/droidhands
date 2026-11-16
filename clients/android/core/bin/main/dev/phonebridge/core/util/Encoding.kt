package dev.phonebridge.core.util

import java.security.MessageDigest

/**
 * Encodings used by the wire protocols (docs/PROTOCOL.md §1). All functions are pure and
 * thread-safe.
 *
 * * `base32`: RFC 4648 alphabet, lowercase (`a-z2-7`), no padding.
 * * `base64url`: RFC 4648 §5 alphabet (`A-Za-z0-9-_`), no padding.
 */
object Encoding {
    private const val BASE32_ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private const val BASE64URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val HEX_DIGITS = "0123456789abcdef"

    private val BASE32_LOOKUP = reverseLookup(BASE32_ALPHABET)
    private val BASE64URL_LOOKUP = reverseLookup(BASE64URL_ALPHABET)

    /** Lowercase base32 without padding. 16 bytes → 26 chars. */
    fun base32(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(BASE32_ALPHABET[(buffer shr (bits - 5)) and 0x1f])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) out.append(BASE32_ALPHABET[(buffer shl (5 - bits)) and 0x1f])
        return out.toString()
    }

    /**
     * Decodes lowercase, unpadded base32 (uppercase input is accepted too).
     * @throws IllegalArgumentException on characters outside the alphabet, padding, an impossible
     * length, or non-zero trailing bits (non-canonical input).
     */
    fun base32Decode(text: String): ByteArray = decode(text, BASE32_LOOKUP, bitsPerChar = 5, caseInsensitive = true, name = "base32")

    /** base64url without padding. 32 bytes → 43 chars. */
    fun base64Url(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 3 <= bytes.size) {
            val n = (bytes[i].toInt() and 0xff shl 16) or (bytes[i + 1].toInt() and 0xff shl 8) or (bytes[i + 2].toInt() and 0xff)
            out.append(BASE64URL_ALPHABET[n shr 18 and 0x3f]).append(BASE64URL_ALPHABET[n shr 12 and 0x3f])
                .append(BASE64URL_ALPHABET[n shr 6 and 0x3f]).append(BASE64URL_ALPHABET[n and 0x3f])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val n = bytes[i].toInt() and 0xff shl 16
                out.append(BASE64URL_ALPHABET[n shr 18 and 0x3f]).append(BASE64URL_ALPHABET[n shr 12 and 0x3f])
            }
            2 -> {
                val n = (bytes[i].toInt() and 0xff shl 16) or (bytes[i + 1].toInt() and 0xff shl 8)
                out.append(BASE64URL_ALPHABET[n shr 18 and 0x3f]).append(BASE64URL_ALPHABET[n shr 12 and 0x3f])
                    .append(BASE64URL_ALPHABET[n shr 6 and 0x3f])
            }
        }
        return out.toString()
    }

    /**
     * Decodes unpadded base64url.
     * @throws IllegalArgumentException on characters outside the alphabet (including `+`, `/`
     * and `=` padding), an impossible length, or non-zero trailing bits (non-canonical input).
     */
    fun base64UrlDecode(text: String): ByteArray = decode(text, BASE64URL_LOOKUP, bitsPerChar = 6, caseInsensitive = false, name = "base64url")

    /** SHA-256 digest (32 bytes). */
    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Lowercase hexadecimal. */
    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for ((i, b) in bytes.withIndex()) {
            out[i * 2] = HEX_DIGITS[(b.toInt() shr 4) and 0xf]
            out[i * 2 + 1] = HEX_DIGITS[b.toInt() and 0xf]
        }
        return String(out)
    }

    /**
     * Compares two byte arrays in time that depends only on their lengths, not their contents.
     * Arrays of different length are unequal (lengths are not secret here: hashes are fixed-size).
     */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    /** [constantTimeEquals] over the UTF-8 bytes of two strings (e.g. hex digests). */
    fun constantTimeEquals(a: String, b: String): Boolean = constantTimeEquals(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private fun reverseLookup(alphabet: String): IntArray = IntArray(128) { -1 }.also { table ->
        alphabet.forEachIndexed { index, c -> table[c.code] = index }
    }

    private fun decode(text: String, lookup: IntArray, bitsPerChar: Int, caseInsensitive: Boolean, name: String): ByteArray {
        val totalBits = text.length.toLong() * bitsPerChar
        val leftover = (totalBits % 8).toInt()
        // A leftover of a whole character (or more) means the input length cannot come from the encoder.
        require(leftover < bitsPerChar) { "Invalid $name length ${text.length}" }
        val out = ByteArray((totalBits / 8).toInt())
        var buffer = 0
        var bits = 0
        var pos = 0
        for (raw in text) {
            val c = if (caseInsensitive) raw.lowercaseChar() else raw
            val value = if (c.code < 128) lookup[c.code] else -1
            require(value >= 0) { "Invalid $name character '${raw}'" }
            buffer = (buffer shl bitsPerChar) or value
            bits += bitsPerChar
            if (bits >= 8) {
                out[pos++] = (buffer shr (bits - 8)).toByte()
                bits -= 8
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        require(buffer == 0) { "Non-canonical $name: trailing bits must be zero" }
        return out
    }
}
