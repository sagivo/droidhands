package dev.phonebridge.android.ui.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * Launchable apps (MAIN/LAUNCHER) for the pickers, and their icons. Every PackageManager call runs
 * on [Dispatchers.IO]; icons are cached (bounded) for the process lifetime.
 */
internal object LaunchableApps {
    private const val TAG = "PhoneBridgeUi"
    private const val ICON_CACHE_SIZE = 160

    private val icons = object : LruCache<String, ImageBitmap>(ICON_CACHE_SIZE) {}

    /**
     * One entry per package, sorted by label for the current locale. [exclude] is left out (the
     * bridge itself: it is always blocked and never offered).
     */
    suspend fun load(context: Context, exclude: Set<String> = setOf(context.packageName)): List<AppInfo> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = try {
                queryActivities(pm, launcher)
            } catch (e: RuntimeException) {
                // Binder failures (huge app lists, dying system server) shouldn't crash the picker.
                Log.w(TAG, "Listing apps failed: ${e.javaClass.name}")
                emptyList()
            }
            val collator = Collator.getInstance()
            resolved.mapNotNull { it.activityInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName !in exclude }
                .map { activity ->
                    val label = runCatching { activity.applicationInfo.loadLabel(pm).toString().trim() }.getOrNull()
                        ?.takeIf { it.isNotEmpty() } ?: activity.packageName
                    AppInfo(activity.packageName, label)
                }
                .sortedWith { a, b -> collator.compare(a.label, b.label) }
        }

    @Suppress("DEPRECATION")
    private fun queryActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(intent, 0)
        }

    /** Cached icon of [packageName], or null if it has none / isn't installed. */
    fun cachedIcon(packageName: String): ImageBitmap? = icons.get(packageName)

    /** Loads (and caches) the icon of [packageName] at [sizePx]; null if unavailable. */
    suspend fun icon(context: Context, packageName: String, sizePx: Int): ImageBitmap? {
        icons.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                drawable.toBitmap(sizePx, sizePx).asImageBitmap().also { icons.put(packageName, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PackageManager.NameNotFoundException) {
                null
            } catch (e: RuntimeException) {
                Log.w(TAG, "Loading an app icon failed: ${e.javaClass.name}")
                null
            }
        }
    }
}
