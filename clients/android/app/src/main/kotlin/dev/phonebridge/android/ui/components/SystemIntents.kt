package dev.phonebridge.android.ui.components

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.notify.BridgeNotificationListener

/** Intents for the system settings screens the Setup checklist sends the user to. */
object SystemIntents {
    // Undocumented but widely honored by Settings: scrolls to / highlights our entry.
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"

    private fun packageUri(context: Context): Uri = Uri.fromParts("package", context.packageName, null)

    /** App info, where "Allow restricted settings" and denied permissions live. */
    fun appDetails(context: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    fun appNotificationSettings(context: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun accessibilitySettings(context: Context): Intent {
        val component = ComponentName(context, BridgeAccessibilityService::class.java).flattenToString()
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, component)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, component) })
    }

    /** Detail page for our listener (API 30+); pair with [notificationListenerList] as fallback. */
    fun notificationListenerDetail(context: Context) = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
        .putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, BridgeNotificationListener::class.java).flattenToString(),
        )

    fun notificationListenerList() = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    /**
     * Direct "Allow / Deny" system dialog (needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, declared in the
     * manifest). Lint's Play-policy warning doesn't apply: a sideloaded bridge that must stay reachable
     * is the documented acceptable use.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    fun batteryOptimizationList() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** On Android 11+ this opens the app list; the package URI is only a hint. */
    fun overlaySettings(context: Context) =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))

    fun dndAccessSettings() = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    /** An https:// page in the user's browser (e.g. the latest release's APK download). */
    fun webPage(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)

    /** [packageName]'s Play Store page: the store app, else the web page. Pass both to [launch]. */
    fun playStore(packageName: String): Array<Intent> = arrayOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")),
        webPage("https://play.google.com/store/apps/details?id=$packageName"),
    )

    /** Opens another installed app ([packageName]'s launcher activity), or null if it has none. */
    fun openApp(context: Context, packageName: String): Intent? = try {
        context.packageManager.getLaunchIntentForPackage(packageName)
    } catch (_: RuntimeException) {
        null
    }

    /**
     * Starts the first of [intents] that resolves. Returns false if none could be started, so the
     * caller can tell the user where to go by hand.
     */
    fun launch(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            if (context.findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                // Try the next fallback.
            } catch (_: SecurityException) {
                // Some OEM settings activities aren't exported; try the next fallback.
            }
        }
        return false
    }
}

/** The hosting [Activity] of a (possibly wrapped) context, or null. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
