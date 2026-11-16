package com.droidhands.android.confirm

import com.droidhands.android.tools.support.ApprovalGuard
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Which screens of a kind are in the foreground (pure, unit-tested). [onChange] hears "at least one is"
 * on every change, synchronously and in order. Thread-safe.
 */
class ForegroundScreens(private val onChange: (Boolean) -> Unit) {
    private val screens: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())

    @Synchronized
    fun resumed(screen: Any) {
        screens += screen
        onChange(true)
    }

    @Synchronized
    fun paused(screen: Any) {
        screens -= screen
        onChange(screens.isNotEmpty())
    }
}

/**
 * Keeps [ApprovalGuard] active while [ConfirmationActivity] or
 * [com.droidhands.android.pairing.PairingActivity] is resumed: Android hides those
 * accessibility-data-sensitive windows from our accessibility service, so the screen-control tools
 * can't see them, and must not tap blindly into an Allow button. Call from onResume/onPause.
 *
 * Its own [ApprovalGuard] source, so other approval UIs reporting theirs can't clear it.
 */
object ApprovalScreens {
    const val GUARD_SOURCE = "approval_activity"

    private val screens = ForegroundScreens { ApprovalGuard.set(GUARD_SOURCE, it) }

    fun resumed(screen: Any) = screens.resumed(screen)

    fun paused(screen: Any) = screens.paused(screen)
}
