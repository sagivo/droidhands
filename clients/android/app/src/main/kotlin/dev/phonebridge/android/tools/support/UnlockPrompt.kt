package dev.phonebridge.android.tools.support

import android.content.Context
import dev.phonebridge.android.confirm.UnlockOutcome
import dev.phonebridge.android.confirm.UnlockPrompts
import dev.phonebridge.core.tools.ToolException

/**
 * "Unlock to continue": when a tool needs the screen on and unlocked (UI automation, WhatsApp/email send),
 * instead of failing immediately it asks the user — a high-priority notification
 * "Unlock your phone to let <agent> <action>" with a "Not now" button — and waits for
 * ACTION_USER_PRESENT / screen-on+unlocked (polling every 500 ms as a fallback).
 *
 * Callers: screen/WhatsApp/email tools. Call from a background dispatcher (it makes binder calls).
 */
object UnlockPrompt {
    /**
     * Returns true as soon as the screen is on and the keyguard is dismissed (immediately if it already is);
     * false after [timeoutMs] or if the user tapped "Not now". The notification is removed and the
     * receivers unregistered in every case, including cancellation of the calling coroutine (which
     * then propagates as usual instead of returning).
     * [agentName] is the token name (CallContext.tokenName); [action] e.g. "send a WhatsApp message to Tania Cohen".
     */
    suspend fun awaitUsable(context: Context, agentName: String, action: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean =
        await(context, agentName, action, timeoutMs) == UnlockOutcome.USABLE

    /** Like [awaitUsable], but says why the wait ended. */
    suspend fun await(context: Context, agentName: String, action: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): UnlockOutcome =
        UnlockPrompts.await(context, agentName, action, timeoutMs)

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
        }
    }

    const val DEFAULT_TIMEOUT_MS = 60_000L

    internal fun declinedMessage(what: String): String =
        "$what needs the phone unlocked, and the user tapped \"Not now\" on the unlock prompt. " +
            "Don't retry on your own; ask the user whether and when to try again."

    internal fun timedOutMessage(what: String, timeoutMs: Long): String =
        "$what needs the phone unlocked, and it stayed locked for ${(timeoutMs + 999) / 1000} s after PhoneBridge asked " +
            "the user to unlock it (Android does not let apps unlock the phone). Ask the user to unlock the phone, then retry. " +
            "(Tip: notification_reply works while locked.)"
}
