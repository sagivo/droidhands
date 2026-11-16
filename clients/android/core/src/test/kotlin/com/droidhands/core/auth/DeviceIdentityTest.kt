package com.droidhands.core.auth

import com.droidhands.core.util.Encoding
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DeviceIdentityTest {
    private val vectorSecret = ByteArray(32) { it.toByte() }

    @Test
    fun matchesProtocolVector() {
        val identity = DeviceIdentity(vectorSecret)
        assertEquals("mmg42klgyqzwneiskrelxms3j4", identity.deviceId)
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", identity.secretEncoded)
        assertEquals("mmg42klgyqzwneiskrelxms3j4", DeviceIdentity.deriveDeviceId(vectorSecret))
    }

    @Test
    fun derivedKeysAreStableAndSeparatedByPurpose() {
        val identity = DeviceIdentity(vectorSecret)
        val key = identity.deriveKey("local-oauth-clients")
        assertEquals(32, key.size)
        val expected = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(vectorSecret, "HmacSHA256"))
            doFinal("droidhands:local-oauth-clients".toByteArray())
        }
        assertEquals(Encoding.hex(expected), Encoding.hex(key))
        assertEquals(Encoding.hex(key), Encoding.hex(DeviceIdentity(vectorSecret).deriveKey("local-oauth-clients")))
        assertNotEquals(Encoding.hex(key), Encoding.hex(identity.deriveKey("other")))
        assertNotEquals(Encoding.hex(key), Encoding.hex(DeviceIdentity.generate().deriveKey("local-oauth-clients")))
    }

    @Test
    fun fromEncodedRoundTrips() {
        val identity = DeviceIdentity.fromEncoded("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
        assertEquals("mmg42klgyqzwneiskrelxms3j4", identity.deviceId)
        val generated = DeviceIdentity.generate()
        val restored = DeviceIdentity.fromEncoded(generated.secretEncoded)
        assertEquals(generated.deviceId, restored.deviceId)
        assertEquals(generated.secretEncoded, restored.secretEncoded)
    }

    @Test
    fun generateProducesValidDistinctIds() {
        val random = SecureRandom()
        val ids = (1..50).map { DeviceIdentity.generate(random) }
        ids.forEach {
            assertTrue(Regex("^[a-z2-7]{26}$").matches(it.deviceId), it.deviceId)
            assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(it.secretEncoded))
            assertTrue(DeviceIdentity.isValidDeviceId(it.deviceId))
        }
        assertEquals(50, ids.map { it.deviceId }.toSet().size)
    }

    @Test
    fun fromEncodedRejectsMalformedSecrets() {
        val good = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
        listOf(
            good.dropLast(1), // 42 chars
            good + "A", // 44 chars
            "$good=", // padding
            good.replace('A', '+'), // standard alphabet
            good.dropLast(1) + "9", // non-zero trailing bits (non-canonical)
            "",
        ).forEach { bad -> assertFailsWith<IllegalArgumentException>(bad) { DeviceIdentity.fromEncoded(bad) } }
    }

    @Test
    fun constructorRequires32Bytes() {
        assertFailsWith<IllegalArgumentException> { DeviceIdentity(ByteArray(31)) }
        assertFailsWith<IllegalArgumentException> { DeviceIdentity(ByteArray(33)) }
    }

    @Test
    fun copiesSecretAndHidesItFromToString() {
        val secret = vectorSecret.copyOf()
        val identity = DeviceIdentity(secret)
        secret.fill(9)
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", identity.secretEncoded)
        assertFalse(identity.toString().contains(identity.secretEncoded))
        assertTrue(identity.toString().contains(identity.deviceId))
        assertNotEquals(DeviceIdentity.generate().deviceId, identity.deviceId)
    }
}
