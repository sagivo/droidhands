package com.droidhands.android.ui.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * Launchable apps (MAIN/LAUNCHER) for the pickers, and their icons. Every PackageManager call runs
 * on [Dispatchers.IO]; icons are cached (bounded) for the process lifetime.
 */
internal object LaunchableApps {
    private const val TAG = "DroidHandsUi"
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

    /** Cached icon of [packageName] drawn at [sizePx], or null if it isn't loaded (yet). */
    fun cachedIcon(packageName: String, sizePx: Int): ImageBitmap? = icons.get(iconKey(packageName, sizePx))

    /**
     * Loads (and caches) the launcher icon of [packageName] drawn at [sizePx]; null if the app is gone or its
     * icon can't be drawn. Drawn into a software bitmap from a fresh copy of the drawable, so the system's
     * shared icon instance is never resized.
     */
    suspend fun icon(context: Context, packageName: String, sizePx: Int): ImageBitmap? {
        val key = iconKey(packageName, sizePx)
        icons.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                rasterize(drawable, sizePx).asImageBitmap().also { icons.put(key, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PackageManager.NameNotFoundException) {
                null
            } catch (e: RuntimeException) {
                Log.w(TAG, "Loading the icon of $packageName failed: ${e.javaClass.name}: ${e.message}")
                null
            }
        }
    }

    private fun iconKey(packageName: String, sizePx: Int) = "$packageName@$sizePx"

    private fun rasterize(icon: Drawable, sizePx: Int): Bitmap {
        val drawable = icon.constantState?.newDrawable()?.mutate() ?: icon
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }
}
