package dev.phonebridge.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val PROMISES = listOf(
    "Every agent must be allowed on this phone first.",
    "Sending messages, calls and other sensitive actions ask you before they happen.",
    "See everything an agent did, and revoke it with one tap.",
    "Banking, payment and password apps can be blocked for all agents.",
)

/** Step 1: what PhoneBridge does, in one sentence, and the safety promise. */
@Composable
internal fun WelcomeStep() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .size(56.dp)
                .align(Alignment.CenterHorizontally),
        )
        StepTitle(
            "Let your AI agents use this phone",
            "PhoneBridge lets AI assistants you approve, like Claude or ChatGPT, read and send messages, handle " +
                "notifications and use apps on this phone for you.",
        )
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("You stay in charge", style = MaterialTheme.typography.titleMedium)
                PROMISES.forEach { promise ->
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(promise, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Text(
            "Setup takes about two minutes: allow a few permissions, get the phone online, and add your first agent.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
