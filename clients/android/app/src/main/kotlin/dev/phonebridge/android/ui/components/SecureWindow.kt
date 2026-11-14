package dev.phonebridge.android.ui.components

import android.view.Window
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import java.util.WeakHashMap

/**
 * Reference-counted FLAG_SECURE per window. Several parts of a screen may need it at once (a new
 * token and an approval card); the flag is set while at least one holds it and cleared as soon as
 * the last one lets go, so the rest of the app stays screenshot-able (useful for support).
 * Main thread only, like every Window call.
 */
internal object SecureWindows {
    private val holders = WeakHashMap<Window, Int>()

    @MainThread
    fun acquire(window: Window) {
        val count = (holders[window] ?: 0) + 1
        holders[window] = count
        if (count == 1) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    @MainThread
    fun release(window: Window) {
        val count = (holders[window] ?: return) - 1
        if (count <= 0) {
            holders.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            holders[window] = count
        }
    }
}

/**
 * Blocks screenshots, screen recording and the Recents thumbnail while [enabled] and in
 * composition: use it only while a token plaintext or an approval is on screen. Leaving
 * composition (or [enabled] turning false) releases it.
 */
@Composable
fun SecureWindowEffect(enabled: Boolean = true) {
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(window, enabled) {
        if (window == null || !enabled) return@DisposableEffect onDispose { }
        SecureWindows.acquire(window)
        onDispose { SecureWindows.release(window) }
    }
}
