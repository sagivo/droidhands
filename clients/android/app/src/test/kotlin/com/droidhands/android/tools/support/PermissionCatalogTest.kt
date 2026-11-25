package com.droidhands.android.tools.support

import android.Manifest
import com.droidhands.android.Edition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Google Play restricts SMS, call-log and background-location permissions; the store edition has none (docs/EDITIONS.md). */
class PermissionCatalogTest {
    private val restricted = setOf(
        Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
    )

    @Test
    fun storeSetupAsksForNoRestrictedPermission() {
        val store = PermissionCatalog.runtime(smsAndCallLog = false)
        assertTrue(store.flatMap { it.permissions }.none { it in restricted })
        assertFalse(store.any { it.id == "sms" })
        assertEquals("Phone", store.single { it.id == "phone" }.title)
        val full = PermissionCatalog.runtime(smsAndCallLog = true).flatMap { it.permissions }
        assertTrue(full.containsAll(listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CALL_LOG)))
        assertEquals(PermissionCatalog.runtime(Edition.SMS_AND_CALL_LOG), PermissionCatalog.RUNTIME)
    }

    /** The shared manifest goes to Play: the restricted permissions belong in src/full only. */
    @Test
    fun mainManifestDeclaresNoRestrictedPermission() {
        val main = File("src/main/AndroidManifest.xml").takeIf { it.isFile } ?: return
        val declared = Regex("uses-permission\\s+android:name=\"([^\"]+)\"").findAll(main.readText()).map { it.groupValues[1] }.toSet()
        assertEquals(emptySet<String>(), declared intersect restricted)
        val full = File("src/full/AndroidManifest.xml").readText()
        for (permission in restricted) assertTrue(permission, full.contains("\"$permission\""))
    }
}
