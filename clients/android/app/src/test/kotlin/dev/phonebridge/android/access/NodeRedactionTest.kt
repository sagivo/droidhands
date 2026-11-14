package dev.phonebridge.android.access

import dev.phonebridge.core.util.OneTimeCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Hide one-time codes" for the screen tools (SEC-4 / OTP-1). */
class NodeRedactionTest {
    private val redact: (String) -> String = OneTimeCodes::redact
    private val sms = "G-482913 is your Google verification code"

    private fun inbox(): UiNode {
        val otp = UiNode(
            text = sms, className = "android.widget.TextView", packageName = "com.google.android.apps.messaging",
            bounds = rect(0, 400, 1080, 500), handle = "otp-node",
        )
        val row = UiNode(
            description = "Google, $sms", className = "android.view.ViewGroup", clickable = true,
            bounds = rect(0, 380, 1080, 520), children = listOf(otp), handle = "row-node",
        )
        val other = label("Lunch at 12:30 tomorrow?", bounds = rect(0, 600, 1080, 700))
        return group(row, other)
    }

    @Test
    fun `codes are masked in every node text, keeping structure and identity`() {
        val root = inbox()
        val masked = NodeRedaction.tree(root, redact)
        assertNotSame(root, masked)
        val all = masked.walk().toList()
        assertTrue(all.none { n -> listOfNotNull(n.text, n.description).any { it.contains("482913") } })
        val row = masked.children[0]
        assertEquals("Google, ${OneTimeCodes.MASK} is your Google verification code", row.description)
        assertEquals("${OneTimeCodes.MASK} is your Google verification code", row.children[0].text)
        // Same platform nodes (refs and actions still work), same flags and bounds, parents rebuilt.
        assertEquals("row-node", row.handle)
        assertTrue(row.clickable)
        assertEquals(rect(0, 380, 1080, 520), row.bounds)
        assertSame(row, row.children[0].parent)
        assertEquals("Lunch at 12:30 tomorrow?", masked.children[1].text)
    }

    @Test
    fun `trees and nodes without codes are returned as they are`() {
        val plain = group(label("Lunch at 12:30 tomorrow?"), button("Send"))
        assertSame(plain, NodeRedaction.tree(plain, redact))
        val node = label("Order #48291 shipped")
        assertSame(node, NodeRedaction.node(node, redact))
        assertEquals("${OneTimeCodes.MASK} is your Google verification code", NodeRedaction.node(label(sms), redact).text)
    }

    @Test
    fun `matching and output only see the masked text`() {
        val masked = NodeRedaction.tree(inbox(), redact)
        // screen_find / ui_wait / ui_tap(text) can't probe for the code.
        assertTrue(NodeQueries.find(listOf(masked), FindCriteria(text = "482913")).isEmpty())
        assertTrue(NodeQueries.find(listOf(masked), FindCriteria(text = "code is")).isEmpty())
        assertEquals(TextTarget.NotFound, NodeQueries.resolveTapText(listOf(masked), "G-482913"))
        assertTrue(NodeQueries.find(listOf(masked), FindCriteria(text = "verification code")).isNotEmpty())
        // screen_read output.
        val out = RefRegistry().newSnapshot { register ->
            TreeSerializer.serialize(listOf(appWindow(masked)), SerializeOptions(), register)
        }
        assertFalse(out.text, out.text.contains("482913"))
        assertTrue(out.text, out.text.contains(OneTimeCodes.MASK))
        assertFalse(NodeQueries.topTexts(listOf(masked)).any { it.contains("482913") })
    }

    @Test
    fun `a ref registered from a masked snapshot still matches the live node once masked the same way`() {
        val masked = NodeRedaction.tree(inbox(), redact).children[0].children[0]
        val refs = RefRegistry()
        val ref = refs.register(masked)
        // What ScreenSession.resolve compares: the live (unmasked) node, masked for the agent.
        val live = UiNode(text = sms, className = "android.widget.TextView", packageName = "com.google.android.apps.messaging", handle = "otp-node")
        assertTrue(refs.lookup(ref).fingerprint.matches(NodeFingerprint.of(NodeRedaction.node(live, redact))))
        assertFalse(refs.lookup(ref).fingerprint.matches(NodeFingerprint.of(live)))
    }

    @Test
    fun `visible text with a code is detected for screenshots`() {
        assertTrue(NodeRedaction.anyVisibleText(listOf(inbox()), OneTimeCodes::containsCode))
        assertFalse(NodeRedaction.anyVisibleText(listOf(group(label("Lunch at 12:30 tomorrow?"))), OneTimeCodes::containsCode))
        val hidden = UiNode(text = sms, visible = false)
        assertFalse(NodeRedaction.anyVisibleText(listOf(group(hidden)), OneTimeCodes::containsCode))
    }
}
