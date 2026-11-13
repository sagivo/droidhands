package dev.phonebridge.android.ui.settings

import dev.phonebridge.android.settings.BridgeSettings
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
            localServerBindAll = true,
            startOnBoot = false,
        )
        val applied = form.applyTo(current)
        assertEquals("https://relay.example.com", applied.relayUrl)
        assertEquals("key", applied.relayAccessKey)
        assertEquals(9000, applied.localServerPort)
        assertTrue(applied.localServerBindAll)
        assertFalse(applied.startOnBoot)
        assertFalse("access switch is not owned by the form", applied.accessEnabled)
        assertTrue(form.differsFrom(current))
    }

    @Test
    fun whitespaceOnlyEditsAreNotChanges() {
        val current = base.copy(relayUrl = "https://relay.example.com")
        val form = SettingsForm.from(current).copy(relayUrl = "https://relay.example.com/ ")
        assertFalse(form.differsFrom(current))
    }
}
