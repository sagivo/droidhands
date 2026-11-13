package dev.phonebridge.android.service

import dev.phonebridge.android.settings.BridgeSettings
import dev.phonebridge.core.transport.RelayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportConfigTest {
    private val validRelay = BridgeSettings(relayEnabled = true, relayUrl = "https://relay.example.com/")

    @Test
    fun relayConfigRequiresEnabledAndValidUrl() {
        assertNull(TransportConfig.relay(validRelay.copy(relayEnabled = false)))
        assertNull(TransportConfig.relay(validRelay.copy(relayUrl = "relay.example.com")))
        assertEquals(RelayConfig("https://relay.example.com", null), TransportConfig.relay(validRelay))
        assertEquals(RelayConfig("https://relay.example.com", "k"), TransportConfig.relay(validRelay.copy(relayAccessKey = " k ")))
    }

    @Test
    fun localConfigFollowsSettings() {
        assertNull(TransportConfig.local(BridgeSettings(localServerEnabled = false)))
        assertEquals(LocalConfig(8765, false), TransportConfig.local(BridgeSettings(localServerEnabled = true)))
        assertEquals(LocalConfig(1024, true), TransportConfig.local(BridgeSettings(localServerEnabled = true, localServerPort = 80, localServerBindAll = true)))
        assertEquals("127.0.0.1", LocalConfig(8765, false).bindAddress)
        assertEquals("0.0.0.0", LocalConfig(8765, true).bindAddress)
    }

    @Test
    fun shouldRunWhenAnyTransportIsUsable() {
        assertFalse(TransportConfig.shouldRun(BridgeSettings()))
        assertTrue(TransportConfig.shouldRun(validRelay))
        assertTrue(TransportConfig.shouldRun(BridgeSettings(localServerEnabled = true)))
        // Relay enabled but unusable, and no local server: nothing to run.
        assertFalse(TransportConfig.shouldRun(validRelay.copy(relayUrl = "")))
        // The kill switch does not stop transports (agents get "paused" errors instead).
        assertTrue(TransportConfig.shouldRun(validRelay.copy(accessEnabled = false)))
    }

    @Test
    fun idleRelayStatusExplainsInvalidUrl() {
        assertEquals(RelayStatus.Stopped, TransportConfig.idleRelayStatus(BridgeSettings()))
        assertEquals(RelayStatus.Stopped, TransportConfig.idleRelayStatus(validRelay))
        val error = TransportConfig.idleRelayStatus(validRelay.copy(relayUrl = "ftp://x")) as RelayStatus.Error
        assertTrue(error.fatal)
        assertNull(error.retryInMs)
        assertTrue(error.message.startsWith("Relay URL: "))
    }

    @Test
    fun localUrlsAlwaysIncludeLoopback() {
        assertEquals(listOf("http://127.0.0.1:8765/mcp"), TransportConfig.localUrls(8765, bindAll = false, lanAddresses = listOf("192.168.1.20")))
        assertEquals(
            listOf("http://127.0.0.1:9000/mcp", "http://192.168.1.20:9000/mcp", "http://100.64.0.7:9000/mcp"),
            TransportConfig.localUrls(9000, bindAll = true, lanAddresses = listOf("192.168.1.20", "127.0.0.2", "0.0.0.0", "100.64.0.7", "192.168.1.20")),
        )
    }

    @Test
    fun relayConfigToStringHidesAccessKey() {
        assertFalse(RelayConfig("https://r", "secret-key").toString().contains("secret-key"))
    }
}
