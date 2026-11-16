package com.droidhands.android.tools.screen

import android.accessibilityservice.AccessibilityService
import com.droidhands.android.access.Bounds
import com.droidhands.android.access.SystemOverlay
import com.droidhands.android.access.UiAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenHelpersTest {
    private val list = Bounds(0, 200, 1080, 2200)

    @Test
    fun `directional scroll action is preferred`() {
        val actions = setOf(UiAction.SCROLL_FORWARD, UiAction.SCROLL_DOWN, UiAction.SCROLL_BACKWARD, UiAction.SCROLL_UP)
        assertEquals(ScrollPlan.Action(UiAction.SCROLL_DOWN), ScrollPlanner.plan(actions, "android.widget.ScrollView", ScrollDirection.DOWN, list))
        assertEquals(ScrollPlan.Action(UiAction.SCROLL_UP), ScrollPlanner.plan(actions, "android.widget.ScrollView", ScrollDirection.UP, list))
    }

    @Test
    fun `forward and backward are used only on the container's axis`() {
        val recycler = "androidx.recyclerview.widget.RecyclerView"
        val both = setOf(UiAction.SCROLL_FORWARD, UiAction.SCROLL_BACKWARD)
        assertEquals(ScrollPlan.Action(UiAction.SCROLL_FORWARD), ScrollPlanner.plan(both, recycler, ScrollDirection.DOWN, list))
        assertEquals(ScrollPlan.Action(UiAction.SCROLL_BACKWARD), ScrollPlanner.plan(both, recycler, ScrollDirection.UP, list))
        assertTrue(ScrollPlanner.plan(both, recycler, ScrollDirection.LEFT, list) is ScrollPlan.Swipe)
        val pager = "androidx.viewpager.widget.ViewPager"
        assertEquals(ScrollPlan.Action(UiAction.SCROLL_FORWARD), ScrollPlanner.plan(setOf(UiAction.SCROLL_FORWARD), pager, ScrollDirection.RIGHT, list))
    }

    @Test
    fun `end of content is reported instead of swiping`() {
        assertEquals(ScrollPlan.AtEnd, ScrollPlanner.plan(setOf(UiAction.SCROLL_BACKWARD), "androidx.recyclerview.widget.RecyclerView", ScrollDirection.DOWN, list))
        assertEquals(ScrollPlan.AtEnd, ScrollPlanner.plan(setOf(UiAction.SCROLL_UP, UiAction.SCROLL_BACKWARD), "android.widget.ScrollView", ScrollDirection.DOWN, list))
    }

    @Test
    fun `swipe moves the finger against the scroll direction inside the bounds`() {
        val down = ScrollPlanner.plan(emptySet(), "android.view.View", ScrollDirection.DOWN, list) as ScrollPlan.Swipe
        assertEquals(ScrollPlan.Swipe(540, 1700, 540, 700), down)
        assertTrue(down.y1 > down.y2)
        val right = ScrollPlanner.swipe(ScrollDirection.RIGHT, list)
        assertEquals(ScrollPlan.Swipe(810, 1200, 270, 1200), right)
        val up = ScrollPlanner.swipe(ScrollDirection.UP, list)
        assertTrue(up.y1 < up.y2)
        val left = ScrollPlanner.swipe(ScrollDirection.LEFT, list)
        assertTrue(left.x1 < left.x2)
        assertEquals(ScrollDirection.DOWN, ScrollDirection.parse(" Down "))
        assertNull(ScrollDirection.parse("sideways"))
    }

    @Test
    fun `screenshot scaling keeps aspect ratio and never upscales`() {
        assertEquals(576 to 1280, ScreenshotMath.scaledSize(1080, 2400, 1280))
        assertEquals(1280 to 576, ScreenshotMath.scaledSize(3200, 1440, 1280))
        assertEquals(720 to 1280, ScreenshotMath.scaledSize(720, 1280, 1280))
        assertEquals(500 to 300, ScreenshotMath.scaledSize(500, 300, 1280))
        assertEquals(29 to 64, ScreenshotMath.scaledSize(1080, 2400, 64))
    }

    @Test
    fun `black frame detection`() {
        val black = IntArray(100) { 0xFF000000.toInt() }
        assertTrue(ScreenshotMath.isMostlyBlack(black))
        val photo = IntArray(100) { if (it < 50) 0xFF000000.toInt() else 0xFF336699.toInt() }
        assertFalse(ScreenshotMath.isMostlyBlack(photo))
        assertFalse(ScreenshotMath.isMostlyBlack(IntArray(0)))
    }

    @Test
    fun `global action names`() {
        assertEquals("back" to AccessibilityService.GLOBAL_ACTION_BACK, GlobalActions.parse("Back"))
        assertEquals("quick_settings" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, GlobalActions.parse("quick-settings"))
        assertEquals("power_dialog" to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, GlobalActions.parse("power dialog"))
        assertNull(GlobalActions.parse("reboot"))
        assertEquals(listOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen", "power_dialog"), GlobalActions.NAMES)
    }

    @Test
    fun `app access messages tell the agent what happened and what to do`() {
        val escaped = UiAutomation.blockedAfterAction("Tapped n3 Button \"Open\".", "Bank", wentHome = true)
        assertTrue(escaped, escaped.startsWith("Tapped n3 Button \"Open\". This brought Bank to the foreground"))
        assertTrue(escaped.contains("went back to the home screen") && escaped.contains("Don't try to open Bank another way"))
        assertTrue(UiAutomation.blockedAfterAction("Swiped.", "Bank", wentHome = false).contains("tried to go back"))
        val shade = ScreenAccess.overlayRefusal(SystemOverlay.NOTIFICATION_SHADE)
        assertTrue(shade, shade.contains("the notification shade is showing") && shade.contains("notifications_list"))
        assertTrue(ScreenAccess.overlayRefusal(SystemOverlay.LOCK_SCREEN).contains("unlock the phone"))
        assertTrue(ScreenAccess.overlayRefusal(SystemOverlay.RECENTS).contains("ui_global_action"))
        assertTrue(UiAutomation.STAYED_LOCKED.startsWith("The phone stayed locked; the user didn't unlock within 60 s."))
    }
}
