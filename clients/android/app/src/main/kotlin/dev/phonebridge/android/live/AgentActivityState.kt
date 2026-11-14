package dev.phonebridge.android.live

import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.mcp.ActiveCall
import dev.phonebridge.core.tools.Risk

/** What the activity notification shows right now. */
sealed interface ActivitySnapshot {
    /** Calls running now, oldest first. Never empty. */
    data class Running(val calls: List<ActiveCall>) : ActivitySnapshot

    /** The calls that were shown and have all ended, with their audit outcomes, oldest first. Never empty. */
    data class Finished(val calls: List<Pair<ActiveCall, AuditOutcome>>) : ActivitySnapshot
}

/**
 * When the activity notification appears, changes and goes away (pure, single-threaded, unit-tested).
 *
 * A call is shown once it has run for [showAfterMs] or is waiting for approval, and at once if it can change
 * something (ACTION or SENSITIVE), so a 50 ms `notifications_list` never flashes a notification. Once shown, a
 * call stays shown until it ends. When the last shown call ends, the outcome (Done, Failed or Stopped) stays up
 * for [lingerMs]; a call shown meanwhile replaces it. After the user swipes the notification away ([dismiss]),
 * nothing is shown until every shown call has ended.
 */
class AgentActivityState(private val showAfterMs: Long = SHOW_AFTER_MS, private val lingerMs: Long = LINGER_MS) {
    private val running = LinkedHashMap<Long, ActiveCall>()

    /** Ids of running calls that were shown. */
    private val shown = HashSet<Long>()

    /** Shown calls that ended while others still showed. */
    private val ended = ArrayList<Pair<ActiveCall, AuditOutcome>>()
    private var finished: ActivitySnapshot.Finished? = null
    private var finishedUntil = 0L
    private var dismissed = false

    fun started(call: ActiveCall) {
        running[call.id] = call
    }

    fun updated(call: ActiveCall) {
        if (call.id in running) running[call.id] = call
    }

    fun finished(call: ActiveCall, outcome: AuditOutcome) {
        running.remove(call.id) ?: return
        if (shown.remove(call.id)) ended += call to outcome
    }

    /** The user swiped the notification away. */
    fun dismiss() {
        if (shown.isNotEmpty()) dismissed = true
        finished = null
    }

    /** Forgets everything (the setting was turned off). */
    fun clear() {
        running.clear()
        shown.clear()
        ended.clear()
        finished = null
        dismissed = false
    }

    /** What to show at [now], or null for nothing. Call after every change and at [nextChangeAt]. */
    fun snapshot(now: Long): ActivitySnapshot? {
        running.values.filter { it.id in shown || isDue(it, now) }.forEach { shown += it.id }
        if (shown.isNotEmpty()) {
            finished = null
            return if (dismissed) null else ActivitySnapshot.Running(running.values.filter { it.id in shown })
        }
        if (ended.isNotEmpty()) {
            // The last shown call just ended.
            if (!dismissed) {
                finished = ActivitySnapshot.Finished(ended.toList())
                finishedUntil = now + lingerMs
            }
            ended.clear()
            dismissed = false
        }
        if (finished != null && now >= finishedUntil) finished = null
        return finished
    }

    /** When [snapshot] may change without an event (a call becoming due, the outcome expiring), or null. */
    fun nextChangeAt(now: Long): Long? {
        val due = running.values.filter { it.id !in shown }.minOfOrNull { it.startedAt + showAfterMs }
        val expiry = finishedUntil.takeIf { finished != null }
        return listOfNotNull(due, expiry).minOrNull()?.coerceAtLeast(now)
    }

    private fun isDue(call: ActiveCall, now: Long): Boolean =
        call.risk != Risk.READ || call.awaitingApproval || now - call.startedAt >= showAfterMs

    companion object {
        /** Read-only calls running at least this long are shown. */
        const val SHOW_AFTER_MS = 1_500L

        /** How long Done, Failed or Stopped stays up. */
        const val LINGER_MS = 4_000L
    }
}
