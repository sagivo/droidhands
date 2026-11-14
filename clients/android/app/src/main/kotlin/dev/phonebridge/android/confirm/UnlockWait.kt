package dev.phonebridge.android.confirm

import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** How an "unlock to continue" wait ended. */
enum class UnlockOutcome {
    /** Screen on and keyguard dismissed: UI automation can run. */
    USABLE,

    /** The user tapped "Not now" on the unlock prompt. */
    DECLINED,

    /** Still locked (or the screen is off) when the time ran out. */
    TIMED_OUT,
}

/** Pure waiting logic behind [dev.phonebridge.android.tools.support.UnlockPrompt] (unit-tested with virtual time). */
internal object UnlockWait {
    /** Fallback re-check interval, for devices or states that don't send the broadcasts. */
    const val POLL_INTERVAL_MS = 500L

    /** UI automation needs the screen on and no keyguard (secure or swipe) in front of the apps. */
    fun isUsable(screenOn: Boolean, keyguardLocked: Boolean): Boolean = screenOn && !keyguardLocked

    /**
     * Re-checks [isDeclined] and [isUsable] now, after every [wakeups] item and at least every
     * [pollMs], until the user declined ([UnlockOutcome.DECLINED]), the phone is usable
     * ([UnlockOutcome.USABLE]) or [timeoutMs] passed (one last check, then [UnlockOutcome.TIMED_OUT]).
     *
     * [wakeups] are only hints to re-check early (a broadcast arrived); the outcome always comes from
     * the two state functions, so a wake-up lost to a poll timeout racing with it changes nothing.
     * A closed [wakeups] channel just leaves polling. Cancellation propagates to the caller.
     */
    suspend fun await(
        isUsable: () -> Boolean,
        isDeclined: () -> Boolean,
        wakeups: ReceiveChannel<Unit>,
        timeoutMs: Long,
        pollMs: Long = POLL_INTERVAL_MS,
    ): UnlockOutcome {
        require(pollMs > 0) { "pollMs must be positive" }
        current(isUsable, isDeclined)?.let { return it }
        return withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) { loop(isUsable, isDeclined, wakeups, pollMs) }
            ?: current(isUsable, isDeclined)
            ?: UnlockOutcome.TIMED_OUT
    }

    private fun current(isUsable: () -> Boolean, isDeclined: () -> Boolean): UnlockOutcome? = when {
        isDeclined() -> UnlockOutcome.DECLINED
        isUsable() -> UnlockOutcome.USABLE
        else -> null
    }

    private suspend fun loop(
        isUsable: () -> Boolean,
        isDeclined: () -> Boolean,
        wakeups: ReceiveChannel<Unit>,
        pollMs: Long,
    ): UnlockOutcome {
        var open = true
        while (true) {
            if (open) {
                val received: ChannelResult<Unit>? = withTimeoutOrNull(pollMs) { wakeups.receiveCatching() }
                if (received != null && received.isClosed) open = false
            } else {
                delay(pollMs)
            }
            current(isUsable, isDeclined)?.let { return it }
        }
    }
}

/** Pure text for the unlock prompt. */
object UnlockText {
    const val TITLE = "Unlock to continue"

    /** Shown on a secure lock screen that hides notification content. */
    const val PUBLIC_TEXT = "Unlock your phone so your agent can continue."

    const val DECLINE_ACTION = "Not now"

    private const val MAX_ACTION_CHARS = 200
    private const val MAX_AGENT_CHARS = 60

    /** "Unlock your phone to let Claude send a WhatsApp message to Tania Cohen." */
    fun message(agentName: String, action: String): String {
        val who = DisplayText.clean(agentName, MAX_AGENT_CHARS).ifEmpty { "your agent" }
        val what = DisplayText.clean(action, MAX_ACTION_CHARS).trimEnd('.', ' ').ifEmpty { "continue" }
        return "Unlock your phone to let $who $what."
    }
}
