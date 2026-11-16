package com.droidhands.android.access

/**
 * Detects an accessibility service that is bound but "stuck" (pure, thread-safe). Seen right after an app
 * update or reinstall: Android reconnects the service, but it receives no windows and no events until the user
 * turns it off and on. Two independent signals, both only counted while the screen is on and unlocked
 * ("usable"):
 *
 * * **Empty probes**: no windows and no active-window root, still so at a probe at least [emptyGraceMs] after
 *   the first such probe (probes more than [maxProbeGapMs] apart start over). A working service always sees at
 *   least the status bar and the foreground app.
 * * **Silence**: the UI was expected to change (the user unlocked the phone, or DroidHands launched an app),
 *   yet no event arrived for [silenceMs].
 *
 * Evidence of health clears the verdict: a non-empty probe clears the probe signal, any event the silence one.
 * Times are `SystemClock.uptimeMillis()`.
 */
class AccessibilityHealth(
    private val emptyGraceMs: Long = 1_000,
    private val silenceMs: Long = 5_000,
    private val maxProbeGapMs: Long = 10_000,
) {
    private val lock = Any()
    private var emptySince: Long? = null
    private var lastEmptyProbe = 0L
    private var probedStuck = false
    private var expectedChangeAt: Long? = null

    /** Starts over (service (re)connected). */
    fun reset() = synchronized(lock) {
        emptySince = null
        lastEmptyProbe = 0L
        probedStuck = false
        expectedChangeAt = null
    }

    /** Any accessibility event arrived. */
    fun onEvent() = synchronized(lock) { expectedChangeAt = null }

    /** Something should produce events soon (unlock, app launch). Ignored unless [usable]. */
    fun onExpectedChange(now: Long, usable: Boolean) = synchronized(lock) {
        if (usable && expectedChangeAt == null) expectedChangeAt = now
    }

    /** Result of a window probe; [empty]: no windows and no active root. Ignored unless [usable]. */
    fun onProbe(now: Long, usable: Boolean, empty: Boolean) = synchronized(lock) {
        if (!usable) return@synchronized
        if (!empty) {
            emptySince = null
            probedStuck = false
            return@synchronized
        }
        val since = emptySince
        if (since == null || now - lastEmptyProbe > maxProbeGapMs) {
            emptySince = now
        } else if (now - since >= emptyGraceMs) {
            probedStuck = true
        }
        lastEmptyProbe = now
    }

    /** True if the service looks stuck now. The silence signal needs [usable]; the probe signal persists. */
    fun isStuck(now: Long, usable: Boolean): Boolean = synchronized(lock) {
        val expected = expectedChangeAt
        probedStuck || (usable && expected != null && now - expected >= silenceMs)
    }

    /** True while empty probes have been seen but the grace period hasn't passed yet. */
    fun awaitingConfirmation(): Boolean = synchronized(lock) { emptySince != null && !probedStuck }

    companion object {
        /** Agent-facing explanation of the stuck state (bridge_capabilities and screen tools). */
        const val STUCK_MESSAGE =
            "DroidHands's accessibility service seems stuck (common right after an app update). " +
                "Ask the user to turn DroidHands off and on in Settings > Accessibility."

        /** Agent-facing explanation when the service isn't connected at all. */
        const val NOT_RUNNING_MESSAGE =
            "DroidHands's accessibility service is not running. Ask the user to turn it on in DroidHands app > Settings > Permissions & access > " +
                "Accessibility (if Android already shows it as on, turn it off and on again in Settings > Accessibility)."
    }
}
