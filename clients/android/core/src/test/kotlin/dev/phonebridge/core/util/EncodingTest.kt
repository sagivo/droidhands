package dev.phonebridge.core.util

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncodingTest {
    private val rfcInputs = listOf("", "f", "fo", "foo", "foob", "fooba", "foobar")

    @Test
    fun base32MatchesRfc4648VectorsLowercasedWithoutPadding() {
        val expected = listOf("", "my", "mzxq", "mzxw6", "mzxw6yq", "mzxw6ytb", "mzxw6ytboi")
        rfcInputs.zip(expected).forEach { (input, out) ->
            assertEquals(out, Encoding.base32(input.toByteArray()), "base32('$input')")
            assertEquals(input, String(Encoding.base32Decode(out)), "base32Decode('$out')")
        }
    }

    @Test
    fun base64UrlMatchesRfc4648VectorsWithoutPadding() {
        val expected = listOf("", "Zg", "Zm8", "Zm9v", "Zm9vYg", "Zm9vYmE", "Zm9vYmFy")
        rfcInputs.zip(expected).forEach { (input, out) ->
            assertEquals(out, Encoding.base64Url(input.toByteArray()), "base64Url('$input')")
            assertEquals(input, String(Encoding.base64UrlDecode(out)), "base64UrlDecode('$out')")
        }
    }

    @Test
    fun base64UrlUsesUrlSafeAlphabet() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xbf.toByte())
        assertEquals("-_-_", Encoding.base64Url(bytes))
        assertContentEquals(bytes, Encoding.base64UrlDecode("-_-_"))
    }

    @Test
    fun roundTripsRandomData() {
        val random = Random(42)
        repeat(200) {
            val bytes = random.nextBytes(random.nextInt(0, 70))
            assertContentEquals(bytes, Encoding.base64UrlDecode(Encoding.base64Url(bytes)))
            assertContentEquals(bytes, Encoding.base32Decode(Encoding.base32(bytes)))
            assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), Encoding.base64Url(bytes))
        }
    }

    @Test
    fun decodersRejectInvalidInput() {
        listOf("Zg==", "Z", "Zm9v+", "Zm/v", "Zh", "Zm9vYmF!", "é").forEach {
            assertFailsWith<IllegalArgumentException>("base64url '$it'") { Encoding.base64UrlDecode(it) }
        }
        listOf("my======", "m", "mzx", "my1", "mz").forEach {
            assertFailsWith<IllegalArgumentException>("base32 '$it'") { Encoding.base32Decode(it) }
        }
    }

    @Test
    fun base32DecodeAcceptsUppercase() {
        assertEquals("foobar", String(Encoding.base32Decode("MZXW6YTBOI")))
    }

    @Test
    fun sha256AndHex() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Encoding.hex(Encoding.sha256("abc".toByteArray())),
        )
        assertEquals("00ff10ab", Encoding.hex(byteArrayOf(0, -1, 0x10, 0xab.toByte())))
    }

    @Test
    fun constantTimeEquals() {
        assertTrue(Encoding.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(Encoding.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(Encoding.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertTrue(Encoding.constantTimeEquals(ByteArray(0), ByteArray(0)))
        assertTrue(Encoding.constantTimeEquals("abc", "abc"))
        assertFalse(Encoding.constantTimeEquals("abc", "abd"))
    }

    @Test
    fun protocolDeviceSecretVector() {
        val secret = ByteArray(32) { it.toByte() }
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", Encoding.base64Url(secret))
        assertEquals("mmg42klgyqzwneiskrelxms3j4", Encoding.base32(Encoding.sha256(secret).copyOf(16)))
    }

    @Test
    fun protocolTokenSecretVector() {
        val secret = ByteArray(32) { (100 + it).toByte() }
        assertEquals("ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM", Encoding.base64Url(secret))
        assertEquals("e3e16780becf601d55e4751e7d722ef91d750bb0cea61b1bb5ea8bc308cdf262", Encoding.hex(Encoding.sha256(secret)))
        assertContentEquals(secret, Encoding.base64UrlDecode("ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM"))
    }
}
