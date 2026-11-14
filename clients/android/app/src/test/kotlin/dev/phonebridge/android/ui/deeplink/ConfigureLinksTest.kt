package dev.phonebridge.android.ui.deeplink

import dev.phonebridge.android.settings.BridgeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigureLinksTest {
    private fun valid(uri: String): ConfigureRequest {
        val result = ConfigureLinks.parse(uri)
        assertTrue("expected valid: $result", result is ConfigureLinkResult.Valid)
        return (result as ConfigureLinkResult.Valid).request
    }

    private fun invalid(uri: String): String {
        val result = ConfigureLinks.parse(uri)
        assertTrue("expected invalid: $result", result is ConfigureLinkResult.Invalid)
        return (result as ConfigureLinkResult.Invalid).message
    }

    @Test
    fun parsesRelayAndKey() {
        val r = valid("phonebridge://configure?relay=https://relay.example.com/&key=s3cr3t")
        assertEquals("https://relay.example.com", r.relayUrl)
        assertEquals("s3cr3t", r.accessKey)
        assertEquals("relay.example.com", r.host)
        assertFalse(r.insecure)
    }

    @Test
    fun decodesPercentEncodingButKeepsPlus() {
        val r = valid("phonebridge://configure?relay=https%3A%2F%2Frelay.example.com%3A8443&key=a%2Fb+c")
        assertEquals("https://relay.example.com:8443", r.relayUrl)
        assertEquals("relay.example.com:8443", r.host)
        assertEquals("a/b+c", r.accessKey)
    }

    @Test
    fun keyIsOptionalAndNeverPrinted() {
        val noKey = valid("phonebridge://configure?relay=https://r.example.com")
        assertNull(noKey.accessKey)
        assertEquals("phonebridge://configure?relay=https://r.example.com", noKey.displayLink)

        val withKey = valid("phonebridge://configure?relay=https://r.example.com&key=topsecret")
        assertFalse(withKey.displayLink.contains("topsecret"))
        assertTrue(withKey.displayLink.endsWith("&key=${ConfigureLinks.MASK}"))
        assertFalse(withKey.toString().contains("topsecret"))
    }

    @Test
    fun flagsPlainHttpToPublicHosts() {
        assertTrue(valid("phonebridge://configure?relay=http://relay.example.com").insecure)
        assertFalse(valid("phonebridge://configure?relay=http://192.168.1.20:8080").insecure)
    }

    @Test
    fun rejectsBadLinks() {
        assertTrue(invalid("https://configure?relay=https://r.example.com").contains("isn't a PhoneBridge"))
        assertTrue(invalid("phonebridge://other?relay=https://r.example.com").contains("isn't a PhoneBridge"))
        assertTrue(invalid("phonebridge://configure").contains("relay address"))
        assertTrue(invalid("phonebridge://configure?relay=").contains("relay address"))
        assertTrue(invalid("phonebridge://configure?relay=ftp://r.example.com").contains("not valid"))
        assertTrue(invalid("phonebridge://configure?relay=https://r.example.com/mcp").contains("not valid"))
        assertTrue(invalid("phonebridge://configure?relay=https://r.example.com&key=a%20b").contains("access key"))
        assertTrue(invalid("not a uri at all ^^").isNotBlank())
    }

    @Test
    fun recognizesSetupLinks() {
        assertTrue(ConfigureLinks.isConfigureLink("phonebridge://configure?relay=x"))
        assertTrue(ConfigureLinks.isConfigureLink("PhoneBridge://Configure"))
        assertFalse(ConfigureLinks.isConfigureLink("phonebridge://pair"))
        assertFalse(ConfigureLinks.isConfigureLink(null))
    }

    @Test
    fun applyTurnsTheRelayOnAndHandlesTheKey() {
        val current = BridgeSettings(relayEnabled = false, relayUrl = "https://old.example.com", relayAccessKey = "oldkey", localServerEnabled = true)
        val withKey = ConfigureLinks.applyTo(current, valid("phonebridge://configure?relay=https://new.example.com&key=newkey"))
        assertEquals(current.copy(relayEnabled = true, relayUrl = "https://new.example.com", relayAccessKey = "newkey"), withKey)

        // Another relay without a key: the old key is meaningless and is cleared.
        val other = ConfigureLinks.applyTo(current, valid("phonebridge://configure?relay=https://new.example.com"))
        assertEquals("", other.relayAccessKey)

        // Same relay without a key: keep the key typed by hand.
        val same = ConfigureLinks.applyTo(current, valid("phonebridge://configure?relay=https://old.example.com/"))
        assertEquals("oldkey", same.relayAccessKey)
        assertTrue(same.relayEnabled)
        assertTrue(same.localServerEnabled)
    }

    @Test
    fun aSetupLinkIsTheUsersOwnRelayChoice() {
        val onDefault = BridgeSettings(relayEnabled = true, relayUrl = "https://default.example.com", relayFromDefault = true, defaultRelayDecided = true)
        val applied = ConfigureLinks.applyTo(onDefault, valid("phonebridge://configure?relay=https://mine.example.com&key=k"))
        assertEquals("https://mine.example.com", applied.relayUrl)
        assertFalse(applied.relayFromDefault)
        assertTrue(applied.defaultRelayDecided)
    }

    @Test
    fun alreadyApplied() {
        val settings = BridgeSettings(relayEnabled = true, relayUrl = "https://r.example.com", relayAccessKey = "k")
        assertTrue(ConfigureLinks.isAlreadyApplied(settings, valid("phonebridge://configure?relay=https://r.example.com&key=k")))
        assertFalse(ConfigureLinks.isAlreadyApplied(settings, valid("phonebridge://configure?relay=https://r.example.com&key=other")))
    }
}
