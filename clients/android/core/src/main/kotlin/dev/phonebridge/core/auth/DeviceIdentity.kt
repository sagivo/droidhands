package dev.phonebridge.core.auth

import java.security.SecureRandom

/**
 * The device's long-term identity: a 32-byte secret and the self-certifying id derived from it
 * (`base32(SHA-256(secret)[0..16])`, 26 chars; docs/PROTOCOL.md §1).
 *
 * STUB — implemented by the core agent.
 */
class DeviceIdentity(secret: ByteArray) {
    val deviceId: String get() = TODO()

    /** base64url, no padding (43 chars). */
    val secretEncoded: String get() = TODO()

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): DeviceIdentity = TODO()
        fun fromEncoded(secretEncoded: String): DeviceIdentity = TODO()
        fun deriveDeviceId(secret: ByteArray): String = TODO()
    }
}
