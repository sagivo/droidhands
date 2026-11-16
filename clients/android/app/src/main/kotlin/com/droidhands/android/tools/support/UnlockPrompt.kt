package com.droidhands.android.tools.support

import android.content.Context
import com.droidhands.android.confirm.UnlockOutcome
import com.droidhands.android.confirm.UnlockPrompts
import com.droidhands.android.graph.BridgeGraph
import com.droidhands.core.tools.ToolException

/**
 * "Unlock to continue": when a tool needs the screen on and unlocked (UI automation, WhatsApp/email send),
 * instead of failing immediately it asks the user — a high-priority notification
 * "Unlock your phone to let <agent> <action>" with a "Not now" button — and waits for
 * ACTION_USER_PRESENT / screen-on+unlocked (polling every 500 ms as a fallback).
 *
 * If the user pauses agent access (the kill switch) before or during the wait, it ends at once with a
 * [ToolException] (or [UnlockOutcome.PAUSED] from [await]), even if the phone gets unlocked: nothing may
 * run while paused.
 *
 * Callers: screen/WhatsApp/email tools. Call from a background dispatcher (it makes binder calls).
 */
object UnlockPrompt {
    /**
     * Returns true as soon as the screen is on and the keyguard is dismissed (immediately if it already is);
     * false after [timeoutMs] or if the user tapped "Not now". Throws a [ToolException] if agent access is
     * (or gets) paused. The notification is removed and the receivers unregistered in every case,
     * including cancellation of the calling coroutine (which then propagates as usual instead of returning).
     * [agentName] is the token name (CallContext.tokenName); [action] e.g. "send a WhatsApp message to Tania Cohen".
     */
    suspend fun awaitUsable(context: Context, agentName: String, action: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean =
        when (await(context, agentName, action, timeoutMs)) {
            UnlockOutcome.USABLE -> true
            UnlockOutcome.PAUSED -> throw ToolException(PAUSED_MESSAGE)
            UnlockOutcome.DECLINED, UnlockOutcome.TIMED_OUT -> false
        }

    /** Like [awaitUsable], but says why the wait ended (never throws for a pause: returns [UnlockOutcome.PAUSED]). */
    suspend fun await(context: Context, agentName: String, action: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): UnlockOutcome =
        UnlockPrompts.await(context, agentName, action, timeoutMs, isPaused = { isAccessPaused(context) })

    /** The kill switch; fails closed (paused) if the settings can't be read. */
    private fun isAccessPaused(context: Context): Boolean = try {
        !BridgeGraph.get(context).settings.state.value.accessEnabled
    } catch (e: RuntimeException) {
        true
    }

    /**
     * [await], throwing a [ToolException] that tells the agent what happened and what the user must do
     * when the phone did not become usable. [what] names the action for the message, e.g. "Sending a WhatsApp message".
     */
    suspend fun requireUsable(
        context: Context,
        agentName: String,
        action: String,
        what: String = "This action",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) {
        when (await(context, agentName, action, timeoutMs)) {
            UnlockOutcome.USABLE -> Unit
            UnlockOutcome.DECLINED -> throw ToolException(declinedMessage(what))
            UnlockOutcome.TIMED_OUT -> throw ToolException(timedOutMessage(what, timeoutMs))
            UnlockOutcome.PAUSED -> throw ToolException(PAUSED_MESSAGE)
        }
    }

    const val DEFAULT_TIMEOUT_MS = 60_000L

    internal const val PAUSED_MESSAGE =
        "Agent access was paused on the phone, so nothing was done. Ask the user to resume it in the DroidHands app."

    internal fun declinedMessage(what: String): String =
        "$what needs the phone unlocked, and the user tapped \"Not now\" on the unlock prompt. " +
            "Don't retry on your own; ask the user whether and when to try again."

    internal fun timedOutMessage(what: String, timeoutMs: Long): String =
        "$what needs the phone unlocked, and it stayed locked for ${(timeoutMs + 999) / 1000} s after DroidHands asked " +
            "the user to unlock it (Android does not let apps unlock the phone). Ask the user to unlock the phone, then retry. " +
            "(Tip: notification_reply works while locked.)"
}
