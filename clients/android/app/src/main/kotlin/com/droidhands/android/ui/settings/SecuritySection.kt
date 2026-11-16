package com.droidhands.android.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.droidhands.android.ui.components.Accents
import com.droidhands.android.ui.components.IconRowInset
import com.droidhands.android.ui.components.IconTile
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.SwitchRow
import com.droidhands.android.ui.setup.SetupUiState

/**
 * "Permissions & access" row: progress of the setup checklist, with a green tile once the
 * essentials are done and an orange one while one is missing; opens the checklist.
 */
@Composable
internal fun PermissionsRow(setup: SetupUiState, onOpen: () -> Unit) {
    val progress = setup.progress
    val text = when {
        !setup.loaded -> "Checking…"
        progress.essentialComplete -> "${progress.done} of ${progress.total} granted · essentials done"
        else -> "${progress.done} of ${progress.total} granted · ${progress.essentialMissing.size} essential missing"
    }
    val (icon, color) = when {
        !setup.loaded -> Icons.Filled.CheckCircle to Accents.Gray
        progress.essentialComplete -> Icons.Filled.CheckCircle to Accents.Green
        else -> Icons.Filled.Warning to Accents.Orange
    }
    ListRow(
        title = "Permissions & access",
        subtitle = text,
        leading = { IconTile(icon, color) },
        onClick = onOpen,
    )
}

/** Device-wide protections that apply to every agent: never-allow apps and hidden one-time codes. */
@Composable
internal fun SecuritySection(
    neverAllowCount: Int,
    redactOneTimeCodes: Boolean,
    onOpenNeverAllow: () -> Unit,
    onRedactChange: (Boolean) -> Unit,
) {
    ListSection(
        title = "Security",
        footer = "With hidden codes, verification and 2FA codes in SMS and notifications reach agents as [code hidden], " +
            "so a tricked agent can't use them to take over your accounts. Screen reading and screenshots can still " +
            "show a code that is on screen: add your SMS and authenticator apps to Never-allow apps.",
    ) {
        ListRow(
            title = "Never-allow apps",
            subtitle = when (neverAllowCount) {
                0 -> "None. Agents may use any app their access allows."
                1 -> "1 app no agent can see or operate"
                else -> "$neverAllowCount apps no agent can see or operate"
            },
            leading = { IconTile(Icons.Filled.Clear, Accents.Orange) },
            onClick = onOpenNeverAllow,
        )
        ListDivider(IconRowInset)
        SwitchRow(
            title = "Hide one-time codes",
            subtitle = "Recommended. Agents see [code hidden] instead.",
            checked = redactOneTimeCodes,
            onCheckedChange = onRedactChange,
            leading = { IconTile(Icons.Filled.Lock, Accents.Orange) },
        )
    }
}

/**
 * "Revoke all agents", last on Settings in its own section: locks every agent out at once. The
 * caller's confirmation dialog runs before anything is revoked. Disabled without active agents.
 */
@Composable
internal fun RevokeAllSection(activeAgents: Int, onRevokeAll: () -> Unit) {
    val enabled = activeAgents > 0
    val scheme = MaterialTheme.colorScheme
    ListSection(footer = "Lock every agent out at once, for example if you think one misbehaved.") {
        ListRow(
            title = "Revoke all agents",
            subtitle = when (activeAgents) {
                0 -> "No active agents"
                1 -> "1 active agent"
                else -> "$activeAgents active agents"
            },
            leading = { IconTile(Icons.Filled.Close, if (enabled) Accents.Red else Accents.Gray) },
            titleColor = if (enabled) scheme.error else scheme.onSurfaceVariant,
            onClick = onRevokeAll,
            enabled = enabled,
            showChevron = false,
        )
    }
}
