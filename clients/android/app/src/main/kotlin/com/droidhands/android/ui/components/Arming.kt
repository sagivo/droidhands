package com.droidhands.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.delay

/**
 * An approve-type button (Allow, Connect) ignores taps until it has stayed in place for this long,
 * so a tap meant for something else can't hit it as it appears or moves. Same as the approval screen.
 */
internal const val ARM_DELAY_MS = 700L

/** See [rememberArming]. */
@Stable
internal class Arming {
    /** Pass as the button's `enabled`. */
    var armed by mutableStateOf(false)
        internal set

    internal var position by mutableStateOf(Offset.Unspecified)
        private set

    /** Put on the button: when it moves on screen (list change, scroll) it disarms and re-arms. */
    val modifier: Modifier = Modifier.onGloballyPositioned { position = it.positionInWindow() }
}

/**
 * False until the button showing [key] has been on screen, without moving, for [ARM_DELAY_MS]. A new
 * [key] (another request in the same place) re-arms too.
 */
@Composable
internal fun rememberArming(key: Any?): Arming {
    val arming = remember { Arming() }
    LaunchedEffect(key, arming.position) {
        arming.armed = false
        delay(ARM_DELAY_MS)
        arming.armed = true
    }
    return arming
}
