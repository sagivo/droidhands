package dev.phonebridge.android.service

import dev.phonebridge.core.transport.RelayStatus

/**
 * When to wake the phone so relay reconnects keep going while it sleeps (pure, unit-tested; driven by
 * [RelayReconnects]). Thread-safe.
 *
 * RelayClient waits between reconnect attempts with coroutine delays, whose clock stops while the CPU
 * is suspended. Once the socket is gone nothing wakes the phone, so in Doze a retry could wait for
 * minutes or hours. So while the relay is waiting to retry, or connecting, an inexact allow-while-idle
 * alarm is kept a little after the moment the client should have moved on. Every status change moves
 * the alarm, so it only fires when the client's own timer stalled; the relay then reconnects under a
 * short wake lock, released once that attempt has finished.
 *
 * Reconnecting starts the client's backoff from scratch, so alarm-driven reconnects back off on their
 * own (doubling from [baseBackoffMs] up to [maxBackoffMs], reset once connected): a relay that stays
 * down costs one wake-up per period, not one per second.
 */
class RelayWakeups(
    /** How long after the client's planned retry the alarm fires (the client normally wins when awake). */
    private val slackMs: Long = SLACK_MS,
    /** How long a connection attempt may take before it counts as stuck (connect + welcome timeouts). */
    private val connectingGraceMs: Long = CONNECTING_GRACE_MS,
    private val baseBackoffMs: Long = BASE_BACKOFF_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS,
) {
    /** What to do after a relay status change. */
    data class Step(
        /** Set the alarm this far from now; null = cancel it. */
        val alarmInMs: Long?,
        /** The reconnect started by the alarm has finished: release its wake lock. */
        val releaseWakeLock: Boolean = false,
    )

    private var status: RelayStatus = RelayStatus.Stopped

    /** Alarm-driven reconnects since the relay was last connected. */
    private var alarmRetries = 0

    /** An alarm-driven reconnect is in progress (its wake lock is held). */
    private var retrying = false

    @Synchronized
    fun onStatus(status: RelayStatus): Step {
        this.status = status
        if (status is RelayStatus.Connected || status is RelayStatus.Stopped) alarmRetries = 0
        val release = retrying && status != RelayStatus.Connecting
        if (release) retrying = false
        return Step(alarmFor(status), release)
    }

    /**
     * The alarm fired. Returns null if nothing is pending (connected, stopped or failed for good);
     * otherwise the relay should reconnect now, under a wake lock, and the alarm be set again this far
     * from now, in case that attempt stalls too.
     */
    @Synchronized
    fun onAlarm(): Long? {
        if (!isPending(status)) return null
        alarmRetries = (alarmRetries + 1).coerceAtMost(MAX_RETRY_COUNT)
        retrying = true
        return maxOf(connectingGraceMs, backoffMs())
    }

    private fun alarmFor(status: RelayStatus): Long? = when {
        status is RelayStatus.Error && isPending(status) -> maxOf((status.retryInMs ?: 0) + slackMs, backoffMs())
        status == RelayStatus.Connecting -> maxOf(connectingGraceMs, backoffMs())
        else -> null
    }

    private fun backoffMs(): Long =
        if (alarmRetries == 0) 0 else minOf(baseBackoffMs shl (alarmRetries - 1).coerceAtMost(MAX_SHIFT), maxBackoffMs)

    companion object {
        const val SLACK_MS = 2_000L
        const val CONNECTING_GRACE_MS = 45_000L
        const val BASE_BACKOFF_MS = 4_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
        private const val MAX_RETRY_COUNT = 64
        private const val MAX_SHIFT = 30

        /** Waiting to retry after a failure, or connecting: the relay is not connected yet but will be. */
        fun isPending(status: RelayStatus): Boolean = when (status) {
            is RelayStatus.Error -> !status.fatal && status.retryInMs != null
            RelayStatus.Connecting -> true
            else -> false
        }
    }
}
