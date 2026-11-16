package com.droidhands.android.ui.connect

import com.droidhands.android.graph.DefaultRelay
import com.droidhands.android.graph.LocalServerStatus
import com.droidhands.android.service.TailnetAddress
import com.droidhands.android.service.TailscaleState
import com.droidhands.android.settings.BridgeSettings
import com.droidhands.android.settings.LocalBind
import com.droidhands.android.ui.home.StatusLevel
import com.droidhands.android.ui.home.StatusPresentation
import com.droidhands.core.transport.RelayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRoutesTest {
    private val defaultRelay = BridgeSettings(
        relayEnabled = true,
        relayUrl = "https://relay.droidhands.com",
        relayFromDefault = true,
        defaultRelayDecided = true,
    )
    private val connected = RelayStatus.Connected("https://relay.droidhands.com/mcp", connectUrl = "https://relay.droidhands.com/d/abc/mcp")
    private val tailscaleUrl = "http://pixel.tail1234.ts.net:8765/mcp"

    @Test
    fun nothingOnMeansNoRoutes() {
        assertEquals(emptyList<ActiveRoute>(), ConnectionRoutes.active(BridgeSettings(), RelayStatus.Stopped, LocalServerStatus.Stopped))
    }

    @Test
    fun relayRouteShowsTheOneTapUrlOnceConnected() {
        val route = ConnectionRoutes.active(defaultRelay, connected, LocalServerStatus.Stopped).single()
        assertEquals(RouteKind.RELAY, route.kind)
        assertEquals("DroidHands relay", route.title)
        assertEquals("https://relay.droidhands.com/d/abc/mcp", route.url)

        val connecting = ConnectionRoutes.active(defaultRelay, RelayStatus.Connecting, LocalServerStatus.Stopped).single()
        assertNull(connecting.url)
        assertEquals("Connecting to relay…", connecting.status)
    }

    @Test
    fun ownRelayIsNamedByItsHost() {
        val own = defaultRelay.copy(relayUrl = "https://relay.example.com", relayFromDefault = false)
        assertEquals("Your relay (relay.example.com)", ConnectionRoutes.relayTitle(own))
        assertEquals("DroidHands relay", ConnectionRoutes.relayTitle(defaultRelay))
    }

    @Test
    fun relayAndTailscaleCombine() {
        val both = ConnectionRoutes.withTailscale(defaultRelay, on = true)
        val routes = ConnectionRoutes.active(both, connected, LocalServerStatus.Running(listOf(tailscaleUrl)))
        assertEquals(listOf(RouteKind.RELAY, RouteKind.TAILSCALE), routes.map { it.kind })
        assertEquals(listOf("https://relay.droidhands.com/d/abc/mcp", tailscaleUrl), routes.map { it.url })
    }

    @Test
    fun tailscaleRouteWaitsForTailscale() {
        val on = ConnectionRoutes.withTailscale(BridgeSettings(), on = true)
        val waiting = ConnectionRoutes.active(on, RelayStatus.Stopped, LocalServerStatus.WaitingForTailscale).single()
        assertEquals(RouteKind.TAILSCALE, waiting.kind)
        assertNull(waiting.url)
        assertTrue(waiting.status.startsWith("Waiting for Tailscale"))

        val failed = ConnectionRoutes.active(on, RelayStatus.Stopped, LocalServerStatus.Error("Port 8765 is busy")).single()
        assertEquals("Port 8765 is busy", failed.status)
    }

    @Test
    fun localServerRoutesAreClassifiedByHost() {
        val lan = BridgeSettings(localServerEnabled = true, localServerBind = LocalBind.ALL_INTERFACES, localServerPort = 9000)
        val running = LocalServerStatus.Running(
            listOf("http://127.0.0.1:9000/mcp", "http://192.168.1.20:9000/mcp", "http://100.101.102.103:9000/mcp"),
        )
        val routes = ConnectionRoutes.active(lan, RelayStatus.Stopped, running)
        assertEquals(listOf(RouteKind.USB, RouteKind.LAN, RouteKind.TAILSCALE), routes.map { it.kind })
        assertTrue(routes[0].status.contains("adb forward tcp:9000 tcp:9000"))

        val starting = ConnectionRoutes.active(lan, RelayStatus.Stopped, LocalServerStatus.Stopped).single()
        assertNull(starting.url)
    }

    @Test
    fun withTailscaleKeepsTheRelayAndDecidesTheDefaultRelay() {
        val fresh = BridgeSettings()
        val on = ConnectionRoutes.withTailscale(fresh, on = true)
        assertTrue(ConnectionRoutes.tailscaleOn(on))
        assertEquals(LocalBind.TAILSCALE, on.localServerBind)
        assertTrue("choosing Tailscale is a connection choice", on.defaultRelayDecided)
        assertEquals(on, DefaultRelay.applyOnFirstRun(on, "https://relay.droidhands.com"))

        val withRelay = ConnectionRoutes.withTailscale(defaultRelay, on = true)
        assertTrue("routes combine: the relay stays on", DefaultRelay.inUse(withRelay))

        val off = ConnectionRoutes.withTailscale(withRelay, on = false)
        assertFalse(off.localServerEnabled)
        assertTrue(DefaultRelay.inUse(off))

        // "Off" leaves a local server on another bind alone.
        val usb = BridgeSettings(localServerEnabled = true)
        assertEquals(usb, ConnectionRoutes.withTailscale(usb, on = false))
    }

    @Test
    fun tailscaleStatusSaysWhatToDo() {
        assertTrue(ConnectionRoutes.tailscaleStatus(TailscaleState()).contains("isn't installed"))
        assertTrue(ConnectionRoutes.tailscaleStatus(TailscaleState(installed = true)).contains("isn't connected"))
        assertEquals(
            "This phone is on your tailnet as pixel.tail1234.ts.net.",
            ConnectionRoutes.tailscaleStatus(TailscaleState(true, TailnetAddress("100.101.102.103", "pixel.tail1234.ts.net"))),
        )
        assertEquals(
            "This phone is on your tailnet as 100.101.102.103.",
            ConnectionRoutes.tailscaleStatus(TailscaleState(true, TailnetAddress("100.101.102.103"))),
        )
    }

    @Test
    fun homeStatusLineNamesTheTailscaleRoute() {
        val running = StatusPresentation.local(LocalServerStatus.Running(listOf(tailscaleUrl)), true, LocalBind.TAILSCALE)
        assertEquals("Tailscale ready", running.title)
        assertEquals(listOf(tailscaleUrl), running.urls)
        val waiting = StatusPresentation.local(LocalServerStatus.WaitingForTailscale, true, LocalBind.TAILSCALE)
        assertEquals(StatusLevel.WARNING, waiting.level)
        assertEquals("Waiting for Tailscale", waiting.title)
        // Waiting for Tailscale isn't something Restart can fix.
        assertFalse(StatusPresentation.showRestart(RelayStatus.Stopped, false, LocalServerStatus.WaitingForTailscale, true))
        assertEquals("Local server running", StatusPresentation.local(LocalServerStatus.Running(emptyList()), true).title)
    }
}
