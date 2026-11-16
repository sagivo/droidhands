package com.droidhands.android.service

import com.droidhands.android.service.RelayWakeups.Step
import com.droidhands.core.transport.RelayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayWakeupsTest {
    private val wakeups = RelayWakeups(slackMs = 2_000, connectingGraceMs = 45_000, baseBackoffMs = 4_000, maxBackoffMs = 300_000)
    private val connected = RelayStatus.Connected("https://relay.example.com/mcp")

    private fun waiting(retryInMs: Long) = RelayStatus.Error("The relay closed the connection", retryInMs = retryInMs)

    @Test
    fun noAlarmWhileConnectedStoppedOrFailedForGood() {
        assertEquals(Step(alarmInMs = null), wakeups.onStatus(connected))
        assertEquals(Step(alarmInMs = null), wakeups.onStatus(RelayStatus.Stopped))
        assertEquals(Step(alarmInMs = null), wakeups.onStatus(RelayStatus.Error("access key rejected", retryInMs = null, fatal = true)))
        assertNull("nothing to wake up for", wakeups.onAlarm())
    }

    @Test
    fun alarmFollowsTheClientsPlannedRetry() {
        // Relay restarted overnight: the client plans a retry in 32 s, which stalls once the CPU sleeps.
        assertEquals(Step(alarmInMs = 34_000), wakeups.onStatus(waiting(32_000)))
        // The client retried on its own (it was awake): the alarm moves to cover the attempt.
        assertEquals(Step(alarmInMs = 45_000), wakeups.onStatus(RelayStatus.Connecting))
        assertEquals(Step(alarmInMs = null), wakeups.onStatus(connected))
    }

    @Test
    fun alarmReconnectsAStalledRetryAndReleasesItsWakeLockWhenTheAttemptEnds() {
        wakeups.onStatus(waiting(32_000))
        assertEquals("re-armed in case this attempt stalls too", 45_000L, wakeups.onAlarm())
        assertEquals(Step(alarmInMs = 45_000, releaseWakeLock = false), wakeups.onStatus(RelayStatus.Connecting))
        assertEquals(Step(alarmInMs = null, releaseWakeLock = true), wakeups.onStatus(connected))
        assertFalse("released once", wakeups.onStatus(RelayStatus.Stopped).releaseWakeLock)
    }

    @Test
    fun alarmRestartsAConnectionAttemptStuckInSleep() {
        wakeups.onStatus(RelayStatus.Connecting)
        assertEquals(45_000L, wakeups.onAlarm())
        // The restart stays Connecting (no new status), then fails.
        val failed = wakeups.onStatus(waiting(1_000))
        assertTrue(failed.releaseWakeLock)
    }

    @Test
    fun alarmDrivenReconnectsBackOffUntilConnected() {
        // The relay stays down while the phone sleeps: every alarm restart resets the client's backoff to ~1 s,
        // so the alarms must back off on their own instead of waking the phone every second.
        val delays = (1..9).map {
            wakeups.onStatus(waiting(1_000))
            wakeups.onAlarm()
            wakeups.onStatus(RelayStatus.Connecting)
            wakeups.onStatus(waiting(1_000)).alarmInMs
        }
        assertEquals(listOf(4_000L, 8_000L, 16_000L, 32_000L, 64_000L, 128_000L, 256_000L, 300_000L, 300_000L), delays)

        wakeups.onStatus(connected)
        assertEquals("connected again: back to the client's own timing", 3_000L, wakeups.onStatus(waiting(1_000)).alarmInMs)
    }

    @Test
    fun clientTimingWinsWhenLongerThanTheAlarmBackoff() {
        wakeups.onStatus(waiting(1_000))
        wakeups.onAlarm()
        assertEquals(62_000L, wakeups.onStatus(waiting(60_000)).alarmInMs)
    }

    @Test
    fun pendingMeansWaitingToRetryOrConnecting() {
        assertTrue(RelayWakeups.isPending(RelayStatus.Connecting))
        assertTrue(RelayWakeups.isPending(waiting(1_000)))
        assertFalse(RelayWakeups.isPending(RelayStatus.Error("bad key", retryInMs = null, fatal = true)))
        assertFalse(RelayWakeups.isPending(connected))
        assertFalse(RelayWakeups.isPending(RelayStatus.Stopped))
    }
}
