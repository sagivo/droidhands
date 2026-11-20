package com.droidhands.android.graph

import com.droidhands.android.settings.BridgeSettings
import com.droidhands.android.ui.deeplink.ConfigureLinkResult
import com.droidhands.android.ui.deeplink.ConfigureLinks
import com.droidhands.android.ui.home.StatusPresentation
import com.droidhands.android.ui.settings.SettingsForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRelayTest {
    private val default = "https://relay.droidhands.example/"
    private val fresh = BridgeSettings()

    @Test
    fun usableDefaultUrls() {
        assertEquals("https://relay.droidhands.example", DefaultRelay.url(default))
        assertEquals("https://example.com/relay", DefaultRelay.url(" https://example.com/relay "))
        // The emulator's host and private networks may use http:// for testing.
        assertEquals("http://10.0.2.2:8787", DefaultRelay.url("http://10.0.2.2:8787"))
        assertNull("no default relay in this build", DefaultRelay.url(""))
        assertNull("plain http over the internet is never a default", DefaultRelay.url("http://relay.example.com"))
        assertNull(DefaultRelay.url("relay.example.com"))
        assertNull(DefaultRelay.url("https://relay.example.com/mcp"))
        assertNull(DefaultRelay.url("wss://relay.example.com"))
    }

    @Test
    fun freshInstallGoesOnlineThroughTheDefaultRelay() {
        val applied = DefaultRelay.applyOnFirstRun(fresh, default)
        assertTrue(applied.relayEnabled)
        assertEquals("https://relay.droidhands.example", applied.relayUrl)
        assertEquals("", applied.relayAccessKey)
        assertTrue(applied.relayFromDefault)
        assertTrue(applied.defaultRelayDecided)
        assertTrue(DefaultRelay.inUse(applied))
        // Everything else is untouched.
        assertEquals(fresh.copy(relayEnabled = true, relayUrl = applied.relayUrl, relayFromDefault = true, defaultRelayDecided = true), applied)
    }

    @Test
    fun decidedOnlyOnce() {
        val decided = fresh.copy(defaultRelayDecided = true)
        assertSame(decided, DefaultRelay.applyOnFirstRun(decided, default))
        // The user turned the default relay off later: it stays off.
        val turnedOff = DefaultRelay.applyOnFirstRun(fresh, default).copy(relayEnabled = false)
        assertSame(turnedOff, DefaultRelay.applyOnFirstRun(turnedOff, default))
    }

    @Test
    fun anExplicitChoiceBeforeTheFirstRunCheckSticks() {
        // A setup link on first launch (the first-run check waits for it), later cleared in Settings.
        val link = ConfigureLinks.parse("droidhands://configure?relay=https://mine.example.com") as ConfigureLinkResult.Valid
        val linked = ConfigureLinks.applyTo(fresh, link.request)
        val cleared = SettingsForm.from(linked).copy(relayEnabled = false, relayUrl = "").applyTo(linked)
        assertFalse(DefaultRelay.somethingConfigured(cleared))
        assertSame(cleared, DefaultRelay.applyOnFirstRun(cleared, default))
    }

    @Test
    fun aDefaultRelayThatNeedsAnAccountWaitsForTheSignIn() {
        // Signed out: nothing changes and nothing is decided; the Connect step offers "Continue with Google".
        assertSame(fresh, DefaultRelay.applyOnFirstRun(fresh, default, signInNeeded = true))
        assertTrue(DefaultRelay.canOffer(fresh, default))
        // Signed in: the usual first run.
        assertTrue(DefaultRelay.inUse(DefaultRelay.applyOnFirstRun(fresh, default, signInNeeded = false)))
    }

    @Test
    fun neverReplacesWhatTheUserSetUp() {
        val own = fresh.copy(relayEnabled = true, relayUrl = "https://mine.example.com", relayAccessKey = "k")
        assertEquals(own.copy(defaultRelayDecided = true), DefaultRelay.applyOnFirstRun(own, default))
        // A relay URL that is switched off still counts as the user's choice.
        val ownOff = fresh.copy(relayUrl = "https://mine.example.com")
        assertEquals(ownOff.copy(defaultRelayDecided = true), DefaultRelay.applyOnFirstRun(ownOff, default))
        // Local only: no relay is added behind the user's back.
        val local = fresh.copy(localServerEnabled = true)
        assertEquals(local.copy(defaultRelayDecided = true), DefaultRelay.applyOnFirstRun(local, default))
    }

    @Test
    fun buildWithoutDefaultKeepsTodaysBehavior() {
        assertEquals(fresh.copy(defaultRelayDecided = true), DefaultRelay.applyOnFirstRun(fresh, ""))
        assertEquals(fresh.copy(defaultRelayDecided = true), DefaultRelay.applyOnFirstRun(fresh, "http://public.example.com"))
        assertFalse(DefaultRelay.canOffer(fresh, ""))
    }

    @Test
    fun offeredOnlyWhenNoRelayOfTheUsersIsSetUp() {
        assertTrue(DefaultRelay.canOffer(fresh, default))
        val inUse = DefaultRelay.use(fresh, default)
        assertFalse("already in use", DefaultRelay.canOffer(inUse, default))
        assertTrue("switched off: offer it again", DefaultRelay.canOffer(inUse.copy(relayEnabled = false), default))
        assertFalse(DefaultRelay.canOffer(fresh.copy(relayUrl = "https://mine.example.com"), default))
        assertTrue("local server on, no relay", DefaultRelay.canOffer(fresh.copy(localServerEnabled = true), default))
    }

    @Test
    fun useDropsTheOldAccessKey() {
        val used = DefaultRelay.use(fresh.copy(relayUrl = "https://old.example.com", relayAccessKey = "old-key"), default)
        assertEquals("https://relay.droidhands.example", used.relayUrl)
        assertEquals("", used.relayAccessKey)
        assertTrue(DefaultRelay.inUse(used))
    }

    @Test(expected = IllegalArgumentException::class)
    fun useNeedsAUsableDefault() {
        DefaultRelay.use(fresh, "")
    }

    @Test
    fun defaultRelayAloneStillShowsTheOnboardingWizard() {
        val online = DefaultRelay.applyOnFirstRun(fresh, default)
        assertTrue(StatusPresentation.transportConfigured(online))
        assertFalse("the user didn't set it up", StatusPresentation.userTransportConfigured(online))
        assertTrue(StatusPresentation.userTransportConfigured(online.copy(relayFromDefault = false)))
        assertTrue(StatusPresentation.userTransportConfigured(online.copy(localServerEnabled = true)))
        assertFalse(StatusPresentation.userTransportConfigured(fresh))
    }
}
