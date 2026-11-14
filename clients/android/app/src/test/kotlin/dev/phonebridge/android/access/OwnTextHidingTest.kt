package dev.phonebridge.android.access

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnTextHidingTest {
    private fun node(text: String?) = UiNode(
        text = text, className = "android.widget.TextView", packageName = "com.android.systemui", bounds = Bounds(0, 0, 10, 10),
    )

    @Test
    fun `a heads-up row showing an own prompt is removed with its buttons`() {
        val title = node("E2E auto wants to: Send SMS")
        val allow = UiNode(text = "Allow", className = "android.widget.Button", packageName = "com.android.systemui", clickable = true)
        val row = UiNode(className = "android.widget.FrameLayout", packageName = "com.android.systemui", clickable = true, children = listOf(title, allow))
        val other = node("Battery 80%")
        val root = UiNode(className = "android.widget.FrameLayout", packageName = "com.android.systemui", children = listOf(row, other))
        val kept = SelfProtection.hideOwnContent(root, "dev.phonebridge.android", setOf("E2E auto wants to: Send SMS"))!!
        assertTrue(kept.children.none { it === row })
        assertTrue(kept.children.any { it === other })
    }

    @Test
    fun `exact title of an own notification is own text`() {
        val own = setOf("E2E auto wants to: Send SMS")
        assertTrue(SelfProtection.isOwnText(node("E2E auto wants to: Send SMS"), own))
        assertTrue(SelfProtection.isOwnText(node("  E2E auto wants to: Send SMS "), own))
    }

    @Test
    fun `other text and empty sets are not`() {
        val own = setOf("E2E auto wants to: Send SMS")
        assertFalse(SelfProtection.isOwnText(node("Send SMS"), own))
        assertFalse(SelfProtection.isOwnText(node(null), own))
        assertFalse(SelfProtection.isOwnText(node("E2E auto wants to: Send SMS"), emptySet()))
    }
}
