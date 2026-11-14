package dev.phonebridge.android.ui.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.phonebridge.android.ui.components.Accents
import dev.phonebridge.android.ui.components.IconRowInset
import dev.phonebridge.android.ui.components.IconTile
import dev.phonebridge.android.ui.components.ListDivider
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.ListSection

/** One line of the safety promise: a short title, the full sentence, and its icon tile. */
private class Promise(val title: String, val text: String, val icon: ImageVector, val color: Color)

private val PROMISES = listOf(
    Promise("You approve every agent", "Every agent must be allowed on this phone first.", Icons.Filled.CheckCircle, Accents.Green),
    Promise(
        "Sensitive actions ask first",
        "Sending messages, calls and other sensitive actions ask you before they happen.",
        Icons.Filled.Notifications,
        Accents.Orange,
    ),
    Promise("See and revoke anytime", "See everything an agent did, and revoke it with one tap.", Icons.AutoMirrored.Filled.List, Accents.Blue),
    Promise("Keep apps off-limits", "Banking, payment and password apps can be blocked for all agents.", Icons.Filled.Lock, Accents.Purple),
)

/** Step 1: what PhoneBridge does, in one sentence, and the safety promise as a list. */
@Composable
internal fun WelcomeStep() {
    StepHeader(
        Icons.Filled.Phone,
        "Let your AI agents use this phone",
        "AI assistants you approve, like Claude or ChatGPT, can read and send messages, handle notifications and use " +
            "apps on this phone for you.",
    )
    ListSection(
        title = "You stay in charge",
        footer = "Setup takes about two minutes: allow a few permissions, get the phone online, and add your first agent.",
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
