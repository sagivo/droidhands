package com.droidhands.android.access

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.SystemClock

/**
 * Packages that are part of the OS rather than apps a user picks for app access (see [AppVisibility]): System
 * UI, framework and permission dialogs, the home screen, the installed keyboards, and DroidHands itself.
 */
object SystemSurfaces {
    private const val CACHE_MS = 60_000L
    private const val SETTINGS = "com.android.settings"

    private val FIXED = setOf(
        ForegroundTracker.SYSTEM_UI,
        "android",
        "com.android.intentresolver",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
    )

    @Volatile
    private var homeCache: Pair<Long, Set<String>>? = null

    /** The current set. Blocking PackageManager IPC at most once a minute: call off the main thread. */
    fun packages(context: Context): Set<String> =
        FIXED + BridgeAccessibilityService.keyboardPackages + homePackages(context) + context.packageName

    /** The default home app; all home apps (minus Settings' fallback) when the user hasn't chosen one. */
    private fun homePackages(context: Context): Set<String> {
        val now = SystemClock.uptimeMillis()
        homeCache?.let { (at, value) -> if (now - at < CACHE_MS) return value }
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val value = try {
            val handlers = query(pm, home).mapNotNull { it.activityInfo?.packageName }.filter { it != SETTINGS && it != "android" }.toSet()
            val default = resolve(pm, home)?.activityInfo?.packageName
            if (default != null && default in handlers) setOf(default) else handlers
        } catch (e: RuntimeException) {
            emptySet()
        }
        homeCache = now to value
        return value
    }

    private fun resolve(pm: PackageManager, intent: Intent): ResolveInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }

    private fun query(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
}
