package com.droidhands.android.ui.agents

import com.droidhands.core.auth.ActiveHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ScheduleTest {
    private val day = ActiveHours(8 * 60, 23 * 60)
    private val night = ActiveHours(22 * 60, 6 * 60 + 30)

    @Test
    fun formatsTimes() {
        assertEquals("08:00", Schedule.time(480, is24Hour = true, Locale.US))
        assertEquals("23:59", Schedule.time(1439, is24Hour = true, Locale.US))
        assertEquals("8:00 AM", Schedule.time(480, is24Hour = false, Locale.US))
        assertEquals("12:00 AM", Schedule.time(0, is24Hour = false, Locale.US))
        assertEquals("12:30 PM", Schedule.time(750, is24Hour = false, Locale.US))
        assertEquals("11:05 PM", Schedule.time(1385, is24Hour = false, Locale.US))
    }

    @Test
    fun describesWindows() {
        assertEquals("Any time", Schedule.describe(null, true, Locale.US))
        assertEquals("08:00–23:00", Schedule.describe(day, true, Locale.US))
        assertEquals("22:00–06:30 (overnight)", Schedule.describe(night, true, Locale.US))
        assertEquals("15 h", Schedule.length(day))
        assertEquals("8 h 30 min", Schedule.length(night))
        assertEquals("30 min", Schedule.length(ActiveHours(600, 630)))
    }

    @Test
    fun withinSameDayWindow() {
        assertTrue(Schedule.isWithin(null, 0))
        assertTrue(Schedule.isWithin(day, 480))
        assertTrue(Schedule.isWithin(day, 1379))
        assertFalse(Schedule.isWithin(day, 1380))
        assertFalse(Schedule.isWithin(day, 479))
    }

    @Test
    fun withinOvernightWindow() {
        assertTrue(Schedule.isWithin(night, 22 * 60))
        assertTrue(Schedule.isWithin(night, 0))
        assertTrue(Schedule.isWithin(night, 6 * 60 + 29))
        assertFalse(Schedule.isWithin(night, 6 * 60 + 30))
        assertFalse(Schedule.isWithin(night, 12 * 60))
    }

    @Test
    fun validation() {
        assertNull(Schedule.error(null))
        assertNull(Schedule.error(day))
        assertNull(Schedule.error(night))
        assertTrue(Schedule.error(ActiveHours(600, 600))!!.contains("same time"))
        assertTrue(Schedule.error(ActiveHours(-1, 600))!!.isNotBlank())
        assertTrue(Schedule.error(ActiveHours(0, 1440))!!.isNotBlank())
        assertNull(Schedule.error(Schedule.DEFAULT))
    }
}
