package dev.phonebridge.android.access

import dev.phonebridge.core.auth.AppAccessMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVisibilityTest {
    private val own = "dev.phonebridge.android"
    private val bank = "com.bank"
    private val chat = "com.whatsapp"
    private val launcher = "com.android.launcher3"
    private val systemUi = ForegroundTracker.SYSTEM_UI

    /** Blocklist {bank}; System UI, the launcher and PhoneBridge are system surfaces. */
    private val blocklist = AppVisibility(
        restricted = true,
        isAllowed = { it != bank && it != own },
        exempt = setOf(systemUi, launcher, own),
        explicitlyBlocked = setOf(bank),
    )

    /** Allowlist {chat}: everything else is blocked unless it is a system surface. */
    private val allowlist = AppVisibility(
        restricted = true,
        isAllowed = { it == chat },
        exempt = setOf(systemUi, launcher, own),
    )

    @Test
    fun `restriction ignores PhoneBridge's own never-allow entry`() {
        assertFalse(AppVisibility.isRestricted(AppAccessMode.ALL, emptySet(), setOf(own), own))
        assertTrue(AppVisibility.isRestricted(AppAccessMode.ALL, emptySet(), setOf(own, bank), own))
        assertTrue(AppVisibility.isRestricted(AppAccessMode.ALLOWLIST, setOf(chat), setOf(own), own))
        assertFalse(AppVisibility.isRestricted(AppAccessMode.BLOCKLIST, emptySet(), setOf(own), own))
        assertTrue(AppVisibility.isRestricted(AppAccessMode.BLOCKLIST, setOf(bank), setOf(own), own))
        assertEquals(setOf(bank, "com.pay"), AppVisibility.explicitlyBlocked(AppAccessMode.BLOCKLIST, setOf(bank), setOf(own, "com.pay"), own))
        assertEquals(setOf("com.pay"), AppVisibility.explicitlyBlocked(AppAccessMode.ALLOWLIST, setOf(chat), setOf(own, "com.pay"), own))
    }

    @Test
    fun `system surfaces stay visible under an allowlist unless blocked explicitly`() {
        assertTrue(allowlist.allows(chat))
        assertTrue(allowlist.allows(launcher))
        assertTrue(allowlist.allows(systemUi))
        assertTrue(allowlist.allows(own))
        assertTrue(allowlist.allows(null))
        assertFalse(allowlist.allows(bank))
        val strict = AppVisibility(true, { false }, exempt = setOf(launcher), explicitlyBlocked = setOf(launcher))
        assertFalse(strict.allows(launcher))
        assertTrue(AppVisibility.UNRESTRICTED.allows(bank))
    }

    @Test
    fun `windows of blocked apps are dropped and counted`() {
        val app = appWindow(group(label("Hi", pkg = chat), pkg = chat), pkg = chat)
        val bankWindow = appWindow(group(label("Balance 1000", pkg = bank), pkg = bank), pkg = bank, primary = false)
        val ownWindow = WindowSnapshot(null, WindowKind.APPLICATION, own, SCREEN, false, null, hidden = true)
        val filtered = blocklist.filter(listOf(app, bankWindow, ownWindow))
        assertEquals(listOf(app, ownWindow), filtered.windows)
        assertEquals(1, filtered.omitted)
        assertSame(app, filtered.windows.first())
        assertEquals(listOf(app, bankWindow), AppVisibility.UNRESTRICTED.filter(listOf(app, bankWindow)).windows)
    }

    @Test
    fun `notifications of blocked apps are pruned from the shade`() {
        val bankRow = group(label("Bank", pkg = bank), label("You received 500", pkg = bank), pkg = bank)
        val chatRow = group(label("Tania: hi", pkg = chat), pkg = chat)
        val clearAll = button("Clear all", pkg = systemUi)
        val shade = WindowSnapshot("Notification shade", WindowKind.SYSTEM, systemUi, SCREEN, false, group(bankRow, chatRow, clearAll, pkg = systemUi))
        val filtered = blocklist.filter(listOf(shade))
        val root = filtered.windows.single().root!!
        val texts = root.walk().mapNotNull { it.text }.toList()
        assertEquals(listOf("Tania: hi", "Clear all"), texts)
        assertEquals(0, filtered.omitted)
        // The pruned copy is a consistent tree: kept children point at the new parent.
        assertSame(root, root.children.first().parent)
        assertEquals(bank, blocklist.firstBlocked(listOf(shade)))
        assertNull(blocklist.firstBlocked(filtered.windows))
    }

    @Test
    fun `prune returns the same tree when nothing is blocked`() {
        val tree = group(label("a", pkg = chat), group(label("b", pkg = chat), pkg = chat), pkg = chat)
        assertSame(tree, blocklist.prune(tree))
        assertNull(blocklist.prune(group(pkg = bank)))
    }

    @Test
    fun `blocked foreground is the primary app or the tracked app while visible`() {
        assertEquals(bank, blocklist.blockedForeground(bank, bank, listOf(bank)))
        // Shade pulled down over the banking app: System UI is primary, the bank is still on screen.
        assertEquals(bank, blocklist.blockedForeground(systemUi, bank, listOf(systemUi, bank)))
        // Stale tracking: the bank is no longer on screen.
        assertNull(blocklist.blockedForeground(launcher, bank, listOf(launcher, systemUi)))
        assertNull(allowlist.blockedForeground(launcher, launcher, listOf(launcher)))
        assertEquals("com.maps", allowlist.blockedForeground("com.maps", "com.maps", listOf("com.maps")))
        assertNull(AppVisibility.UNRESTRICTED.blockedForeground(bank, bank, listOf(bank)))
    }
}
