package com.droidhands.core.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A time budget that can be paused, used to exclude user-confirmation time from a tool's
 * timeout: the clock stops while [pause]d and resumes with the remaining budget. Pauses nest
 * (concurrent confirmations); the budget runs only when none is active. Thread-safe.
 *
 * [expired] completes once the running (unpaused) time reaches [budgetMs]. The timer is a
 * coroutine in the scope given to [start] and is cancelled by [stop].
 */
internal class PausableDeadline(
    budgetMs: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private val expiredSignal = CompletableDeferred<Unit>()
    private var scope: CoroutineScope? = null
    private var timer: Job? = null
    private var remainingMs = budgetMs.coerceAtLeast(1)
    private var runningSinceNanos = 0L
    private var pauseDepth = 0
    private var epoch = 0L
    private var stopped = false

    val expired: Deferred<Unit> get() = expiredSignal

    val isExpired: Boolean get() = expiredSignal.isCompleted

    fun start(scope: CoroutineScope) {
        synchronized(lock) {
            check(this.scope == null) { "already started" }
            this.scope = scope
            if (pauseDepth == 0 && !stopped) arm()
        }
    }

    /** Stops the clock. Every call must be balanced by [resume] (use try/finally). */
    fun pause() {
        synchronized(lock) {
            if (pauseDepth++ > 0 || stopped || expiredSignal.isCompleted || scope == null) return
            epoch++
            timer?.cancel()
            timer = null
            val elapsedMs = (nanoTime() - runningSinceNanos) / 1_000_000
            remainingMs = (remainingMs - elapsedMs).coerceAtLeast(1)
        }
    }

    fun resume() {
        synchronized(lock) {
            if (pauseDepth == 0) return
            if (--pauseDepth == 0 && !stopped && !expiredSignal.isCompleted && scope != null) arm()
        }
    }

    fun stop() {
        synchronized(lock) {
            stopped = true
            epoch++
            timer?.cancel()
            timer = null
        }
    }

    /** Must hold [lock]. */
    private fun arm() {
        val armedEpoch = ++epoch
        val delayMs = remainingMs
        runningSinceNanos = nanoTime()
        timer = scope!!.launch {
            delay(delayMs)
            synchronized(lock) {
                if (armedEpoch == epoch && pauseDepth == 0 && !stopped) expiredSignal.complete(Unit)
            }
        }
    }
}
