package com.droidhands.core.auth

/**
 * Agent token syntax (docs/PROTOCOL.md §2): `pb1.<deviceId>.<tokenId>.<tokenSecret>`.
 * Pure functions; no verification against stored tokens (see [TokenManager.authenticate]).
 */
object TokenFormat {
    const val PREFIX = "pb1"

    /** The full-token regex from the protocol spec. */
    val REGEX = Regex("^pb1\\.([a-z2-7]{26})\\.([a-z2-7]{12})\\.([A-Za-z0-9_-]{43})$")

    private val BEARER = Regex("^bearer\\s+(.*)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /** Syntactically valid token parts. [secret] is the base64url text, not decoded. */
    class Parsed(val deviceId: String, val tokenId: String, val secret: String) {
        override fun toString(): String = "Parsed(deviceId=$deviceId, tokenId=$tokenId)"
    }

    fun format(deviceId: String, tokenId: String, secret: String): String = "$PREFIX.$deviceId.$tokenId.$secret"

    /**
     * Extracts the token from an Authorization header value: `Bearer <token>` (scheme
     * case-insensitive, surrounding whitespace ignored) or a bare token. Null if blank.
     */
    fun fromAuthorization(authorization: String?): String? {
        val value = authorization?.trim().orEmpty()
        if (value.isEmpty()) return null
        val token = BEARER.find(value)?.groupValues?.get(1)?.trim() ?: value
        return token.ifEmpty { null }
    }

    /** Parses a bare token, or returns null if it does not match [REGEX]. */
    fun parse(token: String): Parsed? {
        val m = REGEX.matchEntire(token) ?: return null
        return Parsed(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    /** [fromAuthorization] then [parse]. */
    fun parseAuthorization(authorization: String?): Parsed? = fromAuthorization(authorization)?.let(::parse)
}
