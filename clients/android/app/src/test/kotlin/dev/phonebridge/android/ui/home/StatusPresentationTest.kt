package dev.phonebridge.android.ui.home

import dev.phonebridge.android.graph.LocalServerStatus
import dev.phonebridge.android.settings.BridgeSettings
import dev.phonebridge.core.transport.RelayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPresentationTest {
    @Test
    fun relayLines() {
        val connected = StatusPresentation.relay(RelayStatus.Connected("https://r.example.com/mcp"), true)
        assertEquals(StatusLevel.OK, connected.level)
        assertEquals(listOf("https://r.example.com/mcp"), connected.urls)

        assertEquals(StatusLevel.PENDING, StatusPresentation.relay(RelayStatus.Connecting, true).level)

        val retrying = StatusPresentation.relay(RelayStatus.Error("Connection refused", 4_200, fatal = false), true)
        assertEquals(StatusLevel.WARNING, retrying.level)
        assertEquals("Connection refused. Retrying in 5 s.", retrying.detail)

        val fatal = StatusPresentation.relay(RelayStatus.Error("Bad access key.", null, fatal = true), true)
        assertEquals(StatusLevel.ERROR, fatal.level)
        assertTrue(fatal.detail!!.startsWith("Bad access key. Not retrying"))

        assertEquals(StatusLevel.OFF, StatusPresentation.relay(RelayStatus.Stopped, false).level)
        assertEquals(StatusLevel.WARNING, StatusPresentation.relay(RelayStatus.Stopped, true).level)
    }

    @Test
    fun localLines() {
        val running = StatusPresentation.local(LocalServerStatus.Running(listOf("http://127.0.0.1:8765/mcp")), true)
        assertEquals(StatusLevel.OK, running.level)
        assertEquals(listOf("http://127.0.0.1:8765/mcp"), running.urls)
        assertEquals(StatusLevel.ERROR, StatusPresentation.local(LocalServerStatus.Error("Port in use"), true).level)
        assertEquals(StatusLevel.OFF, StatusPresentation.local(LocalServerStatus.Stopped, false).level)
        assertEquals(StatusLevel.WARNING, StatusPresentation.local(LocalServerStatus.Stopped, true).level)
    }

    @Test
    fun shortDeviceId() {
        assertEquals("mmg42k…s3j4", StatusPresentation.shortDeviceId("mmg42klgyqzwneiskrelxms3j4"))
        assertEquals("short", StatusPresentation.shortDeviceId("short"))
    }

    @Test
    fun gettingStarted() {
        val steps = StatusPresentation.gettingStarted(setupComplete = true, transportConfigured = false, hasActiveToken = false)
        assertEquals(listOf(true, false, false), steps.map { it.done })
        assertTrue(StatusPresentation.showGettingStarted(transportConfigured = false, hasActiveToken = true))
        assertTrue(StatusPresentation.showGettingStarted(transportConfigured = true, hasActiveToken = false))
        assertFalse(StatusPresentation.showGettingStarted(transportConfigured = true, hasActiveToken = true))
    }

    @Test
    fun transportConfigured() {
        val base = BridgeSettings()
        assertFalse(StatusPresentation.transportConfigured(base))
        assertFalse(StatusPresentation.transportConfigured(base.copy(relayEnabled = true)))
        assertFalse(StatusPresentation.transportConfigured(base.copy(relayUrl = "https://r.example.com")))
        assertTrue(StatusPresentation.transportConfigured(base.copy(relayEnabled = true, relayUrl = "https://r.example.com")))
        assertTrue(StatusPresentation.transportConfigured(base.copy(localServerEnabled = true)))
    }

    @Test
    fun showRestart() {
        val running = LocalServerStatus.Running(emptyList())
        assertFalse(StatusPresentation.showRestart(RelayStatus.Stopped, false, LocalServerStatus.Stopped, false))
        assertTrue(StatusPresentation.showRestart(RelayStatus.Stopped, true, running, true))
        assertTrue(StatusPresentation.showRestart(RelayStatus.Error("x", null, fatal = true), true, running, false))
        assertFalse(StatusPresentation.showRestart(RelayStatus.Error("x", 1000, fatal = false), true, running, true))
        assertFalse(StatusPresentation.showRestart(RelayStatus.Connecting, true, running, true))
        assertTrue(StatusPresentation.showRestart(RelayStatus.Connected("u"), true, LocalServerStatus.Error("port"), true))
    }
}
