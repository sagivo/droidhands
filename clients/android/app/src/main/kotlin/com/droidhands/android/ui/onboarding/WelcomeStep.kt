package com.droidhands.android.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.droidhands.android.R
import com.droidhands.android.ui.components.Accents
import com.droidhands.android.ui.components.IconRowInset
import com.droidhands.android.ui.components.IconTile
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ListSection

/** One line of the safety promise: a short title, concise explanation, and its icon tile. */
private class Promise(val title: String, val text: String, val icon: ImageVector, val color: Color)

private val PROMISES = listOf(
    Promise("You approve every agent", "Choose what it can access, or revoke it in one tap.", Icons.Filled.CheckCircle, Accents.Green),
    Promise("Ask first when it matters", "Confirm messages, calls and sensitive actions before they run.", Icons.Filled.Notifications, Accents.Orange),
    Promise("Private apps stay blocked", "Banking and password apps are off-limits by default.", Icons.Filled.Lock, Accents.Purple),
)

/** Step 1: the DroidHands mascot, one-line pitch, and three concise safety guarantees. */
@Composable
internal fun WelcomeStep() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.size(76.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "DroidHands",
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.35f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Let your AI agents use this phone",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "Message, call and use apps with Claude, ChatGPT, Grok or any AI agent.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    ListSection {
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
