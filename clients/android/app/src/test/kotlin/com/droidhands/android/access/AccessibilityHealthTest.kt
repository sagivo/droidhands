package com.droidhands.android.access

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityHealthTest {
    private fun health() = AccessibilityHealth(emptyGraceMs = 1_000, silenceMs = 5_000, maxProbeGapMs = 10_000)

    @Test
    fun `empty windows for over a second while usable means stuck`() {
        val h = health()
        h.onProbe(1_000, usable = true, empty = true)
        assertFalse(h.isStuck(1_000, usable = true))
        assertTrue(h.awaitingConfirmation())
        h.onProbe(1_600, usable = true, empty = true)
        assertFalse(h.isStuck(1_600, usable = true))
        h.onProbe(2_000, usable = true, empty = true)
        assertTrue(h.isStuck(2_000, usable = true))
        assertFalse(h.awaitingConfirmation())
        // The verdict persists while locked, until there is evidence of health.
        assertTrue(h.isStuck(3_000, usable = false))
        h.onProbe(4_000, usable = true, empty = false)
        assertFalse(h.isStuck(4_000, usable = true))
    }

    @Test
    fun `probes while locked or screen off are ignored`() {
        val h = health()
        h.onProbe(1_000, usable = false, empty = true)
        h.onProbe(3_000, usable = false, empty = true)
        assertFalse(h.isStuck(3_000, usable = true))
        assertFalse(h.awaitingConfirmation())
    }

    @Test
    fun `empty probes far apart start over`() {
        val h = health()
        h.onProbe(1_000, usable = true, empty = true)
        h.onProbe(20_000, usable = true, empty = true)
        assertFalse(h.isStuck(20_000, usable = true))
        h.onProbe(21_100, usable = true, empty = true)
        assertTrue(h.isStuck(21_100, usable = true))
    }

    @Test
    fun `no events after an expected change means stuck, any event clears it`() {
        val h = health()
        h.onExpectedChange(1_000, usable = true)
        assertFalse(h.isStuck(5_999, usable = true))
        assertTrue(h.isStuck(6_000, usable = true))
        // Only reported while usable (a locked phone may legitimately be quiet).
        assertFalse(h.isStuck(6_000, usable = false))
        h.onEvent()
        assertFalse(h.isStuck(20_000, usable = true))
    }

    @Test
    fun `expected changes while unusable are ignored and the first one counts`() {
        val h = health()
        h.onExpectedChange(1_000, usable = false)
        assertFalse(h.isStuck(10_000, usable = true))
        h.onExpectedChange(10_000, usable = true)
        h.onExpectedChange(14_000, usable = true)
        assertTrue(h.isStuck(15_000, usable = true))
    }

    @Test
    fun `reset forgets everything`() {
        val h = health()
        h.onProbe(1_000, usable = true, empty = true)
        h.onProbe(2_500, usable = true, empty = true)
        h.onExpectedChange(1_000, usable = true)
        assertTrue(h.isStuck(9_000, usable = true))
        h.reset()
        assertFalse(h.isStuck(9_000, usable = true))
    }
}
