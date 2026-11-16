package com.droidhands.android.tools.system

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * A launchable app, the launcher activity the home screen opens for it, and its install version
 * ([versionCode] and [lastUpdateTime] change with every update; 0 when unknown).
 */
internal class LaunchableApp(val entry: AppEntry, val launcher: ActivityInfo, val versionCode: Long, val lastUpdateTime: Long)

/** Launchable apps (those with a MAIN/LAUNCHER activity), as the home screen shows them. */
internal object InstalledApps {
    /** One entry per package, sorted by label. Blocking PackageManager IPC runs on Dispatchers.IO. */
    suspend fun launchable(context: Context): List<AppEntry> = launchableWithActivities(context).map { it.entry }

    /**
     * [launchable] with each app's (first) launcher activity. [flags] are PackageManager query flags, e.g.
     * GET_META_DATA to read the activities' meta-data. Blocking PackageManager IPC runs on Dispatchers.IO.
     */
    suspend fun launchableWithActivities(context: Context, flags: Int = 0): List<LaunchableApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivitiesCompat(launcher, flags).mapNotNull { it.activityInfo }.distinctBy { it.packageName }
        val collator = Collator.getInstance()
        activities.map { activity ->
            val app = activity.applicationInfo
            val label = runCatching { app.loadLabel(pm).toString().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: runCatching { activity.loadLabel(pm).toString().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: activity.packageName
            val system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val updated = app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            val info = runCatching { pm.packageInfoOrNull(activity.packageName) }.getOrNull()
            val entry = AppEntry(
                packageName = activity.packageName,
                label = label,
                versionName = info?.versionName,
                system = system,
                preinstalledOnly = system && !updated,
            )
            LaunchableApp(entry, activity, info?.longVersionCode ?: 0L, info?.lastUpdateTime ?: 0L)
        }.sortedWith { a, b -> collator.compare(a.entry.label, b.entry.label) }
    }
}
