package dev.phonebridge.android.tools.calls

import android.telephony.TelephonyManager
import dev.phonebridge.core.tools.ToolException

/** `call_status.state` (docs/TOOLS.md). */
enum class CallState(val arg: String) {
    IDLE("idle"),
    RINGING("ringing"),
    IN_CALL("in_call"),
}

/** The phone's call situation at one moment (Android-free). */
data class CallSnapshot(
    val state: CallState,
    /** A call is ringing while another one is already active (call waiting). Best effort. */
    val callWaiting: Boolean = false,
    /** The call is handled by a calling app (WhatsApp, Telegram…) through Android's Telecom, not the SIM. */
    val appCall: Boolean = false,
)

/**
 * Maps Android's call signals to [CallSnapshot]s and checks call_answer / call_end preconditions
 * (pure: uses only compile-time telephony constants).
 */
object CallStates {
    /**
     * [ringing]: TelephonyManager's device-wide call state is RINGING (a call rings or waits).
     * From TelecomManager: [inCall] = `isInCall` (true while dialing, ringing, active or on hold,
     * for SIM and calling-app calls), [inManagedCall] = `isInManagedCall` (SIM calls only).
     * [audioInCall]: AudioManager mode is IN_CALL or IN_COMMUNICATION, which during ringing means
     * another call is already up.
     */
    fun snapshot(ringing: Boolean, inCall: Boolean, inManagedCall: Boolean, audioInCall: Boolean = false): CallSnapshot {
        val appCall = (inCall || ringing) && !inManagedCall
        return when {
            ringing -> CallSnapshot(CallState.RINGING, callWaiting = audioInCall, appCall = appCall)
            inCall -> CallSnapshot(CallState.IN_CALL, appCall = appCall)
            else -> CallSnapshot(CallState.IDLE)
        }
    }

    /** TelephonyManager CALL_STATE_* (from a PhoneStateListener) → [CallState]; unknown values are IDLE. */
    fun fromTelephony(state: Int): CallState = when (state) {
        TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> CallState.IN_CALL
        else -> CallState.IDLE
    }

    /** The incoming number as reported by telephony, or null when withheld/unknown (empty, or not digits). */
    fun cleanNumber(raw: String?): String? {
        val number = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return number.takeIf { n -> n.count(Char::isDigit) >= MIN_DIGITS }
    }

    /** Throws unless a call is ringing (call_answer). */
    fun requireRinging(snapshot: CallSnapshot) {
        when (snapshot.state) {
            CallState.RINGING -> Unit
            CallState.IN_CALL -> throw ToolException(
                "No call is ringing: the phone is already in a call (answered or outgoing). call_answer only answers an " +
                    "incoming call while it rings. Use call_end to hang up.",
            )
            CallState.IDLE -> throw ToolException(
                "No call is ringing right now, so there is nothing to answer. call_answer works only while an incoming call " +
                    "rings; check with call_status.",
            )
        }
    }

    /** Throws unless there is a call to end or reject (call_end). */
    fun requireCall(snapshot: CallSnapshot) {
        if (snapshot.state == CallState.IDLE) {
            throw ToolException("There is no call to end: the phone is not ringing and not in a call (call_status says idle).")
        }
    }

    /**
     * Hint for `call_status`, or null: why a ringing call's number is missing ([numberKnown] false;
     * [canReadNumber] = the permissions are granted) and what call waiting means for answer/end.
     */
    fun statusNote(snapshot: CallSnapshot, numberKnown: Boolean, canReadNumber: Boolean): String? {
        if (snapshot.state != CallState.RINGING) return null
        val notes = buildList {
            if (!numberKnown) {
                add(
                    if (canReadNumber) {
                        "Android did not reveal the caller's number (it may be withheld, or the call is in a calling app)."
                    } else {
                        "To see the caller's number, the user must grant PhoneBridge Phone & call log access (PhoneBridge > Setup)."
                    },
                )
            }
            if (snapshot.callWaiting) {
                add("Another call is already active: call_answer usually puts it on hold, call_end rejects the ringing call.")
            }
        }
        return notes.joinToString(" ").ifEmpty { null }
    }

    /** Throws when call_answer didn't take effect: [after] is the state once Android had time to answer. */
    fun requireAnswered(after: CallSnapshot) {
        when (after.state) {
            CallState.IN_CALL -> Unit
            CallState.IDLE -> throw ToolException("The call ended before it could be answered (the caller hung up or it went to voicemail).")
            CallState.RINGING -> throw ToolException(
                "Android accepted the request, but the call is still ringing. Ask the user to answer it on the phone." +
                    if (after.appCall) " Calls in calling apps (WhatsApp, Telegram…) may only be answerable in that app." else "",
            )
        }
    }

    /** Agent-facing error when TelecomManager.endCall() returns false. */
    fun endFailedMessage(snapshot: CallSnapshot): String =
        "Android did not end the call. Emergency calls can't be ended by apps." +
            if (snapshot.appCall) {
                " This call is in a calling app (not a SIM call); end it in that app instead (app_open, then screen tools) or ask the user."
            } else {
                " Ask the user to end it on the phone."
            }

    private const val MIN_DIGITS = 3
}
