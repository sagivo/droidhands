package dev.phonebridge.android.tools.support

import android.content.Context

/**
 * "Unlock to continue": when a tool needs the screen on and unlocked (UI automation, WhatsApp/email send),
 * instead of failing immediately it asks the user — a high-priority notification
 * "Unlock your phone to let <agent> <action>" — and waits for ACTION_USER_PRESENT / screen-on+unlocked.
 *
 * STUB — implemented by the services agent (wave 2). Callers: screen/WhatsApp/email tools.
 */
object UnlockPrompt {
    /**
     * Returns true as soon as the screen is on and the keyguard is dismissed (immediately if it already is);
     * false after [timeoutMs] or if the coroutine is cancelled (the notification is removed either way).
     * [agentName] is the token name (CallContext.tokenName); [action] e.g. "send a WhatsApp message to Tania Cohen".
     */
    suspend fun awaitUsable(context: Context, agentName: String, action: String, timeoutMs: Long = 60_000): Boolean = TODO()
}
