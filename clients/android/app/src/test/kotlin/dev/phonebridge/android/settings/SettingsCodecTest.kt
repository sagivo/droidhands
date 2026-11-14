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
            neverAllowApps = setOf("com.ideomobile.hapoalim", "com.x8bit.bitwarden"),
            redactOneTimeCodes = false,
            securityDefaultsApplied = true,
        )
        val encoded = SettingsCodec.encode(settings)
        assertEquals(11, encoded.size)
        assertEquals(settings, SettingsCodec.decode(encoded))
    }

    @Test
    fun securityDefaultsAreSafe() {
        val defaults = BridgeSettings()
        assertTrue("one-time codes are hidden by default", defaults.redactOneTimeCodes)
        assertTrue(defaults.neverAllowApps.isEmpty())
        assertFalse("first run still has to pre-select sensitive apps", defaults.securityDefaultsApplied)
    }

    @Test
    fun decodeReadsStoredStringSetAndIgnoresNonStrings() {
        val decoded = SettingsCodec.decode(mapOf(SettingsCodec.KEY_NEVER_ALLOW_APPS to setOf("com.venmo", 42, " com.squareup.cash ")))
        assertEquals(setOf("com.venmo", "com.squareup.cash"), decoded.neverAllowApps)
        assertEquals(emptySet<String>(), SettingsCodec.decode(mapOf(SettingsCodec.KEY_NEVER_ALLOW_APPS to "com.venmo")).neverAllowApps)
    }

    @Test
    fun sanitizeDropsMalformedPackageNames() {
        val clean = SettingsCodec.sanitize(
            BridgeSettings(neverAllowApps = setOf("com.paypal.android.p2pmobile", "", "  ", "nodots", "com..double", "1com.x", "com.ok_1.App")),
        )
        assertEquals(setOf("com.ok_1.App", "com.paypal.android.p2pmobile"), clean.neverAllowApps)
    }

    @Test
    fun packageNameValidation() {
        listOf("com.whatsapp", "keepass2android.keepass2android_nonet", "com.MizrahiTefahot.nh").forEach {
            assertTrue(it, SettingsCodec.isPackageName(it))
        }
        listOf("", "whatsapp", ".com.x", "com.x.", "com.1x", "com x.y", "com/x.y").forEach {
            assertFalse(it, SettingsCodec.isPackageName(it))
        }
    }

    @Test
    fun toStringNeverContainsTheAccessKey() {
        val text = BridgeSettings(relayAccessKey = "super-secret-key").toString()
        assertFalse(text.contains("super-secret-key"))
        assertTrue(text.contains("relayAccessKey=***"))
    }
}
