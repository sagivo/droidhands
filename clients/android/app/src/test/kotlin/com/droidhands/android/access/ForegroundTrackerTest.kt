package com.droidhands.android.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundTrackerTest {
    private val tracker = ForegroundTracker().apply {
        configure("com.droidhands.android", listOf("com.google.android.inputmethod.latin"))
    }

    @Test
    fun `ignores own package, system ui and keyboards`() {
        assertTrue(tracker.onWindowStateChanged("com.whatsapp", "com.whatsapp.HomeActivity", 1))
        assertFalse(tracker.onWindowStateChanged("com.droidhands.android", "com.droidhands.android.confirm.ConfirmationActivity", 2))
        assertFalse(tracker.onWindowStateChanged("com.android.systemui", "android.widget.FrameLayout", 3))
        assertFalse(tracker.onWindowStateChanged("com.google.android.inputmethod.latin", "android.inputmethodservice.SoftInputWindow", 4))
        assertFalse(tracker.onWindowStateChanged("com.other.keyboard", "android.inputmethodservice.SoftInputWindow", 5))
        assertFalse(tracker.onWindowStateChanged(null, "x", 6))
        assertEquals("com.whatsapp", tracker.packageName)
    }

    @Test
    fun `activity is the most recent class that resolves as an activity`() {
        val calls = mutableListOf<String>()
        val isActivity = { _: String, cls: String -> calls += cls; cls.endsWith("Activity") || cls == "com.whatsapp.Conversation" }
        tracker.onWindowStateChanged("com.whatsapp", "com.whatsapp.HomeActivity", 1)
        tracker.onWindowStateChanged("com.whatsapp", "com.whatsapp.Conversation", 2)
        tracker.onWindowStateChanged("com.whatsapp", "androidx.appcompat.app.AlertDialog", 3)
        assertEquals("com.whatsapp.Conversation", tracker.activity(isActivity))
        assertEquals("com.whatsapp.Conversation", tracker.activity(isActivity))
        assertEquals(1, calls.count { it == "androidx.appcompat.app.AlertDialog" }) // cached

        tracker.onWindowStateChanged("com.android.chrome", "android.widget.FrameLayout", 4)
        assertEquals("com.android.chrome", tracker.packageName)
        assertNull(tracker.activity(isActivity))
    }

    @Test
    fun `records state change times for every package and resets`() {
        tracker.onWindowStateChanged("com.whatsapp", "c", 10)
        tracker.onWindowStateChanged("com.android.systemui", "c", 20)
        assertEquals(10L, tracker.lastStateChangeAt("com.whatsapp"))
        assertEquals(20L, tracker.lastStateChangeAt("com.android.systemui"))
        tracker.reset()
        assertNull(tracker.packageName)
        assertNull(tracker.lastStateChangeAt("com.whatsapp"))
    }

    @Test
    fun `seed only fills an unknown package`() {
        tracker.seed("com.droidhands.android")
        assertNull(tracker.packageName)
        tracker.seed("com.whatsapp")
        assertEquals("com.whatsapp", tracker.packageName)
        tracker.seed("com.android.chrome")
        assertEquals("com.whatsapp", tracker.packageName)
    }
}
