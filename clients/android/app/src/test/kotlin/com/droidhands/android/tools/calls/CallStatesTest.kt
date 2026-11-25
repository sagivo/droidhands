package com.droidhands.android.tools.calls

import com.droidhands.android.Edition
import android.telephony.TelephonyManager
import com.droidhands.core.tools.ToolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStatesTest {
    @Test
    fun `idle ringing and in_call`() {
        assertEquals(CallSnapshot(CallState.IDLE), CallStates.snapshot(ringing = false, inCall = false, inManagedCall = false))
        assertEquals(CallSnapshot(CallState.RINGING), CallStates.snapshot(ringing = true, inCall = true, inManagedCall = true))
        assertEquals(CallSnapshot(CallState.IN_CALL), CallStates.snapshot(ringing = false, inCall = true, inManagedCall = true))
    }

    @Test
    fun `ringing wins over an active call and is call waiting when audio is in a call`() {
        val waiting = CallStates.snapshot(ringing = true, inCall = true, inManagedCall = true, audioInCall = true)
        assertEquals(CallSnapshot(CallState.RINGING, callWaiting = true), waiting)
        // Audio mode only means call waiting while something rings.
        assertEquals(CallSnapshot(CallState.IN_CALL), CallStates.snapshot(ringing = false, inCall = true, inManagedCall = true, audioInCall = true))
    }

    @Test
    fun `calls outside the SIM are app calls`() {
        assertEquals(CallSnapshot(CallState.IN_CALL, appCall = true), CallStates.snapshot(ringing = false, inCall = true, inManagedCall = false))
        assertEquals(CallSnapshot(CallState.RINGING, appCall = true), CallStates.snapshot(ringing = true, inCall = true, inManagedCall = false))
        assertEquals(CallSnapshot(CallState.IDLE), CallStates.snapshot(ringing = false, inCall = false, inManagedCall = false, audioInCall = true))
    }

    @Test
    fun `telephony states map to call states`() {
        assertEquals(CallState.IDLE, CallStates.fromTelephony(TelephonyManager.CALL_STATE_IDLE))
        assertEquals(CallState.RINGING, CallStates.fromTelephony(TelephonyManager.CALL_STATE_RINGING))
        assertEquals(CallState.IN_CALL, CallStates.fromTelephony(TelephonyManager.CALL_STATE_OFFHOOK))
        assertEquals(CallState.IDLE, CallStates.fromTelephony(42))
    }

    @Test
    fun `state args match the tool catalog`() {
        assertEquals(listOf("idle", "ringing", "in_call"), CallState.entries.map { it.arg })
    }

    @Test
    fun `incoming numbers are cleaned`() {
        assertEquals("+972541234567", CallStates.cleanNumber(" +972541234567 "))
        assertNull(CallStates.cleanNumber(""))
        assertNull(CallStates.cleanNumber(null))
        assertNull(CallStates.cleanNumber("-1"))
        assertNull(CallStates.cleanNumber("Private"))
    }

    @Test
    fun `answer needs a ringing call`() {
        CallStates.requireRinging(CallSnapshot(CallState.RINGING))
        val idle = assertThrows(ToolException::class.java) { CallStates.requireRinging(CallSnapshot(CallState.IDLE)) }
        assertTrue(idle.message!!.contains("No call is ringing"))
        val active = assertThrows(ToolException::class.java) { CallStates.requireRinging(CallSnapshot(CallState.IN_CALL)) }
        assertTrue(active.message!!.contains("call_end"))
    }

    @Test
    fun `answer outcome`() {
        CallStates.requireAnswered(CallSnapshot(CallState.IN_CALL))
        val ended = assertThrows(ToolException::class.java) { CallStates.requireAnswered(CallSnapshot(CallState.IDLE)) }
        assertTrue(ended.message!!.contains("ended before"))
        val ringing = assertThrows(ToolException::class.java) { CallStates.requireAnswered(CallSnapshot(CallState.RINGING, appCall = true)) }
        assertTrue(ringing.message!!.contains("still ringing"))
        assertTrue(ringing.message!!.contains("calling apps"))
    }

    @Test
    fun `end needs a call`() {
        CallStates.requireCall(CallSnapshot(CallState.RINGING))
        CallStates.requireCall(CallSnapshot(CallState.IN_CALL))
        val e = assertThrows(ToolException::class.java) { CallStates.requireCall(CallSnapshot(CallState.IDLE)) }
        assertTrue(e.message!!.contains("no call to end"))
    }

    @Test
    fun `end failure explains app calls and emergency calls`() {
        assertTrue(CallStates.endFailedMessage(CallSnapshot(CallState.IN_CALL)).contains("Emergency"))
        assertTrue(CallStates.endFailedMessage(CallSnapshot(CallState.IN_CALL, appCall = true)).contains("in that app"))
    }

    @Test
    fun `status note explains a missing number and call waiting`() {
        assertNull(CallStates.statusNote(CallSnapshot(CallState.IN_CALL), numberKnown = false, canReadNumber = false))
        assertNull(CallStates.statusNote(CallSnapshot(CallState.RINGING), numberKnown = true, canReadNumber = true))
        // The store edition has no call log, so there is nothing to grant (docs/EDITIONS.md).
        val noNumber = CallStates.statusNote(CallSnapshot(CallState.RINGING), numberKnown = false, canReadNumber = false)!!
        assertTrue(noNumber, noNumber.contains(if (Edition.SMS_AND_CALL_LOG) "Permissions & access" else "can't see the caller's number"))
        assertTrue(CallStates.statusNote(CallSnapshot(CallState.RINGING), numberKnown = false, canReadNumber = true)!!.contains("withheld"))
        val waiting = CallStates.statusNote(CallSnapshot(CallState.RINGING, callWaiting = true), numberKnown = true, canReadNumber = true)!!
        assertTrue(waiting.contains("on hold"))
    }
}
