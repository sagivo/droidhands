package com.droidhands.android.confirm

import com.droidhands.android.tools.support.UnlockPrompt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockWaitTest {
    private val never = { false }

    @Test
    fun usableNeedsScreenOnAndNoKeyguard() {
        assertTrue(UnlockWait.isUsable(screenOn = true, keyguardLocked = false))
        assertFalse(UnlockWait.isUsable(screenOn = true, keyguardLocked = true))
        assertFalse(UnlockWait.isUsable(screenOn = false, keyguardLocked = false))
        assertFalse(UnlockWait.isUsable(screenOn = false, keyguardLocked = true))
    }

    @Test
    fun alreadyUsableReturnsImmediately() = runTest {
        val outcome = UnlockWait.await({ true }, never, Channel(), timeoutMs = 60_000)
        assertEquals(UnlockOutcome.USABLE, outcome)
        assertEquals(0L, currentTime)
    }

    @Test
    fun wakeupTriggersAnImmediateRecheck() = runTest {
        var usable = false
        val wakeups = Channel<Unit>(Channel.CONFLATED)
        val wait = async { UnlockWait.await({ usable }, never, wakeups, timeoutMs = 60_000, pollMs = 10_000) }
        advanceTimeBy(1_234)
        usable = true
        wakeups.send(Unit)
        runCurrent()
        assertTrue(wait.isCompleted)
        assertEquals(UnlockOutcome.USABLE, wait.await())
        assertEquals(1_234L, currentTime)
    }

    @Test
    fun pollingFindsAnUnlockWithoutBroadcasts() = runTest {
        var usable = false
        val wait = async { UnlockWait.await({ usable }, never, Channel(), timeoutMs = 60_000) }
        launch {
            delay(1_200)
            usable = true
        }
        assertEquals(UnlockOutcome.USABLE, wait.await())
        assertTrue("found by the 500 ms poll, at $currentTime", currentTime in 1_200..1_500)
    }

    @Test
    fun declineEndsTheWaitEvenWhenItsWakeupRacesThePoll() = runTest {
        var declined = false
        val wakeups = Channel<Unit>(Channel.CONFLATED)
        val wait = async { UnlockWait.await(never, { declined }, wakeups, timeoutMs = 60_000) }
        // Exactly on a poll boundary: the wake-up may be lost to the poll's timeout, the flag is not.
        advanceTimeBy(3_000)
        declined = true
        wakeups.send(Unit)
        assertEquals(UnlockOutcome.DECLINED, wait.await())
        assertTrue(currentTime <= 3_500)
    }

    @Test
    fun declineWinsOverUsable() = runTest {
        assertEquals(UnlockOutcome.DECLINED, UnlockWait.await({ true }, { true }, Channel(), timeoutMs = 60_000))
    }

    @Test
    fun pausedBeforeTheWaitNeverReportsUsable() = runTest {
        assertEquals(UnlockOutcome.PAUSED, UnlockWait.await({ true }, never, Channel(), timeoutMs = 60_000, isPaused = { true }))
        assertEquals(UnlockOutcome.PAUSED, UnlockWait.await({ true }, { true }, Channel(), timeoutMs = 60_000, isPaused = { true }))
        assertEquals(0L, currentTime)
    }

    @Test
    fun pausingDuringTheWaitEndsItEvenIfThePhoneIsThenUnlocked() = runTest {
        var paused = false
        var usable = false
        val wakeups = Channel<Unit>(Channel.CONFLATED)
        val wait = async { UnlockWait.await({ usable }, never, wakeups, timeoutMs = 60_000, isPaused = { paused }) }
        advanceTimeBy(5_000)
        // The user taps Pause on the lock screen, then unlocks to look: the unlock broadcast must not let the call run.
        paused = true
        usable = true
        wakeups.send(Unit)
        runCurrent()
        assertEquals(UnlockOutcome.PAUSED, wait.await())
        assertEquals(5_000L, currentTime)
    }

    @Test
    fun pollingNoticesAPauseWithoutBroadcasts() = runTest {
        var paused = false
        val wait = async { UnlockWait.await(never, never, Channel(), timeoutMs = 60_000, isPaused = { paused }) }
        launch {
            delay(2_100)
            paused = true
        }
        assertEquals(UnlockOutcome.PAUSED, wait.await())
        assertTrue("found by the 500 ms poll, at $currentTime", currentTime in 2_100..2_600)
    }

    @Test
    fun timesOutWhenStillLocked() = runTest {
        val outcome = UnlockWait.await(never, never, Channel(), timeoutMs = 60_000)
        assertEquals(UnlockOutcome.TIMED_OUT, outcome)
        assertEquals(60_000L, currentTime)
    }

    @Test
    fun closedWakeupChannelFallsBackToPolling() = runTest {
        var checks = 0
        val wakeups = Channel<Unit>().apply { close() }
        val outcome = UnlockWait.await({ ++checks >= 4 }, never, wakeups, timeoutMs = 60_000, pollMs = 500)
        assertEquals(UnlockOutcome.USABLE, outcome)
        assertTrue("no busy loop on a closed channel: $checks checks at $currentTime ms", currentTime >= 1_000)
    }

    @Test
    fun zeroTimeoutChecksOnce() = runTest {
        assertEquals(UnlockOutcome.TIMED_OUT, UnlockWait.await(never, never, Channel(), timeoutMs = 0))
    }

    @Test
    fun promptTextNamesAgentAndAction() {
        assertEquals(
            "Unlock your phone to let Claude send a WhatsApp message to Tania Cohen.",
            UnlockText.message("Claude", "send a WhatsApp message to Tania Cohen."),
        )
        assertEquals("Unlock your phone to let your agent continue.", UnlockText.message("  ", ""))
        assertTrue(UnlockText.message("Claude", "x".repeat(500)).length < 300)
    }

    @Test
    fun toolMessagesTellTheAgentWhatToDo() {
        val declined = UnlockPrompt.declinedMessage("Sending a WhatsApp message")
        assertTrue(declined, declined.startsWith("Sending a WhatsApp message needs the phone unlocked") && declined.contains("Don't retry"))
        val timedOut = UnlockPrompt.timedOutMessage("This action", 60_000)
        assertTrue(timedOut, timedOut.contains("60 s") && timedOut.contains("Ask the user to unlock the phone"))
        assertTrue(UnlockPrompt.PAUSED_MESSAGE, UnlockPrompt.PAUSED_MESSAGE.contains("paused") && UnlockPrompt.PAUSED_MESSAGE.contains("nothing was done"))
    }
}
