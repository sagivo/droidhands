package com.droidhands.android.ui.settings

import com.droidhands.android.settings.BridgeSettings
import com.droidhands.android.settings.LocalBind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsFormTest {
    private val base = BridgeSettings()

    @Test
    fun defaultsAreValidAndUnchanged() {
        val form = SettingsForm.from(base)
        assertTrue(form.validate().isValid)
        assertFalse(form.differsFrom(base))
        assertEquals(base, form.applyTo(base))
    }

    @Test
    fun relayEnabledRequiresUrl() {
        val v = SettingsForm.from(base).copy(relayEnabled = true).validate()
        assertFalse(v.isValid)
        assertNotNull(v.relayUrlError)
    }

    @Test
    fun invalidUrlBlocksSaveEvenWhenRelayOff() {
        val v = SettingsForm.from(base).copy(relayUrl = "relay.example.com").validate()
        assertFalse(v.isValid)
    }

    @Test
    fun insecureUrlWarnsButIsValid() {
        val v = SettingsForm.from(base).copy(relayEnabled = true, relayUrl = "http://relay.example.com").validate()
        assertTrue(v.isValid)
        assertEquals(RelayUrls.INSECURE_WARNING, v.relayUrlWarning)
    }

    @Test
    fun portValidation() {
        val form = SettingsForm.from(base)
        assertNotNull(form.copy(localServerPort = "").validate().portError)
        assertNotNull(form.copy(localServerPort = "80").validate().portError)
        assertNotNull(form.copy(localServerPort = "70000").validate().portError)
        assertNotNull(form.copy(localServerPort = "12a").validate().portError)
        assertNull(form.copy(localServerPort = " 9000 ").validate().portError)
        assertNull(form.copy(localServerPort = "1024").validate().portError)
        assertNull(form.copy(localServerPort = "65535").validate().portError)
    }

    @Test
    fun applyNormalizesAndKeepsForeignFields() {
        val current = base.copy(accessEnabled = false)
        val form = SettingsForm.from(current).copy(
            relayEnabled = true,
            relayUrl = " https://relay.example.com/ ",
            relayAccessKey = " key\n",
            localServerEnabled = true,
            localServerPort = "9000",
            localServerBind = LocalBind.ALL_INTERFACES,
            startOnBoot = false,
        )
        val applied = form.applyTo(current)
        assertEquals("https://relay.example.com", applied.relayUrl)
        assertEquals("key", applied.relayAccessKey)
        assertEquals(9000, applied.localServerPort)
        assertEquals(LocalBind.ALL_INTERFACES, applied.localServerBind)
        assertFalse(applied.startOnBoot)
        assertFalse("access switch is not owned by the form", applied.accessEnabled)
        assertTrue(form.differsFrom(current))
    }

    @Test
    fun tailscaleSwitchIsTheLocalServerOnTheTailnetOnly() {
        val form = SettingsForm.from(base.copy(localServerBind = LocalBind.ALL_INTERFACES))
        assertFalse(form.tailscaleOn)

        val on = form.withTailscale(true)
        assertTrue(on.tailscaleOn)
        assertTrue(on.localServerEnabled)
        assertEquals(LocalBind.TAILSCALE, on.localServerBind)

        val off = on.withTailscale(false)
        assertFalse(off.tailscaleOn)
        assertFalse("the server ran only for Tailscale", off.localServerEnabled)

        // Turning Tailscale "off" leaves a server listening elsewhere alone.
        val usb = form.copy(localServerEnabled = true, localServerBind = LocalBind.LOOPBACK)
        assertEquals(usb, usb.withTailscale(false))
    }

    @Test
    fun choosingTailscaleIsAConnectionDecision() {
        val current = base.copy(localServerEnabled = true)
        val applied = SettingsForm.from(current).withTailscale(true).applyTo(current)
        assertEquals(LocalBind.TAILSCALE, applied.localServerBind)
        assertTrue("the default relay must not switch itself on later", applied.defaultRelayDecided)
    }

    @Test
    fun editingTheRelayUrlMakesItTheUsersOwnRelay() {
        val onDefault = base.copy(relayEnabled = true, relayUrl = "https://default.example.com", relayFromDefault = true, defaultRelayDecided = true)
        val untouched = SettingsForm.from(onDefault)
        assertFalse("unchanged form is not dirty", untouched.differsFrom(onDefault))
        assertTrue("other edits keep the default relay", untouched.copy(startOnBoot = false).applyTo(onDefault).relayFromDefault)
        assertTrue("turning it off keeps it the default one", untouched.copy(relayEnabled = false).applyTo(onDefault).relayFromDefault)

        val own = untouched.copy(relayUrl = "https://mine.example.com").applyTo(onDefault)
        assertEquals("https://mine.example.com", own.relayUrl)
        assertFalse(own.relayFromDefault)
        assertTrue(own.defaultRelayDecided)
    }

    @Test
    fun changingHowThePhoneConnectsRecordsTheDefaultRelayDecision() {
        val undecided = base.copy(relayEnabled = true, relayUrl = "https://mine.example.com")
        val form = SettingsForm.from(undecided)
        assertFalse("an unchanged form still compares equal", form.differsFrom(undecided))
        assertFalse(form.copy(startOnBoot = !undecided.startOnBoot).applyTo(undecided).defaultRelayDecided)
        assertTrue(form.copy(relayEnabled = false).applyTo(undecided).defaultRelayDecided)
        assertTrue(form.copy(relayUrl = "").applyTo(undecided).defaultRelayDecided)
        assertTrue(form.copy(relayAccessKey = "k").applyTo(undecided).defaultRelayDecided)
        assertTrue(form.copy(localServerEnabled = true).applyTo(undecided).defaultRelayDecided)
    }

    @Test
    fun whitespaceOnlyEditsAreNotChanges() {
        val current = base.copy(relayUrl = "https://relay.example.com")
        val form = SettingsForm.from(current).copy(relayUrl = "https://relay.example.com/ ")
        assertFalse(form.differsFrom(current))
    }
}
