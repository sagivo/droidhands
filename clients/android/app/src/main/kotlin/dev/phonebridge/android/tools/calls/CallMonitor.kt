package dev.phonebridge.android.tools.calls

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The phone's call state and control of the ringing / current call through [TelecomManager].
 * Telecom covers every SIM and calling apps that integrate with it. Callers check permissions
 * first: READ_PHONE_STATE for [snapshot], ANSWER_PHONE_CALLS for [answer] and [end]; a
 * permission revoked meanwhile surfaces as a [ToolException]. All methods are main-safe.
 */
internal class CallMonitor(private val context: Context) {
    private val telecom: TelecomManager =
        context.getSystemService(TelecomManager::class.java) ?: throw ToolException(NO_TELECOM)

    /**
     * TelecomManager has no public "is ringing"; TelephonyManager.getCallState() is the device-wide
     * state across all calls (deprecated in API 31 in favour of per-SIM or Telecom queries, neither
     * of which reports ringing for the whole device) and needs READ_PHONE_STATE.
     */
    @SuppressLint("MissingPermission") // READ_PHONE_STATE is checked by the tools.
    @Suppress("DEPRECATION")
    suspend fun snapshot(): CallSnapshot = onIo {
        val telephonyState = context.getSystemService(TelephonyManager::class.java)?.callState
        val mode = runCatching { context.getSystemService(AudioManager::class.java)?.mode }.getOrNull()
        CallStates.snapshot(
            ringing = telephonyState?.let(CallStates::fromTelephony) == CallState.RINGING,
            inCall = telecom.isInCall,
            inManagedCall = telecom.isInManagedCall,
            audioInCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION,
        )
    }

    /** Answers the ringing call (deprecated in API 29 for dialers, still the public way for other apps). */
    @SuppressLint("MissingPermission") // ANSWER_PHONE_CALLS is checked by the tool.
    @Suppress("DEPRECATION")
    suspend fun answer() = onIo { telecom.acceptRingingCall() }

    /** Ends the foreground call, or rejects the ringing one. False when Android refused (e.g. an emergency call). */
    @SuppressLint("MissingPermission") // ANSWER_PHONE_CALLS is checked by the tool.
    @Suppress("DEPRECATION")
    suspend fun end(): Boolean = onIo { telecom.endCall() }

    /** Polls [snapshot] until [done] holds or [timeoutMs] passes; returns the last snapshot. */
    suspend fun await(timeoutMs: Long, done: (CallSnapshot) -> Boolean): CallSnapshot {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var last = snapshot()
        while (!done(last) && System.nanoTime() < deadline) {
            delay(POLL_MS)
            last = snapshot()
        }
        return last
    }

    private suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: SecurityException) {
            throw ToolException(PERMISSION_DENIED)
        }
    }

    companion object {
        private const val POLL_MS = 200L

        const val NO_TELECOM = "This device has no phone-call service (Telecom), so PhoneBridge can't see or control calls."

        const val PERMISSION_DENIED =
            "Android denied access to phone calls. Ask the user to open the PhoneBridge app > Setup and grant " +
                "Phone & call log (it includes answering and ending calls)."
    }
}
