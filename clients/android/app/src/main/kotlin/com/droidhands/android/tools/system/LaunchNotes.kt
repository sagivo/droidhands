package com.droidhands.android.tools.system

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import com.droidhands.android.Edition
import com.droidhands.android.access.BridgeAccessibilityService
import com.droidhands.android.tools.support.DeviceState

/** Agent-facing caveats for tools that start an activity on the phone. */
internal object LaunchNotes {
    const val CHOOSER =
        "Android is showing its app chooser: the user has to pick the app on the phone (or call again with 'package')."

    /**
     * Null when nothing gets in the way, else a hint such as "the phone is locked" or "Android may
     * block launches from the background because accessibility is off". [chooser]: the launch
     * showed Android's "Open with" chooser instead of an app.
     */
    fun afterLaunch(context: Context, chooser: Boolean = false): String? {
        val notes = buildList {
            if (chooser) add(CHOOSER)
            if (DeviceState.isLocked(context)) {
                add("The phone is locked: the user has to unlock it to see the result.")
            } else if (!DeviceState.isScreenOn(context)) {
                add("The screen is off: the user has to turn it on to see the result.")
            }
            if (!canStartFromBackground(context)) {
                add(
                    if (Edition.PHONE_USE) {
                        "DroidHands's accessibility service is off, so Android may silently block opening apps from the " +
                            "background. If nothing opened, ask the user to enable it in DroidHands app > Settings > Permissions & access > Accessibility."
                    } else {
                        "Android may silently block opening apps from the background. If nothing opened, ask the user to allow " +
                            "Display over other apps in DroidHands app > Settings > Permissions & access."
                    },
                )
            }
        }
        return notes.joinToString(" ").ifEmpty { null }
    }

    /** True if an exemption from Android's background-activity-start limits applies right now. */
    fun canStartFromBackground(context: Context): Boolean =
        BridgeAccessibilityService.instance != null || Settings.canDrawOverlays(context) || isAppInForeground()

    /** Whether DroidHands's own UI is visible (a foreground service alone does not count). */
    fun isAppInForeground(): Boolean = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)
}
