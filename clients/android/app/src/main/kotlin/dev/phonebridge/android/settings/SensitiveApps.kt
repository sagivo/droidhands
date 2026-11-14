package dev.phonebridge.android.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.annotation.WorkerThread

/** An installed app that looks too sensitive for agents. */
data class SensitiveApp(val packageName: String, val label: String, val category: SensitiveCategory)

/**
 * Finds installed banking, payment, crypto, password-manager and authenticator apps
 * ([SensitiveAppMatcher]) among the launchable apps. Needs QUERY_ALL_PACKAGES (declared) to see
 * every app on Android 11+.
 */
object SensitiveApps {
    /**
     * Installed launchable apps that look sensitive, sorted by category then label (for the security
     * settings' "suggested" list). PhoneBridge itself is excluded (it is always blocked anyway).
     * Blocking PackageManager IPC: call from a background thread.
     *
     * @throws RuntimeException if the package manager is unavailable (e.g. the system is shutting down).
     */
    @WorkerThread
    fun suggest(context: Context): List<SensitiveApp> = launchableApps(context)
        .filter { (pkg, _) -> pkg != context.packageName }
        .mapNotNull { (pkg, label) -> SensitiveAppMatcher.match(pkg, label)?.let { SensitiveApp(pkg, label, it) } }
        .sortedWith(compareBy<SensitiveApp> { it.category.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    /** Package name → label of every app with a launcher activity. */
    @WorkerThread
    internal fun launchableApps(context: Context): List<Pair<String, String>> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
        }
        return activities
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = try {
                    info.loadLabel(pm).toString()
                } catch (e: RuntimeException) {
                    pkg
                }
                pkg to label
            }
            .distinctBy { it.first }
    }
}

/** First-run security defaults (pure, unit-tested). */
object SecurityDefaults {
    /**
     * Adds [detected] sensitive packages to the never-allow list and marks the defaults as applied.
     * A no-op once applied, so apps the user later unblocks stay unblocked.
     */
    fun apply(settings: BridgeSettings, detected: Collection<String>): BridgeSettings =
        if (settings.securityDefaultsApplied) {
            settings
        } else {
            settings.copy(neverAllowApps = settings.neverAllowApps + detected, securityDefaultsApplied = true)
        }
}
