package dev.phonebridge.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCodecTest {
    @Test
    fun clampsPortToUnprivilegedRange() {
        assertEquals(1024, SettingsCodec.clampPort(80))
        assertEquals(1024, SettingsCodec.clampPort(-1))
        assertEquals(65535, SettingsCodec.clampPort(70_000))
        assertEquals(8765, SettingsCodec.clampPort(8765))
        assertEquals(1024, SettingsCodec.clampPort(1024))
        assertEquals(65535, SettingsCodec.clampPort(65535))
    }

    @Test
    fun sanitizeTrimsPastedValuesAndClampsPort() {
        val dirty = BridgeSettings(relayUrl = "  https://r.example.com/ \n", relayAccessKey = " key ", localServerPort = 22)
        val clean = SettingsCodec.sanitize(dirty)
        assertEquals("https://r.example.com/", clean.relayUrl)
        assertEquals("key", clean.relayAccessKey)
        assertEquals(1024, clean.localServerPort)
    }

    @Test
    fun decodeOfEmptyStoreGivesDefaults() {
        assertEquals(BridgeSettings(), SettingsCodec.decode(emptyMap<String, Any>()))
    }

    @Test
    fun decodeIgnoresValuesOfTheWrongType() {
        val decoded = SettingsCodec.decode(
            mapOf(
                SettingsCodec.KEY_RELAY_ENABLED to "yes",
                SettingsCodec.KEY_LOCAL_PORT to "8080",
                SettingsCodec.KEY_RELAY_URL to 42,
                SettingsCodec.KEY_ACCESS_ENABLED to false,
            ),
        )
        assertEquals(BridgeSettings(accessEnabled = false), decoded)
    }

    @Test
    fun decodeClampsStoredPort() {
        assertEquals(65535, SettingsCodec.decode(mapOf(SettingsCodec.KEY_LOCAL_PORT to 99_999)).localServerPort)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        val settings = BridgeSettings(
            relayEnabled = true,
            relayUrl = "https://relay.example.com",
            relayAccessKey = "s3cret",
            localServerEnabled = true,
            localServerPort = 9000,
            localServerBindAll = true,
            accessEnabled = false,
            startOnBoot = false,
        )
        val encoded = SettingsCodec.encode(settings)
        assertEquals(8, encoded.size)
        assertEquals(settings, SettingsCodec.decode(encoded))
    }

    @Test
    fun toStringNeverContainsTheAccessKey() {
        val text = BridgeSettings(relayAccessKey = "super-secret-key").toString()
        assertFalse(text.contains("super-secret-key"))
        assertTrue(text.contains("relayAccessKey=***"))
    }
}
