package dev.phonebridge.android.tools.system

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator

/** Launchable apps (those with a MAIN/LAUNCHER activity), as the home screen shows them. */
internal object InstalledApps {
    /** One entry per package, sorted by label. Blocking PackageManager IPC runs on Dispatchers.IO. */
    suspend fun launchable(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = pm.queryIntentActivitiesCompat(launcher).mapNotNull { it.activityInfo }.distinctBy { it.packageName }
        val collator = Collator.getInstance()
        activities.map { activity ->
            val app = activity.applicationInfo
            val label = runCatching { app.loadLabel(pm).toString().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: runCatching { activity.loadLabel(pm).toString().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: activity.packageName
            val system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val updated = app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            AppEntry(
                packageName = activity.packageName,
                label = label,
                versionName = runCatching { pm.packageInfoOrNull(activity.packageName)?.versionName }.getOrNull(),
                system = system,
                preinstalledOnly = system && !updated,
            )
        }.sortedWith { a, b -> collator.compare(a.label, b.label) }
    }
}
