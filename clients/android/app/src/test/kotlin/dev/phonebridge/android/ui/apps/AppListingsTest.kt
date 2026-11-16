package dev.phonebridge.android.ui.apps

import dev.phonebridge.android.ui.apps.AppTestData.BANK
import dev.phonebridge.android.ui.apps.AppTestData.NOTES
import dev.phonebridge.android.ui.apps.AppTestData.SPOTIFY
import dev.phonebridge.android.ui.apps.AppTestData.WHATSAPP
import dev.phonebridge.core.plugins.Integration
import dev.phonebridge.core.plugins.PluginStatus
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListingsTest {
    @Test
    fun everyAppWithItsIntegrationAndActions() = runTest {
        val listings = AppListings.load(AppTestData.catalog()).associateBy { it.packageName }
        assertEquals(listOf(BANK, NOTES, SPOTIFY, WHATSAPP), listings.keys.toList())

        val spotify = listings.getValue(SPOTIFY)
        assertEquals(Integration.PLUGIN, spotify.integration)
        assertEquals(PluginStatus.EXPERIMENTAL, spotify.pluginStatus)
        assertEquals("spotify", spotify.pluginId)
        // Plugin actions first, then the discovered ones no plugin action replaces.
        assertEquals(listOf("play", "open"), spotify.actions.map { it.id })
        val play = spotify.actions.first()
        assertEquals("com.spotify.music/play", play.toolName)
        assertEquals(Risk.ACTION, play.risk)
        assertEquals(Scopes.APPS, play.scope)
        assertEquals(listOf(ActionParamInfo("query", "What to play", "string", true)), play.params)
        assertFalse(play.discovered)
        assertTrue(spotify.actions[1].discovered)

        assertEquals(Integration.BASIC, listings.getValue(BANK).integration)
        assertEquals(listOf("open"), listings.getValue(BANK).actions.map { it.id })
    }

    @Test
    fun toolRecipesTakeTheBuiltInToolsRiskAndScope() = runTest {
        val whatsapp = AppListings.load(AppTestData.catalog()).first { it.packageName == WHATSAPP }
        val send = whatsapp.actions.first { it.id == "send_message" }
        assertEquals("sms_send", send.builtInTool)
        assertEquals(Risk.SENSITIVE, send.risk)
        assertEquals(Scopes.MESSAGES_SEND, send.scope)
        assertEquals("com.whatsapp/send_message", send.toolName)
        // An action whose tool this phone lacks is left out, as it is for agents.
        assertEquals(listOf("send_message", "open"), whatsapp.actions.map { it.id })
    }

    @Test
    fun localPluginsAreMarkedUnreviewed() = runTest {
        val notes = AppListings.load(AppTestData.catalog()).first { it.packageName == NOTES }
        assertEquals(Integration.LOCAL, notes.integration)
        assertTrue(notes.actions.first { it.id == "new_note" }.unreviewed)
        assertFalse(notes.actions.first { it.id == "open" }.unreviewed)
    }

    @Test
    fun aFailingScanOnlyAffectsItsAppAndAvailabilityIsReported() = runTest {
        val platform = AppTestData.FakePlatform().apply {
            failing += BANK
            unavailable["$SPOTIFY/play"] = "Turn on the accessibility service."
        }
        val listings = AppListings.load(AppTestData.catalog(platform)).associateBy { it.packageName }
        // The plugin actions survive a failing discovery (AppActionCatalog), discovered ones are just missing.
        assertEquals(emptyList<String>(), listings.getValue(BANK).actions.map { it.id })
        assertEquals("Turn on the accessibility service.", listings.getValue(SPOTIFY).actions.first().unavailableReason)
        assertNull(listings.getValue(SPOTIFY).actions[1].unavailableReason)
        assertEquals(4, listings.size)
    }
}
