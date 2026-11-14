package dev.phonebridge.android.ui.onboarding

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.setup.RestrictedSettingsHelp
import dev.phonebridge.android.ui.setup.SetupActions
import dev.phonebridge.android.ui.setup.SetupItem
import dev.phonebridge.android.ui.setup.SetupLogic
import dev.phonebridge.android.ui.setup.SetupStatus
import dev.phonebridge.android.ui.setup.SetupViewModel
import dev.phonebridge.android.ui.setup.WithSetupActions
import dev.phonebridge.android.ui.theme.statusColors

/**
 * Step 2: "Grant all" asks for every runtime permission in one go, then the special accesses are
 * walked one at a time: one tap opens the right system screen, and when the user comes back with
 * it granted, the next one is highlighted automatically. Everything can be skipped.
 */
@Composable
internal fun PermissionsStep(setup: SetupViewModel) {
    val state by setup.state.collectAsStateWithLifecycle()
    var skipped by rememberSaveable { mutableStateOf(listOf<String>()) }
    StepTitle(
        "Allow what your agents need",
        "Each permission unlocks specific abilities. Agents are told exactly what's missing, and you can change " +
            "everything later in Settings.",
    )
    if (!state.loaded) {
        CircularProgressIndicator(Modifier.padding(16.dp))
        return
    }
    WithSetupActions(setup) { actions ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RuntimeCard(state.items, actions)
            val next = OnboardingFlow.nextSpecialAccess(state.items, skipped.toSet())
            if (next != null) {
                NextAccessCard(next, actions, onSkip = { skipped = skipped + next.id })
            } else {
                val (done, total) = OnboardingFlow.specialProgress(state.items)
                DoneLine(if (done == total) "All special accesses granted" else "Special accesses: $done of $total granted")
            }
            if (OnboardingFlow.essentialsDone(state.items)) {
                Notice("The essentials are done. Continue whenever you're ready.")
            }
            Overview(state.items, actions)
        }
    }
}

@Composable
private fun RuntimeCard(items: List<SetupItem>, actions: SetupActions) {
    val toRequest = OnboardingFlow.runtimeToRequest(items)
    val blocked = OnboardingFlow.blockedRuntime(items)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App permissions", style = MaterialTheme.typography.titleMedium)
            Text(
                "Notifications (for Allow/Deny prompts), contacts, SMS, phone, calendar and location. Android asks " +
                    "about each one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                toRequest.isNotEmpty() -> Button(onClick = { actions.requestPermissions(toRequest) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Grant all")
                }
                blocked.isNotEmpty() -> {
                    Notice(
                        "Android won't ask again for: ${blocked.joinToString { it.title }}. Allow them in App info > Permissions.",
                        kind = NoticeKind.WARNING,
                    )
                    OutlinedButton(onClick = actions.openAppInfo) { Text("Open App info") }
                }
                else -> DoneLine("App permissions granted")
            }
        }
    }
}

@Composable
private fun NextAccessCard(item: SetupItem, actions: SetupActions, onSkip: () -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Next: ${item.title}" + if (item.essential) "" else " (optional)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(item.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(
                "Unlocks: ${item.unlocks}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (item.id == SetupLogic.ACCESSIBILITY && item.status == SetupStatus.PENDING) {
                Notice(
                    "Turned on, but Android hasn't connected the service yet. If this doesn't change in a few seconds, " +
                        "turn PhoneBridge off and on again in Accessibility settings.",
                    kind = NoticeKind.WARNING,
                )
            }
            if (item.id == SetupLogic.ACCESSIBILITY) {
                Text(
                    "On the next screen tap \"Installed apps\" (on some phones \"Downloaded apps\") > PhoneBridge, then turn it on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (item.restrictedSettingsHelp) RestrictedSettingsHelp(actions.openAppInfo)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onSkip) { Text("Skip") }
                Button(onClick = { actions.onAction(item) }) { Text(SetupLogic.actionLabel(item) ?: "Open settings") }
            }
        }
    }
}

/** Every checklist row in one compact list; tapping an open row fixes it. */
@Composable
private fun Overview(items: List<SetupItem>, actions: SetupActions) {
    Column {
        SectionHeader("Everything")
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column {
                items.forEachIndexed { index, item ->
                    if (index > 0) HorizontalDivider()
                    CompactRow(item, onClick = SetupLogic.actionLabel(item)?.let { { actions.onAction(item) } })
                }
            }
        }
    }
}

@Composable
private fun CompactRow(item: SetupItem, onClick: (() -> Unit)?) {
    val colors = statusColors()
    val done = item.status == SetupStatus.DONE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClickLabel = SetupLogic.actionLabel(item), onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.Info,
            contentDescription = null,
            tint = if (done) colors.ok else if (item.essential) colors.warning else MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                SetupLogic.statusLabel(item.status, item.blocked) + if (!item.essential && !done) " · optional" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DoneLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = statusColors().ok, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
