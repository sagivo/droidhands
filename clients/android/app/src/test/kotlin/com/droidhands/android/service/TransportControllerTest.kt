package com.droidhands.android.service

import com.droidhands.android.graph.LocalServerStatus
import com.droidhands.android.settings.BridgeSettings
import com.droidhands.android.settings.LocalBind
import com.droidhands.core.transport.RelayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TransportControllerTest {
    private class FakeRelay(val config: RelayConfig, val scope: CoroutineScope, private val startFailure: Exception?) : RelayTransport {
        override val status = MutableStateFlow<RelayStatus>(RelayStatus.Stopped)
        var starts = 0
        var stops = 0

        override fun start() {
            starts++
            startFailure?.let { throw it }
            status.value = RelayStatus.Connecting
        }

        override fun stop() {
            stops++
            status.value = RelayStatus.Stopped
        }
    }

    private class FakeLocal(val config: LocalConfig, private val failure: Exception?) : LocalTransport {
        var running = false

        override fun start() {
            failure?.let { throw it }
            running = true
        }

        override fun stop() {
            running = false
        }

        override val boundPort: Int get() = config.port
    }

    private class Harness(scope: TestScope, initial: BridgeSettings) {
        var settings = initial
        val relays = mutableListOf<FakeRelay>()
        val locals = mutableListOf<FakeLocal>()
        var localFailure: Exception? = null
        var relayFailure: Exception? = null
        var relayStartFailure: Exception? = null
        var relayStatus: RelayStatus? = null
        var localStatus: LocalServerStatus? = null
        val lanAddresses = mutableListOf("192.168.1.20")
        var tailnet: TailnetAddress? = null
        var tailnetQueries = 0

        val controller = TransportController(
            scope = scope.backgroundScope,
            settings = { settings },
            relayFactory = { config, relayScope ->
                relayFailure?.let { throw it }
                FakeRelay(config, relayScope, relayStartFailure).also { relays += it }
            },
            localFactory = { config -> FakeLocal(config, localFailure).also { locals += it } },
            lanAddresses = { lanAddresses.toList() },
            tailnet = {
                tailnetQueries++
                tailnet
            },
            onRelayStatus = { relayStatus = it },
            onLocalStatus = { localStatus = it },
        )
    }

    private val relayOn = BridgeSettings(relayEnabled = true, relayUrl = "https://relay.example.com", relayAccessKey = "k1")
    private val both = relayOn.copy(localServerEnabled = true)

    @Test
    fun applyStartsEnabledTransportsAndForwardsStatus() = runTest {
        val h = Harness(this, both)
        h.controller.apply()
        runCurrent()

        assertEquals(1, h.relays.size)
        assertEquals(RelayConfig("https://relay.example.com", "k1"), h.relays[0].config)
        assertEquals(1, h.relays[0].starts)
        assertEquals(RelayStatus.Connecting, h.relayStatus)
        assertTrue(h.locals.single().running)
        assertEquals(LocalServerStatus.Running(listOf("http://127.0.0.1:8765/mcp")), h.localStatus)

        h.relays[0].status.value = RelayStatus.Connected("https://relay.example.com/mcp")
        runCurrent()
        assertEquals(RelayStatus.Connected("https://relay.example.com/mcp"), h.relayStatus)
    }

    @Test
    fun applyWithUnchangedSettingsKeepsTransports() = runTest {
        val h = Harness(this, both)
        h.controller.apply()
        runCurrent()
        h.controller.apply()
        runCurrent()
        assertEquals(1, h.relays.size)
        assertEquals(1, h.locals.size)
        assertEquals(0, h.relays[0].stops)
    }

    @Test
    fun changingTheAccessKeyReconnectsTheRelay() = runTest {
        val h = Harness(this, relayOn)
        h.controller.apply()
        runCurrent()
        h.settings = relayOn.copy(relayAccessKey = "k2")
        h.controller.apply()
        runCurrent()

        assertEquals(2, h.relays.size)
        assertEquals(1, h.relays[0].stops)
        assertFalse(h.relays[0].scope.isActive)
        assertEquals("k2", h.relays[1].config.accessKey)
        assertEquals(RelayStatus.Connecting, h.relayStatus)
    }

    @Test
    fun changingPortOrBindingRebindsTheLocalServer() = runTest {
        val h = Harness(this, BridgeSettings(localServerEnabled = true))
        h.controller.apply()
        runCurrent()
        h.settings = h.settings.copy(localServerPort = 9000, localServerBind = LocalBind.ALL_INTERFACES)
        h.controller.apply()
        runCurrent()

        assertEquals(2, h.locals.size)
        assertFalse(h.locals[0].running)
        assertEquals(LocalConfig(9000, LocalBind.ALL_INTERFACES), h.locals[1].config)
        assertEquals(
            LocalServerStatus.Running(listOf("http://127.0.0.1:9000/mcp", "http://192.168.1.20:9000/mcp")),
            h.localStatus,
        )
        assertEquals(LocalConfig(9000, LocalBind.ALL_INTERFACES), h.controller.localConfig)
    }

    @Test
    fun disablingTheRelayStopsItAndIgnoresLateStatus() = runTest {
        val h = Harness(this, relayOn)
        h.controller.apply()
        runCurrent()
        val old = h.relays.single()
        h.settings = relayOn.copy(relayEnabled = false)
        h.controller.apply()
        runCurrent()

        assertEquals(1, old.stops)
        assertEquals(RelayStatus.Stopped, h.relayStatus)
        old.status.value = RelayStatus.Connected("https://late/mcp")
        runCurrent()
        assertEquals(RelayStatus.Stopped, h.relayStatus)
        assertNull(h.controller.relayConfig)
    }

    @Test
    fun invalidRelayUrlReportsFatalErrorWithoutConnecting() = runTest {
        val h = Harness(this, relayOn.copy(relayUrl = "relay.example.com"))
        h.controller.apply()
        runCurrent()
        assertTrue(h.relays.isEmpty())
        val status = h.relayStatus as RelayStatus.Error
        assertTrue(status.fatal)
    }

    @Test
    fun relayFactoryFailureBecomesErrorStatus() = runTest {
        val h = Harness(this, relayOn)
        h.relayFailure = IllegalArgumentException("bad url")
        h.controller.apply()
        runCurrent()
        val status = h.relayStatus as RelayStatus.Error
        assertTrue(status.message.contains("bad url"))
        assertNull(h.controller.relayConfig)
    }

    @Test
    fun relayStartFailureStopsTheHalfStartedClient() = runTest {
        val h = Harness(this, relayOn)
        h.relayStartFailure = IllegalStateException("boom")
        h.controller.apply()
        runCurrent()
        val relay = h.relays.single()
        assertEquals(1, relay.stops)
        assertFalse(relay.scope.isActive)
        assertTrue((h.relayStatus as RelayStatus.Error).message.contains("boom"))
        assertNull(h.controller.relayConfig)
    }

    @Test
    fun localBindFailureIsReportedAndRetriedOnNextApply() = runTest {
        val h = Harness(this, BridgeSettings(localServerEnabled = true))
        h.localFailure = IOException("Address already in use")
        h.controller.apply()
        runCurrent()
        val error = h.localStatus as LocalServerStatus.Error
        assertTrue(error.message.contains("8765"))
        assertTrue(error.message.contains("Address already in use"))
        assertNull(h.controller.localConfig)

        h.localFailure = null
        h.controller.apply()
        runCurrent()
        assertTrue(h.locals.last().running)
        assertEquals(LocalServerStatus.Running(listOf("http://127.0.0.1:8765/mcp")), h.localStatus)
    }

    @Test
    fun restartRelayReconnectsUnlessStoppedOrFatal() = runTest {
        val h = Harness(this, relayOn)
        h.controller.restartRelay()
        runCurrent()
        assertTrue("inactive controller ignores restarts", h.relays.isEmpty())

        h.controller.apply()
        runCurrent()
        h.controller.restartRelay()
        runCurrent()
        assertEquals(2, h.relays.size)
        assertEquals(1, h.relays[0].stops)
        assertEquals(h.relays[0].config, h.relays[1].config)

        h.relays[1].status.value = RelayStatus.Error("access key rejected", null, fatal = true)
        runCurrent()
        h.controller.restartRelay()
        runCurrent()
        assertEquals(2, h.relays.size)
    }

    @Test
    fun retryRelayOnlyRestartsARelayWaitingToRetry() = runTest {
        val h = Harness(this, relayOn)
        h.controller.retryRelay()
        runCurrent()
        assertTrue("inactive controller ignores retries", h.relays.isEmpty())

        h.controller.apply()
        runCurrent()
        h.controller.retryRelay()
        runCurrent()
        assertEquals("a connection attempt in progress is left alone", 1, h.relays.size)

        h.relays[0].status.value = RelayStatus.Connected("https://relay.example.com/mcp")
        runCurrent()
        h.controller.retryRelay(alsoWhileConnecting = true)
        runCurrent()
        assertEquals("a connected relay is never restarted", 1, h.relays.size)

        h.relays[0].status.value = RelayStatus.Error("relay restarting", retryInMs = 32_000)
        runCurrent()
        h.controller.retryRelay()
        runCurrent()
        assertEquals("waiting out a backoff: reconnect now", 2, h.relays.size)
        assertEquals(1, h.relays[0].stops)
        assertEquals(1, h.relays[1].starts)
        assertEquals(RelayStatus.Connecting, h.relayStatus)

        h.relays[1].status.value = RelayStatus.Error("access key rejected", null, fatal = true)
        runCurrent()
        h.controller.retryRelay(alsoWhileConnecting = true)
        runCurrent()
        assertEquals("fatal errors wait for a settings change", 2, h.relays.size)
    }

    @Test
    fun retryRelayWhileConnectingRestartsAStalledAttempt() = runTest {
        val h = Harness(this, relayOn)
        h.controller.apply()
        runCurrent()
        assertEquals(RelayStatus.Connecting, h.relays[0].status.value)
        h.controller.retryRelay(alsoWhileConnecting = true)
        runCurrent()
        assertEquals(2, h.relays.size)
        assertEquals(1, h.relays[0].stops)
    }

    @Test
    fun refreshLocalUrlsPicksUpNewAddresses() = runTest {
        val h = Harness(this, BridgeSettings(localServerEnabled = true, localServerBind = LocalBind.ALL_INTERFACES))
        h.controller.apply()
        runCurrent()
        h.lanAddresses.clear()
        h.lanAddresses += "10.0.0.5"
        h.controller.refreshLocalUrls()
        runCurrent()
        assertEquals(LocalServerStatus.Running(listOf("http://127.0.0.1:8765/mcp", "http://10.0.0.5:8765/mcp")), h.localStatus)
        assertEquals(1, h.locals.size)
    }

    @Test
    fun tailscaleServerWaitsForTheTailnetAndFollowsItsAddress() = runTest {
        val h = Harness(this, BridgeSettings(localServerEnabled = true, localServerBind = LocalBind.TAILSCALE))
        h.controller.apply()
        runCurrent()
        assertTrue("nothing listens without a tailnet address", h.locals.isEmpty())
        assertEquals(LocalServerStatus.WaitingForTailscale, h.localStatus)

        // Tailscale connects: the network callback refreshes, the server binds the tailnet address.
        h.tailnet = TailnetAddress("100.101.102.103")
        h.controller.refreshLocalUrls()
        runCurrent()
        assertEquals(LocalConfig(8765, LocalBind.TAILSCALE, "100.101.102.103"), h.locals.single().config)
        assertEquals("100.101.102.103", h.locals.single().config.bindAddress)
        assertEquals(LocalServerStatus.Running(listOf("http://100.101.102.103:8765/mcp")), h.localStatus)

        // The MagicDNS name arrives: same socket, nicer URL.
        h.tailnet = TailnetAddress("100.101.102.103", "pixel.tail1234.ts.net")
        h.controller.refreshLocalUrls()
        runCurrent()
        assertEquals(1, h.locals.size)
        assertEquals(LocalServerStatus.Running(listOf("http://pixel.tail1234.ts.net:8765/mcp")), h.localStatus)

        // A new tailnet address: rebind.
        h.tailnet = TailnetAddress("100.101.102.104")
        h.controller.refreshLocalUrls()
        runCurrent()
        assertEquals(2, h.locals.size)
        assertFalse(h.locals[0].running)
        assertTrue(h.locals[1].running)
        assertEquals("100.101.102.104", h.locals[1].config.bindAddress)

        // Tailscale disconnects: stop listening and wait again.
        h.tailnet = null
        h.controller.refreshLocalUrls()
        runCurrent()
        assertFalse(h.locals[1].running)
        assertEquals(LocalServerStatus.WaitingForTailscale, h.localStatus)
        assertNull(h.controller.localConfig)
    }

    @Test
    fun otherBindModesNeverAskForTheTailnet() = runTest {
        val h = Harness(this, BridgeSettings(localServerEnabled = true, localServerBind = LocalBind.ALL_INTERFACES))
        h.tailnet = TailnetAddress("100.101.102.103")
        h.controller.apply()
        h.controller.refreshLocalUrls()
        runCurrent()
        assertEquals(0, h.tailnetQueries)
        assertEquals(1, h.locals.size)
    }

    @Test
    fun tailscaleBindFailureSaysItRetries() = runTest {
        val h = Harness(this, BridgeSettings(localServerEnabled = true, localServerBind = LocalBind.TAILSCALE))
        h.tailnet = TailnetAddress("100.101.102.103")
        h.localFailure = IOException("Cannot assign requested address")
        h.controller.apply()
        runCurrent()
        val error = h.localStatus as LocalServerStatus.Error
        assertTrue(error.message.contains("Tailscale"))

        h.localFailure = null
        h.controller.refreshLocalUrls()
        runCurrent()
        assertTrue("a network change retries the bind", h.locals.last().running)
    }

    @Test
    fun stopAllStopsEverythingAndDeactivates() = runTest {
        val h = Harness(this, both)
        h.controller.apply()
        runCurrent()
        h.controller.stopAll()
        runCurrent()

        assertEquals(1, h.relays.single().stops)
        assertFalse(h.locals.single().running)
        assertEquals(RelayStatus.Stopped, h.relayStatus)
        assertEquals(LocalServerStatus.Stopped, h.localStatus)

        h.controller.restartRelay()
        h.controller.refreshLocalUrls()
        runCurrent()
        assertEquals(1, h.relays.size)
        assertEquals(LocalServerStatus.Stopped, h.localStatus)
    }

    @Test
    fun commandsRunInSubmissionOrder() = runTest {
        val h = Harness(this, BridgeSettings(localServerEnabled = true))
        // A service instance being destroyed and a new one starting right after.
        h.controller.apply()
        h.controller.stopAll()
        h.controller.apply()
        runCurrent()
        assertEquals(2, h.locals.size)
        assertFalse(h.locals[0].running)
        assertTrue(h.locals[1].running)
    }
}
