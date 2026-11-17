package com.droidhands.android.ui.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.droidhands.android.ui.components.Accents
import com.droidhands.android.ui.components.IconRowInset
import com.droidhands.android.ui.components.IconTile
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ListSection

/** One line of the safety promise: a short title, the full sentence, and its icon tile. */
private class Promise(val title: String, val text: String, val icon: ImageVector, val color: Color)

private val PROMISES = listOf(
    Promise("You approve every agent", "And choose once what it may use. Anything else is refused.", Icons.Filled.CheckCircle, Accents.Green),
    Promise(
        "Ask first, if you want",
        "Have messages, calls and payments wait for your OK.",
        Icons.Filled.Notifications,
        Accents.Orange,
    ),
    Promise("See and revoke anytime", "Every action is logged. Stop or revoke an agent in one tap.", Icons.AutoMirrored.Filled.List, Accents.Blue),
    Promise("Keep apps off-limits", "Banking and password apps are blocked for every agent by default.", Icons.Filled.Lock, Accents.Purple),
)

/** Step 1: what DroidHands does, in one sentence, and the safety promise as a list. */
@Composable
internal fun WelcomeStep() {
    StepHeader(
        Icons.Filled.Phone,
        "Let your AI agents use this phone",
        "Claude, ChatGPT, Grok and other AI agents you approve can message, call and use apps on this phone for you.",
    )
    ListSection(
        title = "You stay in charge",
        footer = "About two minutes: allow a few permissions, then add your agent.",
    ) {
        PROMISES.forEachIndexed { index, promise ->
            if (index > 0) ListDivider(IconRowInset)
            ListRow(
                title = promise.title,
                subtitle = promise.text,
                leading = { IconTile(promise.icon, promise.color) },
            )
        }
    }
}
