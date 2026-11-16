package com.droidhands.android.ui.setup

import android.content.pm.PackageInstaller
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictedSettingsTest {
    private val browserDownload = InstallSource(
        packageSource = RestrictedSettings.SOURCE_DOWNLOADED_FILE,
        installer = "com.google.android.packageinstaller",
        initiator = "com.android.chrome",
    )
    private val fileManager = InstallSource(RestrictedSettings.SOURCE_LOCAL_FILE, "com.google.android.packageinstaller", "com.google.android.documentsui")
    private val playStore = InstallSource(RestrictedSettings.SOURCE_STORE, "com.android.vending", "com.android.vending")
    private val adb = InstallSource(RestrictedSettings.SOURCE_UNSPECIFIED, null, RestrictedSettings.SHELL)

    @Test
    fun apkFilesAreRestrictedFromAndroid13() {
        assertTrue(RestrictedSettings.likelyRestricted(33, browserDownload))
        assertTrue(RestrictedSettings.likelyRestricted(35, fileManager))
        assertFalse("Android 12 has no restricted settings", RestrictedSettings.likelyRestricted(32, browserDownload))
        assertFalse(RestrictedSettings.likelyRestricted(30, fileManager))
    }

    @Test
    fun storeAndAdbInstallsAreNot() {
        assertFalse(RestrictedSettings.likelyRestricted(35, playStore))
        assertFalse("developer setup over USB", RestrictedSettings.likelyRestricted(35, adb))
        assertFalse(RestrictedSettings.likelyRestricted(35, InstallSource(null, RestrictedSettings.SHELL, null)))
        // A store that reports itself as one wins, whoever started the install.
        assertFalse(RestrictedSettings.likelyRestricted(35, InstallSource(RestrictedSettings.SOURCE_STORE, "org.fdroid.fdroid", null)))
    }

    @Test
    fun unknownInstallsGetTheGuide() {
        assertTrue("nothing known", RestrictedSettings.likelyRestricted(34, InstallSource()))
        assertTrue("no installer recorded", RestrictedSettings.likelyRestricted(34, InstallSource(RestrictedSettings.SOURCE_OTHER, null, "com.example.files")))
        assertTrue(
            "Android's package installer without a source",
            RestrictedSettings.likelyRestricted(34, InstallSource(RestrictedSettings.SOURCE_UNSPECIFIED, "com.android.packageinstaller", null)),
        )
        assertFalse(
            "another installer app that doesn't say it's a file install",
            RestrictedSettings.likelyRestricted(34, InstallSource(RestrictedSettings.SOURCE_OTHER, "com.example.installer", null)),
        )
    }

    @Test
    fun guideShowsOnlyAboveAccessibilityWhileItIsOff() {
        assertTrue(RestrictedSettings.showGuide(SetupLogic.ACCESSIBILITY, SetupStatus.MISSING, 34, browserDownload))
        assertFalse("already turned on", RestrictedSettings.showGuide(SetupLogic.ACCESSIBILITY, SetupStatus.PENDING, 34, browserDownload))
        assertFalse(RestrictedSettings.showGuide(SetupLogic.ACCESSIBILITY, SetupStatus.DONE, 34, browserDownload))
        assertFalse("other rows keep the short hint", RestrictedSettings.showGuide(SetupLogic.NOTIFICATION_ACCESS, SetupStatus.MISSING, 34, browserDownload))
        assertFalse(RestrictedSettings.showGuide(SetupLogic.ACCESSIBILITY, SetupStatus.MISSING, 34, playStore))
        assertFalse(RestrictedSettings.showGuide(SetupLogic.ACCESSIBILITY, SetupStatus.MISSING, 32, browserDownload))
    }

    private fun item(id: String, status: SetupStatus, blocked: Boolean = false) =
        SetupItem(id, id.uppercase(), "d", "u", essential = true, status = status, blocked = blocked)

    private val freshItems = listOf(
        item(RestrictedSettings.SMS_ID, SetupStatus.MISSING),
        item(SetupLogic.ACCESSIBILITY, SetupStatus.MISSING),
        item(SetupLogic.NOTIFICATION_ACCESS, SetupStatus.MISSING),
    )

    private fun with(id: String, status: SetupStatus) = freshItems.map { if (it.id == id) it.copy(status = status) else it }

    @Test
    fun aRestrictedInstallGetsTheGuideFirstUntilSomethingRestrictedIsGranted() {
        val android15 = RestrictedSettings.advice(restricted = true, sdkInt = 35, items = freshItems)
        assertTrue(android15.guideFirst)
        assertEquals("SMS, notification access and accessibility", android15.unlocks)
        val android13 = RestrictedSettings.advice(restricted = true, sdkInt = 33, items = freshItems)
        assertTrue(android13.guideFirst)
        assertEquals("notification access and accessibility", android13.unlocks)
        assertFalse(RestrictedSettings.advice(restricted = false, sdkInt = 35, items = freshItems).guideFirst)

        // Only an app with restricted settings allowed can be granted these.
        assertFalse(RestrictedSettings.advice(true, 35, with(SetupLogic.ACCESSIBILITY, SetupStatus.PENDING)).guideFirst)
        assertFalse(RestrictedSettings.advice(true, 35, with(SetupLogic.ACCESSIBILITY, SetupStatus.DONE)).guideFirst)
        assertFalse(RestrictedSettings.advice(true, 35, with(SetupLogic.NOTIFICATION_ACCESS, SetupStatus.DONE)).guideFirst)
        assertFalse(RestrictedSettings.advice(true, 35, with(RestrictedSettings.SMS_ID, SetupStatus.PARTIAL)).guideFirst)
        // Before Android 15, SMS isn't restricted, so granting it proves nothing.
        assertTrue(RestrictedSettings.advice(true, 34, with(RestrictedSettings.SMS_ID, SetupStatus.DONE)).guideFirst)
        assertFalse(RestrictedSettings.likelyAllowed(freshItems, 35))
    }

    @Test
    fun aRefusedSmsPermissionOnARestrictedInstallPointsToTheGuide() {
        val sms = item(RestrictedSettings.SMS_ID, SetupStatus.MISSING, blocked = true)
        val contacts = item("contacts", SetupStatus.MISSING, blocked = true)
        val advice = RestrictedSettings.advice(true, 35, freshItems)
        assertEquals(
            "Android blocks SMS for apps installed from the web. Do the \"Allow restricted settings\" steps above first, " +
                "then allow SMS in App info > Permissions.",
            RestrictedSettings.blockedNotice(listOf(sms), advice),
        )
        assertTrue(RestrictedSettings.blockedNotice(listOf(contacts, sms), advice).startsWith("Android won't ask again for: CONTACTS. "))
        assertEquals(
            "Android won't ask again for: SMS. Allow them in App info > Permissions.",
            RestrictedSettings.blockedNotice(listOf(sms), RestrictedSettings.Advice()),
        )
    }

    @Test
    fun guideStepsStartWithTheAttemptAndTeachTheMenu() {
        val steps = RestrictedSettings.GUIDE_STEPS
        assertEquals(3, steps.size)
        // Android only offers "Allow restricted settings" after one attempt to turn the setting on.
        assertTrue(steps[0].contains("try to turn DroidHands on"))
        assertTrue(steps[1].contains("Open App info") && steps[1].contains("three-dot menu") && steps[1].contains("Allow restricted settings"))
        assertTrue(steps[2].contains("turn on accessibility"))
    }

    @Test
    fun matchesTheAndroidConstants() {
        assertEquals(PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED, RestrictedSettings.SOURCE_UNSPECIFIED)
        assertEquals(PackageInstaller.PACKAGE_SOURCE_OTHER, RestrictedSettings.SOURCE_OTHER)
        assertEquals(PackageInstaller.PACKAGE_SOURCE_STORE, RestrictedSettings.SOURCE_STORE)
        assertEquals(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE, RestrictedSettings.SOURCE_LOCAL_FILE)
        assertEquals(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE, RestrictedSettings.SOURCE_DOWNLOADED_FILE)
        assertEquals(SetupLogic.PACKAGE_SOURCE_STORE, RestrictedSettings.SOURCE_STORE)
        assertEquals(Build.VERSION_CODES.TIRAMISU, RestrictedSettings.SDK)
    }
}
