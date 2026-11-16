package com.droidhands.android.confirm

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Hardens an approval screen (agent actions, agent pairing) against other apps and services:
 * * `FLAG_SECURE`: no screenshots, screen recording or casting of the screen, and it is blanked in
 *   the recents thumbnail;
 * * Android 12+: other apps' overlay windows are hidden while it is showing (`HIDE_OVERLAY_WINDOWS`);
 * * touches are dropped while another window obscures it (tapjacking);
 * * Android 14+: marked accessibility-data-sensitive, so only accessibility *tools* can read or press it.
 *
 * Call in `onCreate` before `setContent`.
 */
internal fun Activity.applySecureWindow() {
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    if (Build.VERSION.SDK_INT >= 31) {
        try {
            window.setHideOverlayWindows(true)
        } catch (e: SecurityException) {
            // HIDE_OVERLAY_WINDOWS is a normal (install-time) permission, so this shouldn't happen.
            Log.w("DroidHands/Secure", "Could not hide overlay windows", e)
        }
    }
    window.decorView.filterTouchesWhenObscured = true
    if (Build.VERSION.SDK_INT >= 34) {
        window.decorView.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }
}
