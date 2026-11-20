package com.droidhands.core.account

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (RFC 7636, S256) for signing in to a DroidHands account: the app keeps [newVerifier] for the
 * attempt it started and sends only its [challenge], so a sign-in code that another app catches on its
 * way back (`droidhands://signed-in?code=…`) is useless without the verifier.
 */
object Pkce {
    const val METHOD = "S256"

    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** 43 base64url characters (32 random bytes). */
    fun newVerifier(random: SecureRandom = SecureRandom()): String = encoder.encodeToString(ByteArray(32).also(random::nextBytes))

    /** base64url(SHA-256(ASCII(verifier))), unpadded. */
    fun challenge(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
