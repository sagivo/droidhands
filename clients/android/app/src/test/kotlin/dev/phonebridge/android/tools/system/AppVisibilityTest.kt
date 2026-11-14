package dev.phonebridge.android.tools.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVisibilityTest {
    private fun app(pkg: String, label: String) = AppEntry(pkg, label, "1.0", system = false)

    private val wallet = app("com.google.android.apps.walletnfcrel", "Wallet")
    private val walletTracker = app("com.example.wallettracker", "Wallet Tracker")
    private val bankA = app("com.bank.a", "Bank Alpha")
    private val bankB = app("com.bank.b", "Bank Beta")
    private val bankingTips = app("com.example.tips", "Banking Tips")
    private val chrome = app("com.android.chrome", "Chrome")
    private val apps = listOf(wallet, walletTracker, bankA, bankB, bankingTips, chrome)

    private val blocked = setOf(wallet.packageName, bankA.packageName, bankB.packageName)
    private val isAllowed: (String) -> Boolean = { it !in blocked }

    @Test
    fun `visible keeps order and drops blocked apps`() {
        assertEquals(listOf(walletTracker, bankingTips, chrome), AppVisibility.visible(apps, isAllowed))
        assertEquals(apps, AppVisibility.visible(apps) { true })
    }

    @Test
    fun `allowed unique match is found`() {
        assertEquals(AppPick.Found(chrome), AppVisibility.pick("chrome", apps, isAllowed))
    }

    @Test
    fun `a blocked best match is refused by name rather than swapped for a lookalike`() {
        // "Wallet" is an exact label match for the blocked app; "Wallet Tracker" only a prefix match.
        assertEquals(AppPick.Blocked(wallet), AppVisibility.pick("wallet", apps, isAllowed))
    }

    @Test
    fun `a tie of blocked apps is refused`() {
        val onlyBanks = listOf(bankA, bankB)
        assertEquals(AppPick.Blocked(bankA), AppVisibility.pick("bank", onlyBanks, isAllowed))
    }

    @Test
    fun `blocked apps tied with one allowed app stay ambiguous and are not named`() {
        val pick = AppVisibility.pick("bank", apps, isAllowed)
        assertEquals(AppPick.Ambiguous(listOf(bankingTips), someBlocked = true), pick)
    }

    @Test
    fun `ambiguity among allowed apps only is reported without the blocked flag`() {
        val pick = AppVisibility.pick("bank", apps) { true } as AppPick.Ambiguous
        assertEquals(listOf(bankA, bankB, bankingTips), pick.candidates)
        assertFalse(pick.someBlocked)
    }

    @Test
    fun `suggestions never include blocked apps`() {
        val pick = AppVisibility.pick("zzzzzz", apps, isAllowed) as AppPick.NotFound
        assertTrue(pick.suggestions.none { it.packageName in blocked })
    }
}
