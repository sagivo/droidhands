package com.droidhands.android.ui.components

import android.os.Build
import android.util.Log
import android.view.Window
import android.view.WindowManager
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.util.WeakHashMap

/**
 * Reference-counted FLAG_SECURE per window. Several parts of a screen may need it at once (a new
 * token and an approval card); the flag is set while at least one holds it and cleared as soon as
 * the last one lets go, so the rest of the app stays screenshot-able (useful for support).
 * Main thread only, like every Window call.
 */
internal object SecureWindows {
    private val holders = HolderCounts<Window>(WeakHashMap())

    @MainThread
    fun acquire(window: Window) {
        if (holders.acquire(window)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    @MainThread
    fun release(window: Window) {
        if (holders.release(window)) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
}

/**
 * Reference-counted tapjacking protection per window, like `confirm.applySecureWindow` gives the
 * approval activities: touches are dropped while another app's window covers the touched spot
 * (`filterTouchesWhenObscured`), and on Android 12+ other apps' overlay windows are hidden while the
 * window is showing (`HIDE_OVERLAY_WINDOWS`). Main thread only.
 */
internal object TouchShields {
    private val holders = HolderCounts<Window>(WeakHashMap())

    @MainThread
    fun acquire(window: Window) {
        if (holders.acquire(window)) shield(window, on = true)
    }

    @MainThread
    fun release(window: Window) {
        if (holders.release(window)) shield(window, on = false)
    }

    private fun shield(window: Window, on: Boolean) {
        window.decorView.filterTouchesWhenObscured = on
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                window.setHideOverlayWindows(on)
            } catch (e: SecurityException) {
                // HIDE_OVERLAY_WINDOWS is a normal (install-time) permission, so this shouldn't happen.
                Log.w("DroidHandsUi", "Could not change overlay hiding", e)
            }
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

/**
 * Applies [TouchShields] to the window this is composed in while [enabled]: the activity's window,
 * or, inside a `Dialog`/`AlertDialog`, the dialog's own window. Use it while something on screen can
 * approve an agent action or change where the phone connects.
 */
@Composable
internal fun TouchShieldEffect(enabled: Boolean = true) {
    val view = LocalView.current
    val context = LocalContext.current
    val window = remember(view, context) { (view.parent as? DialogWindowProvider)?.window ?: context.findActivity()?.window }
    DisposableEffect(window, enabled) {
        if (window == null || !enabled) return@DisposableEffect onDispose { }
        TouchShields.acquire(window)
        onDispose { TouchShields.release(window) }
    }
}
