package com.droidhands.android.tools.system

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

// PackageManager calls without the int-flag overloads deprecated in API 33. Blocking IPC: call on
// Dispatchers.IO. Package visibility is covered by QUERY_ALL_PACKAGES in the manifest.

internal fun PackageManager.resolveActivityCompat(intent: Intent, flags: Int = 0): ResolveInfo? =
    if (Build.VERSION.SDK_INT >= 33) {
        resolveActivity(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        resolveActivity(intent, flags)
    }

internal fun PackageManager.queryIntentActivitiesCompat(intent: Intent, flags: Int = 0): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= 33) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, flags)
    }

internal fun PackageManager.hasServiceFor(intent: Intent): Boolean =
    if (Build.VERSION.SDK_INT >= 33) {
        queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0)).isNotEmpty()
    } else {
        @Suppress("DEPRECATION")
        queryIntentServices(intent, 0).isNotEmpty()
    }

/** Null when the package is not installed (or not visible). */
internal fun PackageManager.packageInfoOrNull(packageName: String): PackageInfo? = try {
    if (Build.VERSION.SDK_INT >= 33) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, 0)
    }
} catch (e: PackageManager.NameNotFoundException) {
    null
}
