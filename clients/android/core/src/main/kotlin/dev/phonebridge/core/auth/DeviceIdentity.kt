package dev.phonebridge.core.auth

import dev.phonebridge.core.util.Encoding
import java.security.SecureRandom

/**
 * The device's long-term identity: a 32-byte secret and the self-certifying id derived from it
 * (`base32(SHA-256(secret)[0..16])`, 26 chars; docs/PROTOCOL.md §1).
 *
 * Immutable and thread-safe. [toString] never includes the secret.
 *
 * @throws IllegalArgumentException if [secret] is not exactly [SECRET_BYTES] long.
 */
class DeviceIdentity(secret: ByteArray) {
    private val secret: ByteArray = secret.copyOf()

    init {
        require(secret.size == SECRET_BYTES) { "Device secret must be $SECRET_BYTES bytes, got ${secret.size}" }
    }

    /** 26 chars `[a-z2-7]`. */
    val deviceId: String = deriveDeviceId(this.secret)

    /** base64url, no padding (43 chars). Sent only to the relay in the `hello` frame. */
    val secretEncoded: String get() = Encoding.base64Url(secret)

    override fun toString(): String = "DeviceIdentity(deviceId=$deviceId)"

    companion object {
        const val SECRET_BYTES = 32

        private val ENCODED_SECRET = Regex("^[A-Za-z0-9_-]{43}$")
        private val DEVICE_ID = Regex("^[a-z2-7]{26}$")

        fun generate(random: SecureRandom = SecureRandom()): DeviceIdentity =
            DeviceIdentity(ByteArray(SECRET_BYTES).also(random::nextBytes))

        /**
         * Restores an identity from [secretEncoded] (as produced by [DeviceIdentity.secretEncoded]).
         * @throws IllegalArgumentException unless it is canonical 43-char base64url of 32 bytes.
         */
        fun fromEncoded(secretEncoded: String): DeviceIdentity {
            require(ENCODED_SECRET.matches(secretEncoded)) { "Device secret must be 43 base64url characters" }
            val bytes = try {
                Encoding.base64UrlDecode(secretEncoded)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Device secret is not canonical base64url", e)
            }
            return DeviceIdentity(bytes)
        }

        /** `base32(SHA-256(secret)[0..16])`. */
        fun deriveDeviceId(secret: ByteArray): String = Encoding.base32(Encoding.sha256(secret).copyOf(16))

        /** True if [id] has the device-id shape (26 chars `[a-z2-7]`). */
        fun isValidDeviceId(id: String): Boolean = DEVICE_ID.matches(id)
    }
}
