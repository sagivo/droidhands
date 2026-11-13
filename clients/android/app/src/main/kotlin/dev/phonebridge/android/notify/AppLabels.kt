package dev.phonebridge.android.notify

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide cache of app labels by package. Label lookups load resources, so the first call
 * per package can take tens of milliseconds: call from a background thread. Labels practically
 * never change, and the cache is bounded by the number of installed apps.
 */
object AppLabels {
    private val cache = ConcurrentHashMap<String, String>()

    /** User-visible label of [packageName], or the package name if it can't be resolved. */
    fun of(context: Context, packageName: String): String = cache.getOrPut(packageName) { load(context, packageName) }

    /** Cached label only; never touches PackageManager. */
    fun cached(packageName: String): String? = cache[packageName]

    private fun load(context: Context, packageName: String): String {
        val pm = context.packageManager
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            NotificationText.clean(pm.getApplicationLabel(info)) ?: packageName
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        } catch (e: RuntimeException) {
            packageName
        }
    }
}
