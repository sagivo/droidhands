package dev.phonebridge.android.ui.components

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapSafetyTest {
    @Test
    fun `protection stays on until the last holder lets go`() {
        val holders = HolderCounts<String>()
        assertTrue(holders.acquire("main")) // approval card: apply
        assertFalse(holders.acquire("main")) // token on screen too: already applied
        assertTrue(holders.acquire("dialog")) // other windows are independent
        assertFalse(holders.release("main")) // card answered: the token still needs it
        assertTrue(holders.release("main")) // last one: lift
        assertFalse(holders.release("main")) // unbalanced release is ignored
        assertTrue(holders.release("dialog"))
        assertTrue(holders.acquire("main")) // and it can be applied again
    }

    @Test
    fun `approval screens pause screen control only while resumed`() {
        assertEquals("approval_screen", ApprovalScreens.SOURCE)
        assertEquals(true, ApprovalScreens.activeAfter(Lifecycle.Event.ON_RESUME))
        assertEquals(false, ApprovalScreens.activeAfter(Lifecycle.Event.ON_PAUSE))
        assertEquals(false, ApprovalScreens.activeAfter(Lifecycle.Event.ON_DESTROY))
        listOf(Lifecycle.Event.ON_CREATE, Lifecycle.Event.ON_START, Lifecycle.Event.ON_STOP)
            .forEach { assertNull(it.name, ApprovalScreens.activeAfter(it)) }
    }
}
