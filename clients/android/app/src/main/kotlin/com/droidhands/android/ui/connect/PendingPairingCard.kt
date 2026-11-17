package com.droidhands.android.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.droidhands.android.ui.components.AppCard
import com.droidhands.android.ui.components.IconBadge
import com.droidhands.android.ui.components.PrimaryButton
import com.droidhands.android.ui.components.SecondaryButton
import com.droidhands.core.transport.PairingRequest

/**
 * "Claude (claude.ai) wants to connect · code K7Q2" (or "Instinct wants to connect" for an agent sign-in): a one-tap pairing waiting for the user.
 * Review opens the full approval screen (choose access, then Allow); Deny refuses right away.
 */
@Composable
internal fun PendingPairingCard(
    request: PairingRequest,
    now: Long,
    onReview: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppCard(onClick = onReview, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Face, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(PairingWatch.title(request), style = MaterialTheme.typography.titleMedium)
                Text(
                    PairingWatch.timeLeft(request.expiresAt, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Code", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        request.displayCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.semantics { contentDescription = PairingWatch.spokenCode(request.displayCode) },
                    )
                }
            }
        }
        Text(
            PairingWatch.cardHint(request),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Deny", onClick = onDeny, modifier = Modifier.weight(1f))
            PrimaryButton("Review", onClick = onReview, modifier = Modifier.weight(1f))
        }
    }
}
