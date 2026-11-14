// Adapted from T3 Code (MIT, Copyright (c) 2026 T3 Tools Inc.), apps/mobile/src/features/connection/pairing.test.ts
package dev.phonebridge.android.ui.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupQrPayloadTest {
    private fun valid(payload: String): ConfigureRequest {
        val result = SetupQrPayload.parse(payload)
        check(result is ConfigureLinkResult.Valid) { "expected a valid link for '$payload', got $result" }
        return result.request
    }

    private fun invalid(payload: String?): String {
        val result = SetupQrPayload.parse(payload)
        check(result is ConfigureLinkResult.Invalid) { "expected '$payload' to be refused, got $result" }
        return result.message
    }

    @Test
    fun readsTheRelaySetupPageWithItsKeyFromTheFragment() {
        val request = valid("https://relay.example.com/setup#key=s3cr%2Fet")
        assertEquals("https://relay.example.com", request.relayUrl)
        assertEquals("s3cr/et", request.accessKey)
        assertFalse(request.insecure)
    }

    @Test
    fun readsTheSetupPageWithoutAKey() {
        val request = valid("https://relay.example.com/setup")
        assertEquals("https://relay.example.com", request.relayUrl)
        assertEquals(null, request.accessKey)
        assertEquals(null, valid("https://relay.example.com/setup#key=").accessKey)
    }

    @Test
    fun keepsARelaysPathPrefixAndPort() {
        val request = valid("https://example.com:8443/phonebridge/setup/#key=k")
        assertEquals("https://example.com:8443/phonebridge", request.relayUrl)
        assertEquals("k", request.accessKey)
    }

    @Test
    fun keepsAPlusInTheKey() {
        assertEquals("a+b/c=", valid("https://relay.example.com/setup#key=a+b%2Fc%3D").accessKey)
    }

    @Test
    fun acceptsTheKeyInTheQueryToo() {
        assertEquals("k", valid("https://relay.example.com/setup?key=k").accessKey)
        assertEquals("fragment wins", "f", valid("https://relay.example.com/setup?key=q#key=f").accessKey)
    }

    @Test
    fun trimsWhitespaceAroundThePayload() {
        assertEquals("https://relay.example.com", valid("  \n https://relay.example.com/setup#key=k \t").relayUrl)
    }

    @Test
    fun ignoresTheCaseOfSchemeAndPath() {
        val request = valid("HTTPS://relay.example.com/SETUP#key=k")
        assertEquals("https://relay.example.com", request.relayUrl)
        assertEquals("k", request.accessKey)
        assertEquals("https://relay.example.com", valid("PhoneBridge://Configure?relay=https%3A%2F%2Frelay.example.com").relayUrl)
    }

    @Test
    fun readsTheConfigureDeepLink() {
        val request = valid("phonebridge://configure?relay=https%3A%2F%2Frelay.example.com&key=abc")
        assertEquals("https://relay.example.com", request.relayUrl)
        assertEquals("abc", request.accessKey)
    }

    @Test
    fun readsTheSetupPagesIntentLink() {
        val request = valid(
            "intent://configure?relay=https%3A%2F%2Frelay.example.com&key=abc#Intent;scheme=phonebridge;package=dev.phonebridge.android;end",
        )
        assertEquals("https://relay.example.com", request.relayUrl)
        assertEquals("abc", request.accessKey)
        assertEquals(SetupQrPayload.NOT_A_SETUP_LINK, invalid("intent://configure?relay=x#Intent;scheme=evil;end"))
    }

    @Test
    fun readsABareRelayUrl() {
        assertEquals("https://relay.example.com", valid("https://relay.example.com").relayUrl)
        assertEquals("https://relay.example.com", valid("https://relay.example.com/").relayUrl)
    }

    @Test
    fun aSchemelessHostGetsHttpsAndAnIpAddressHttp() {
        assertEquals("https://relay.example.com", valid("relay.example.com/setup#key=k").relayUrl)
        val lan = valid("192.168.1.100:8080/setup#key=k")
        assertEquals("http://192.168.1.100:8080", lan.relayUrl)
        assertEquals("k", lan.accessKey)
        assertFalse("plain http to a private address is fine", lan.insecure)
    }

    @Test
    fun explicitHttpIsKeptAndFlaggedForPublicHosts() {
        assertTrue(valid("http://relay.example.com/setup").insecure)
    }

    @Test
    fun refusesEmptyPayloads() {
        assertEquals("The QR code is empty.", invalid(null))
        assertEquals("The QR code is empty.", invalid("   "))
    }

    @Test
    fun refusesOtherSchemes() {
        listOf(
            "javascript:alert(1)",
            "JavaScript://relay.example.com/setup",
            "data:text/html,<b>hi</b>",
            "file:///sdcard/setup",
            "ftp://relay.example.com/setup",
            "wss://relay.example.com/setup",
            "t3code://pair?pairingUrl=https%3A%2F%2Frelay.example.com%2Fsetup",
            "mailto:someone@example.com",
        ).forEach { assertEquals(it, SetupQrPayload.NOT_A_SETUP_LINK, invalid(it)) }
    }

    @Test
    fun refusesJunkAndOtherPages() {
        listOf(
            "hello",
            "WIFI:S:home;T:WPA;P:secret;;",
            "https://example.com/menu",
            "https://relay.example.com/setup-guide",
            "https://relay .example.com/setup",
            "https://user:pass@relay.example.com/setup",
            "https:relay.example.com",
            "x".repeat(5000),
        ).forEach { assertEquals(it, SetupQrPayload.NOT_A_SETUP_LINK, invalid(it)) }
    }

    @Test
    fun explainsAnAgentConnectionUrl() {
        assertEquals(SetupQrPayload.AGENT_URL, invalid("https://relay.example.com/mcp"))
        assertEquals(SetupQrPayload.AGENT_URL, invalid("https://relay.example.com/d/ABC123/mcp/"))
    }

    @Test
    fun aBadRelayInsideTheDeepLinkIsRefusedByTheUsualChecks() {
        assertTrue(invalid("phonebridge://configure?relay=javascript%3Aalert(1)").startsWith("The relay address in the setup link is not valid"))
        assertTrue(invalid("phonebridge://configure?key=abc").contains("doesn't contain a relay address"))
    }

    @Test
    fun recognisesIpLiterals() {
        assertTrue(SetupQrPayload.isIpLiteral("10.0.0.1"))
        assertTrue(SetupQrPayload.isIpLiteral("10.0.0.1:8080"))
        assertTrue(SetupQrPayload.isIpLiteral("[fd7a::1]:8080"))
        assertFalse(SetupQrPayload.isIpLiteral("256.0.0.1"))
        assertFalse(SetupQrPayload.isIpLiteral("relay.example.com"))
        assertFalse(SetupQrPayload.isIpLiteral("1.2.3"))
    }
}
