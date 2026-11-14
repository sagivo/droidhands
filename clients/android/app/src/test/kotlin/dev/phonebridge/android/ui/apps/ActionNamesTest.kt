package dev.phonebridge.android.ui.apps

import dev.phonebridge.android.ui.activity.ActivityFilters
import dev.phonebridge.android.ui.activity.ActivityQuery
import dev.phonebridge.android.ui.activity.AuditPresentation
import dev.phonebridge.android.ui.apps.AppTestData.action
import dev.phonebridge.android.ui.apps.AppTestData.listing
import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.plugins.Integration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionNamesTest {
    private val index = ActionNames.Index(
        listOf(listing("com.spotify.music", "Spotify", Integration.PLUGIN, action("com.spotify.music", "play", "Play music"))),
    )

    @Test
    fun parsesOnlyAppActionNames() {
        assertEquals("com.spotify.music" to "play", ActionNames.parse("com.spotify.music/play"))
        assertNull(ActionNames.parse("whatsapp_send"))
        assertNull(ActionNames.parse("tools/call"))
        assertNull(ActionNames.parse("com.spotify.music/Play"))
        assertNull(ActionNames.parse("com.spotify.music/play/x"))
        assertNull(ActionNames.parse("/play"))
        assertNull(ActionNames.parse("com.spotify.music/"))
    }

    @Test
    fun appActionsReadAsAppAndTitle() {
        val play = ActionNames.display("com.spotify.music/play", index)
        assertEquals(ToolName("Spotify – Play music", "com.spotify.music/play", "com.spotify.music"), play)
        assertTrue(play.isAppAction)
        // An action the index doesn't know (yet, or any more): its id made readable.
        assertEquals("Spotify – Play from search", ActionNames.display("com.spotify.music/play_from_search", index).title)
        // An app that is gone: its package.
        assertEquals("com.gone.app – Open", ActionNames.display("com.gone.app/open").title)
        // Built-in tools and methods stay as they are.
        val builtIn = ActionNames.display("whatsapp_send", index)
        assertEquals(ToolName("whatsapp_send", "whatsapp_send"), builtIn)
        assertFalse(builtIn.isAppAction)
    }

    @Test
    fun activityRowsAndSearchUseTheShownName() {
        val play = AuditEntry(timestamp = 1, tokenId = "t1", tokenName = "Claude", transport = "relay", method = "tools/call", tool = "com.spotify.music/play", outcome = AuditOutcome.OK)
        val send = AuditEntry(timestamp = 2, tokenId = "t1", tokenName = "Claude", transport = "relay", method = "tools/call", tool = "whatsapp_send", outcome = AuditOutcome.OK)
        assertEquals("Spotify – Play music", AuditPresentation.displayTitle(play, index).title)
        assertEquals("whatsapp_send", AuditPresentation.displayTitle(send, index).title)
        val entries = listOf(send, play)
        assertEquals(listOf(play), ActivityFilters.apply(entries, ActivityQuery(search = "play music"), index))
        assertEquals(listOf(play), ActivityFilters.apply(entries, ActivityQuery(search = "spotify"), index))
        // Without names the raw tool name still matches.
        assertEquals(listOf(play), ActivityFilters.apply(entries, ActivityQuery(search = "spotify")))
        assertEquals(emptyList<AuditEntry>(), ActivityFilters.apply(entries, ActivityQuery(search = "play music")))
    }
}
