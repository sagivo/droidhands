package com.droidhands.android.live

import com.droidhands.core.audit.AuditOutcome
import com.droidhands.core.mcp.ActiveCall
import com.droidhands.core.tools.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActivityStateTest {
    private val state = AgentActivityState(showAfterMs = 1_500, lingerMs = 4_000)

    private fun call(id: Long, risk: Risk = Risk.READ, at: Long = 0, token: String = "t1") =
        ActiveCall(id, token, "Claude Code", "tool_$id", "Tool $id", risk, startedAt = at)

    private fun running(now: Long) = state.snapshot(now) as? ActivitySnapshot.Running

    @Test
    fun aFastReadOnlyCallNeverShows() {
        val read = call(1)
        state.started(read)
        assertNull(state.snapshot(0))
        assertEquals(1_500L, state.nextChangeAt(0))
        state.finished(read, AuditOutcome.OK)
        assertNull(state.snapshot(50))
        assertNull("nothing left to wait for", state.nextChangeAt(50))
    }

    @Test
    fun aSlowReadOnlyCallShowsAfterTheDelay() {
        state.started(call(1))
        assertNull(state.snapshot(1_499))
        assertEquals(listOf(1L), running(1_500)!!.calls.map { it.id })
    }

    @Test
    fun actionsAndSensitiveCallsShowAtOnce() {
        state.started(call(1, Risk.ACTION))
        state.started(call(2, Risk.SENSITIVE))
        assertEquals(listOf(1L, 2L), running(0)!!.calls.map { it.id })
    }

    @Test
    fun aReadOnlyCallWaitingForApprovalShowsAtOnceAndStaysShown() {
        val read = call(1)
        state.started(read)
        state.updated(read.copy(awaitingApproval = true))
        assertTrue(running(10)!!.calls.single().awaitingApproval)
        // Approved quickly: it stays up instead of flickering away.
        state.updated(read.copy(awaitingApproval = false))
        assertEquals(1, running(20)!!.calls.size)
    }

    @Test
    fun onlyDueCallsAreListedWhileOthersAreFast() {
        state.started(call(1, Risk.ACTION))
        state.started(call(2, at = 100))
        assertEquals(listOf(1L), running(200)!!.calls.map { it.id })
        assertEquals(1_600L, state.nextChangeAt(200))
        assertEquals(listOf(1L, 2L), running(1_600)!!.calls.map { it.id })
    }

    @Test
    fun theOutcomeLingersThenGoesAway() {
        val send = call(1, Risk.SENSITIVE)
        state.started(send)
        running(0)
        state.finished(send, AuditOutcome.OK)
        val finished = state.snapshot(1_000) as ActivitySnapshot.Finished
        assertEquals(listOf(send to AuditOutcome.OK), finished.calls)
        assertEquals(5_000L, state.nextChangeAt(1_000))
        assertTrue(state.snapshot(4_999) is ActivitySnapshot.Finished)
        assertNull(state.snapshot(5_000))
        assertNull(state.nextChangeAt(5_000))
    }

    @Test
    fun theOutcomeListsEveryShownCallButNoFastOnes() {
        val a = call(1, Risk.ACTION)
        val fast = call(2)
        val b = call(3, Risk.ACTION)
        state.started(a)
        state.started(b)
        state.started(fast)
        running(0)
        state.finished(fast, AuditOutcome.OK)
        state.finished(a, AuditOutcome.OK)
        running(10)
        state.finished(b, AuditOutcome.CANCELLED)
        val finished = state.snapshot(20) as ActivitySnapshot.Finished
        assertEquals(listOf(1L to AuditOutcome.OK, 3L to AuditOutcome.CANCELLED), finished.calls.map { it.first.id to it.second })
    }

    @Test
    fun aNewCallReplacesTheOutcome() {
        val first = call(1, Risk.ACTION)
        state.started(first)
        running(0)
        state.finished(first, AuditOutcome.OK)
        assertTrue(state.snapshot(10) is ActivitySnapshot.Finished)
        state.started(call(2, Risk.ACTION, at = 20))
        assertEquals(listOf(2L), running(20)!!.calls.map { it.id })
    }

    @Test
    fun aDismissedCardStaysAwayUntilItsCallsEnd() {
        val first = call(1, Risk.ACTION)
        state.started(first)
        running(0)
        state.dismiss()
        assertNull(state.snapshot(10))
        state.started(call(2, Risk.ACTION, at = 20))
        assertNull("still the same run", state.snapshot(20))
        state.finished(first, AuditOutcome.OK)
        state.finished(call(2, Risk.ACTION, at = 20), AuditOutcome.OK)
        assertNull("no outcome for a dismissed run", state.snapshot(30))
        state.started(call(3, Risk.ACTION, at = 40))
        assertEquals("a new run shows again", listOf(3L), running(40)!!.calls.map { it.id })
    }

    @Test
    fun dismissingTheOutcomeDoesNotHideTheNextRun() {
        val first = call(1, Risk.ACTION)
        state.started(first)
        running(0)
        state.finished(first, AuditOutcome.OK)
        state.snapshot(10)
        state.dismiss()
        assertNull(state.snapshot(20))
        state.started(call(2, Risk.ACTION, at = 30))
        assertEquals(1, running(30)!!.calls.size)
    }

    @Test
    fun updatesAndEndsOfUnknownCallsAreIgnored() {
        state.updated(call(9, Risk.ACTION))
        state.finished(call(9, Risk.ACTION), AuditOutcome.OK)
        assertNull(state.snapshot(0))
    }

    @Test
    fun clearForgetsEverything() {
        state.started(call(1, Risk.ACTION))
        running(0)
        state.clear()
        assertNull(state.snapshot(10))
        assertNull(state.nextChangeAt(10))
    }
}
