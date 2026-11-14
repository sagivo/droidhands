package dev.phonebridge.android.graph

import android.content.ComponentCallbacks2
import android.content.res.Configuration

/**
 * Calls [flush] when the UI goes to the background or the system asks the process to trim memory,
 * the last reliable moments before the process may be killed. [flush] must return quickly (it
 * should only schedule background work); it runs on the main thread.
 */
internal class FlushOnTrim(private val flush: () -> Unit) : ComponentCallbacks2 {
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) flush()
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = flush()
}
