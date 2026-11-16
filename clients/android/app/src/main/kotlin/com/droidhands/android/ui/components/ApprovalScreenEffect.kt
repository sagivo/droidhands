package com.droidhands.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.droidhands.android.tools.support.ApprovalGuard

/**
 * The [ApprovalGuard] source of DroidHands's approval screens: on while one is resumed, so agents'
 * screen control is refused while the user decides (pure).
 */
internal object ApprovalScreens {
    const val SOURCE = "approval_screen"

    /**
     * The guard after [event], or null to leave it. Resumed-only: when one approval screen covers
     * another, the old one pauses before the new one resumes, so the guard stays on throughout.
     */
    fun activeAfter(event: Lifecycle.Event): Boolean? = when (event) {
        Lifecycle.Event.ON_RESUME -> true
        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_DESTROY -> false
        else -> null
    }
}

/** Keeps [ApprovalGuard] on while this approval UI (e.g. a dialog) is in composition and its screen is resumed. */
@Composable
internal fun ApprovalScreenEffect() {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        // Adding the observer replays the events up to the current state, so an already resumed screen turns it on.
        val observer = LifecycleEventObserver { _, event ->
            ApprovalScreens.activeAfter(event)?.let { ApprovalGuard.set(ApprovalScreens.SOURCE, it) }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Not resumed: ON_PAUSE already turned it off, and another approval screen may hold it now.
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) ApprovalGuard.set(ApprovalScreens.SOURCE, false)
        }
    }
}
