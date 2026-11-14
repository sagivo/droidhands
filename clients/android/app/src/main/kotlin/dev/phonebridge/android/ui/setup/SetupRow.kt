package dev.phonebridge.android.ui.setup

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.theme.statusColors

/** One checklist row: status icon, explanation, what it unlocks, and the action to fix it. */
@Composable
internal fun SetupRow(
    item: SetupItem,
    onAction: (SetupItem) -> Unit,
    onOpenAppInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = statusColors()
    val (icon, tint) = statusIcon(item, colors.ok, colors.warning, colors.error, MaterialTheme.colorScheme.outline)
    val statusText = SetupLogic.statusLabel(item.status, item.blocked)
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text(statusText, style = MaterialTheme.typography.labelMedium, color = tint)
                }
            }
            Text(item.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Unlocks: ${item.unlocks}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RowNotes(item, onOpenAppInfo)
            SetupLogic.actionLabel(item)?.let { label ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (item.essential) {
                        Button(onClick = { onAction(item) }) { Text(label) }
                    } else {
                        FilledTonalButton(onClick = { onAction(item) }) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowNotes(item: SetupItem, onOpenAppInfo: () -> Unit) {
    if (item.id == SetupLogic.ACCESSIBILITY && item.status == SetupStatus.PENDING) {
        Notice(
            "Turned on in Settings, but Android hasn't connected the service yet. If this doesn't change in a few " +
                "seconds, turn PhoneBridge off and on again in Accessibility settings.",
            kind = NoticeKind.WARNING,
        )
    }
    if (item.blocked) {
        Notice(
            "Android won't show the permission dialog again. Allow it in App info (Permissions or Notifications).",
            kind = NoticeKind.WARNING,
        )
    }
    when {
        item.restrictedSettingsGuide -> RestrictedSettingsGuide(onOpenAppInfo)
        item.restrictedSettingsHelp -> RestrictedSettingsHelp(onOpenAppInfo)
    }
}

/**
 * The numbered "Allow restricted settings" steps for an app installed from an APK file on Android
 * 13+ ([RestrictedSettings.showGuide]), with the Open App info button. The caller places it above
 * the accessibility row's own "Open Accessibility" button, which step 1 refers to, or passes
 * [onOpenAccessibility] to show that button here. [unlocks] is what Android blocks until then.
 * The buttons use the content color the caller's container sets, so they read on any container.
 */
@Composable
internal fun RestrictedSettingsGuide(
    onOpenAppInfo: () -> Unit,
    modifier: Modifier = Modifier,
    unlocks: String = "accessibility",
    onOpenAccessibility: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Installed from the web? One extra step", style = MaterialTheme.typography.titleMedium)
        Text(
            "Android blocks $unlocks for apps installed outside an app store until you allow restricted settings:",
            style = MaterialTheme.typography.bodyMedium,
        )
        RestrictedSettings.GUIDE_STEPS.forEachIndexed { index, step ->
            Row {
                Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(24.dp))
                Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            val content = LocalContentColor.current
            if (onOpenAccessibility != null) {
                OutlinedButton(
                    onClick = onOpenAccessibility,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = content),
                    border = BorderStroke(1.dp, content),
                ) { Text("Open Accessibility") }
            }
            // Filled in the card's own on-color: an outlined button in the theme's accent was unreadable on it.
            // The label takes black or white, whichever contrasts with that fill.
            Button(
                onClick = onOpenAppInfo,
                colors = ButtonDefaults.buttonColors(
                    containerColor = content,
                    contentColor = if (content.luminance() > 0.5f) Color.Black else Color.White,
                ),
            ) { Text("Open App info") }
        }
    }
}

/**
 * Android 13+ greys out accessibility and notification access for apps installed from a file
 * ("Restricted setting"); explains the App info > ⋮ > "Allow restricted settings" workaround.
 */
@Composable
internal fun RestrictedSettingsHelp(onOpenAppInfo: () -> Unit) {
    Column {
        Notice(
            title = "Switch greyed out (\"Restricted setting\")?",
            text = "Android 13+ restricts this for apps installed outside an app store. Try turning it on once, " +
                "then open App info, tap ⋮ (top corner) > Allow restricted settings and confirm. Come back here " +
                "and turn PhoneBridge on again.",
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onOpenAppInfo) { Text("Open App info") }
        }
    }
}

private fun statusIcon(item: SetupItem, ok: Color, warning: Color, error: Color, off: Color): Pair<ImageVector, Color> = when {
    item.status == SetupStatus.DONE -> Icons.Filled.CheckCircle to ok
    item.blocked -> Icons.Filled.Lock to error
    item.status == SetupStatus.NEEDS_PREREQUISITE -> Icons.Outlined.Lock to off
    item.status == SetupStatus.PARTIAL || item.status == SetupStatus.PENDING -> Icons.Filled.Info to warning
    item.essential -> Icons.Filled.Warning to warning
    else -> Icons.Outlined.Info to off
}
