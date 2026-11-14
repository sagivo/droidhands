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
        // A non-empty store is an existing install: the default relay counts as decided (migration).
        assertEquals(BridgeSettings(accessEnabled = false, defaultRelayDecided = true), decoded)
    }

    @Test
    fun freshInstallStillHasToDecideTheDefaultRelay() {
        val fresh = SettingsCodec.decode(emptyMap<String, Any>())
        assertFalse(fresh.defaultRelayDecided)
        assertFalse(fresh.relayFromDefault)
        assertTrue("update checks are on by default", fresh.checkForUpdates)
    }

    @Test
    fun migrationKeepsExistingUsersSettingsAndNeverAddsARelay() {
        // Stored by a version from before the default relay existed: an existing user without a relay.
        val noRelay = mapOf(
            SettingsCodec.KEY_RELAY_ENABLED to false,
            SettingsCodec.KEY_RELAY_URL to "",
            SettingsCodec.KEY_LOCAL_ENABLED to true,
            SettingsCodec.KEY_SECURITY_DEFAULTS_APPLIED to true,
        )
        val migrated = SettingsCodec.decode(noRelay)
        assertTrue("the first-run default relay must not apply to existing installs", migrated.defaultRelayDecided)
        assertFalse(migrated.relayFromDefault)
        assertFalse(migrated.relayEnabled)
        assertTrue(migrated.localServerEnabled)
        assertTrue(migrated.checkForUpdates)

        // An existing user's own relay stays theirs.
        val ownRelay = SettingsCodec.decode(
            mapOf(
                SettingsCodec.KEY_RELAY_ENABLED to true,
                SettingsCodec.KEY_RELAY_URL to "https://relay.example.com",
                SettingsCodec.KEY_RELAY_ACCESS_KEY to "k",
            ),
        )
        assertEquals("https://relay.example.com", ownRelay.relayUrl)
        assertEquals("k", ownRelay.relayAccessKey)
        assertTrue(ownRelay.relayEnabled)
        assertFalse(ownRelay.relayFromDefault)
        assertTrue(ownRelay.defaultRelayDecided)
    }

    @Test
    fun storedDecisionWins() {
        // Written by this version on a fresh install before the UI decided (e.g. by the security defaults).
        val undecided = SettingsCodec.decode(mapOf(SettingsCodec.KEY_SECURITY_DEFAULTS_APPLIED to true, SettingsCodec.KEY_DEFAULT_RELAY_DECIDED to false))
        assertFalse(undecided.defaultRelayDecided)
        val off = SettingsCodec.decode(mapOf(SettingsCodec.KEY_CHECK_FOR_UPDATES to false))
        assertFalse(off.checkForUpdates)
    }

    @Test
    fun noRelayUrlMeansNoDefaultRelay() {
        assertFalse(SettingsCodec.sanitize(BridgeSettings(relayUrl = "  ", relayFromDefault = true)).relayFromDefault)
        assertTrue(SettingsCodec.sanitize(BridgeSettings(relayUrl = "https://r.example.com", relayFromDefault = true)).relayFromDefault)
        assertFalse(
            SettingsCodec.decode(mapOf(SettingsCodec.KEY_RELAY_URL to "", SettingsCodec.KEY_RELAY_FROM_DEFAULT to true)).relayFromDefault,
        )
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
            localServerBind = LocalBind.TAILSCALE,
            accessEnabled = false,
            startOnBoot = false,
            neverAllowApps = setOf("com.ideomobile.hapoalim", "com.x8bit.bitwarden"),
            redactOneTimeCodes = false,
            securityDefaultsApplied = true,
            localPluginsEnabled = true,
            defaultRelayDecided = true,
            relayFromDefault = true,
            checkForUpdates = false,
        )
        val encoded = SettingsCodec.encode(settings)
        assertEquals(16, encoded.size)
        assertEquals(settings, SettingsCodec.decode(encoded))
        // Fresh-install values survive a save too (the store is no longer empty afterwards).
        val fresh = BridgeSettings(securityDefaultsApplied = true)
        assertEquals(fresh, SettingsCodec.decode(SettingsCodec.encode(fresh)))
    }

    @Test
    fun localBindMigratesFromTheBindAllSwitch() {
        // Saved before the bind mode existed: the bind-all switch decides, so nothing changes for the user.
        assertEquals(LocalBind.ALL_INTERFACES, SettingsCodec.decode(mapOf(SettingsCodec.KEY_LOCAL_BIND_ALL to true)).localServerBind)
        assertEquals(LocalBind.LOOPBACK, SettingsCodec.decode(mapOf(SettingsCodec.KEY_LOCAL_BIND_ALL to false)).localServerBind)
        assertEquals(LocalBind.LOOPBACK, SettingsCodec.decode(mapOf(SettingsCodec.KEY_LOCAL_ENABLED to true)).localServerBind)
        assertEquals(LocalBind.LOOPBACK, SettingsCodec.decode(emptyMap<String, Any>()).localServerBind)

        // The bind mode wins once stored; unknown or mistyped values fall back to the switch.
        val both = mapOf(SettingsCodec.KEY_LOCAL_BIND_ALL to false, SettingsCodec.KEY_LOCAL_BIND to "tailscale")
        assertEquals(LocalBind.TAILSCALE, SettingsCodec.decode(both).localServerBind)
        assertEquals(
            LocalBind.ALL_INTERFACES,
            SettingsCodec.decode(mapOf(SettingsCodec.KEY_LOCAL_BIND_ALL to true, SettingsCodec.KEY_LOCAL_BIND to "mesh")).localServerBind,
        )
        assertEquals(LocalBind.LOOPBACK, SettingsCodec.decode(mapOf(SettingsCodec.KEY_LOCAL_BIND to 3)).localServerBind)
    }

    @Test
    fun encodeKeepsTheBindAllSwitchForOlderVersions() {
        fun stored(bind: LocalBind) = SettingsCodec.encode(BridgeSettings(localServerBind = bind))
        assertEquals("all", stored(LocalBind.ALL_INTERFACES)[SettingsCodec.KEY_LOCAL_BIND])
        assertEquals(true, stored(LocalBind.ALL_INTERFACES)[SettingsCodec.KEY_LOCAL_BIND_ALL])
        // An older app reads Tailscale-only as loopback: never wider than the user chose.
        assertEquals(false, stored(LocalBind.TAILSCALE)[SettingsCodec.KEY_LOCAL_BIND_ALL])
        assertEquals("tailscale", stored(LocalBind.TAILSCALE)[SettingsCodec.KEY_LOCAL_BIND])
        assertEquals(false, stored(LocalBind.LOOPBACK)[SettingsCodec.KEY_LOCAL_BIND_ALL])
    }

    @Test
    fun securityDefaultsAreSafe() {
        val defaults = BridgeSettings()
        assertTrue("one-time codes are hidden by default", defaults.redactOneTimeCodes)
        assertTrue(defaults.neverAllowApps.isEmpty())
        assertFalse("first run still has to pre-select sensitive apps", defaults.securityDefaultsApplied)
        assertFalse("unreviewed local plugins are off until the user turns them on", defaults.localPluginsEnabled)
        assertFalse(SettingsCodec.decode(emptyMap<String, Any>()).localPluginsEnabled)
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
