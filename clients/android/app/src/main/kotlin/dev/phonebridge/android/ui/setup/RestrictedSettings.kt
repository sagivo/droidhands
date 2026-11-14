package dev.phonebridge.android.ui.setup

/** How this app was installed, from `PackageManager.getInstallSourceInfo` (null fields are unknown). */
data class InstallSource(
    /** `InstallSourceInfo.packageSource` (Android 13+). */
    val packageSource: Int? = null,
    /** `InstallSourceInfo.installingPackageName`, e.g. the system package installer or an app store. */
    val installer: String? = null,
    /** `InstallSourceInfo.initiatingPackageName`, e.g. the browser that opened the APK, or the adb shell. */
    val initiator: String? = null,
)

/**
 * Android 13+ "restricted settings" (pure, unit-tested). An app installed from an APK file (a
 * browser download or a file manager) can't turn on accessibility or notification access: the
 * switch is greyed out ("Restricted setting") until the user opens App info > ⋮ > Allow restricted
 * settings. Android only adds that menu item after the user tried to turn the setting on once, so
 * the guide starts with that attempt.
 */
object RestrictedSettings {
    /** First Android version with restricted settings (Android 13). */
    const val SDK = 33

    // InstallSourceInfo.PACKAGE_SOURCE_* (API 33).
    const val SOURCE_UNSPECIFIED = 0
    const val SOURCE_OTHER = 1
    const val SOURCE_STORE = 2
    const val SOURCE_LOCAL_FILE = 3
    const val SOURCE_DOWNLOADED_FILE = 4

    /** `adb install` (the developer setup over USB, scripts/setup-phone.sh): never restricted. */
    const val SHELL = "com.android.shell"

    /** Android's own package installers, which install the APK files people open from a browser or file manager. */
    val SYSTEM_INSTALLERS: Set<String> = setOf("com.google.android.packageinstaller", "com.android.packageinstaller")

    /** The guided card's steps, in order (shown numbered). */
    val GUIDE_STEPS: List<String> = listOf(
        "Tap Open Accessibility below and try to turn PhoneBridge on. Android says it's a restricted setting: tap OK.",
        "Tap Open App info, then the three-dot menu (top right), choose Allow restricted settings and confirm.",
        "Come back here and turn on accessibility.",
    )

    /**
     * True when Android likely restricts accessibility for this install: Android 13+ and the APK
     * was installed from a downloaded or local file, by Android's package installer, or by an
     * unknown installer. App store and adb installs are never restricted.
     */
    fun likelyRestricted(sdkInt: Int, source: InstallSource): Boolean = when {
        sdkInt < SDK -> false
        source.packageSource == SOURCE_STORE -> false
        source.installer == SHELL || source.initiator == SHELL -> false
        source.packageSource == SOURCE_LOCAL_FILE || source.packageSource == SOURCE_DOWNLOADED_FILE -> true
        source.installer in SYSTEM_INSTALLERS -> true
        else -> source.installer.isNullOrBlank()
    }

    /**
     * Show the guided "Allow restricted settings" card above the accessibility row: it is the
     * accessibility row, accessibility isn't turned on yet, and the install is likely restricted.
     * Once accessibility is on (even if Android hasn't connected it yet) the card goes away.
     */
    fun showGuide(id: String, status: SetupStatus, sdkInt: Int, source: InstallSource): Boolean =
        id == SetupLogic.ACCESSIBILITY && status == SetupStatus.MISSING && likelyRestricted(sdkInt, source)
}
