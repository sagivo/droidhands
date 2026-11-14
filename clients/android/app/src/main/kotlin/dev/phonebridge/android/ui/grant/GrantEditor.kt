package dev.phonebridge.android.ui.grant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.SwitchRow
import dev.phonebridge.core.auth.Ability
import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.auth.GrantDraft
import dev.phonebridge.core.auth.GrantMode

/**
 * The grant ("Give <agent> access"): Everything, or Only what I choose (apps and abilities), the one
 * "ask me before it sends messages, calls or pays" switch and the never-allow line. One stateless block of
 * plain rows, to be placed inside the caller's scrolling column; used by the pairing screen, the manual token
 * form and Agents > (agent) > Access, so all three look and behave the same.
 *
 * [original] is the saved grant when editing an existing agent (its older settings get their own rows, so
 * they are kept until the user changes them); null for a new agent. [onChooseApps] opens the app picker.
 */
@Composable
internal fun GrantEditor(
    grant: GrantDraft,
    original: GrantDraft?,
    appLabels: Map<String, String>,
    neverAllowCount: Int,
    enabled: Boolean,
    onChange: (GrantDraft) -> Unit,
    onChooseApps: () -> Unit,
    onChangeNeverAllow: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.selectableGroup()) {
            GrantChoiceRow(
                title = GrantPresentation.EVERYTHING,
                description = GrantPresentation.EVERYTHING_DESCRIPTION,
                selected = grant.mode == GrantMode.EVERYTHING,
                enabled = enabled,
                onSelect = { onChange(grant.withMode(GrantMode.EVERYTHING)) },
            )
            GrantChoiceRow(
                title = GrantPresentation.CHOSEN,
                description = GrantPresentation.CHOSEN_DESCRIPTION,
                selected = grant.mode == GrantMode.CHOSEN,
                enabled = enabled,
                onSelect = { onChange(grant.withMode(GrantMode.CHOSEN)) },
            )
        }
        if (grant.mode == GrantMode.CHOSEN) {
            ChosenSection(grant, original, appLabels, enabled, onChange, onChooseApps)
        }
        Spacer(Modifier.padding(top = 4.dp))
        SwitchRow(
            title = GrantPresentation.ASK_SWITCH,
            subtitle = if (grant.asksBeforeSensitive) GrantPresentation.ASK_SWITCH_ON else GrantPresentation.ASK_SWITCH_OFF,
            checked = grant.asksBeforeSensitive,
            onCheckedChange = { onChange(grant.withAsk(it)) },
            enabled = enabled,
        )
        if (grant.asksBeforeEveryAction) {
            Notice(GrantPresentation.EVERY_ACTION_NOTICE, kind = NoticeKind.INFO)
            TextButton(onClick = { onChange(grant.withAsk(true)) }, enabled = enabled) { Text(GrantPresentation.NARROW_ASKING) }
        }
        Text(
            GrantPresentation.neverAllowLine(neverAllowCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (onChangeNeverAllow != null) {
            TextButton(onClick = onChangeNeverAllow, enabled = enabled) { Text(GrantPresentation.CHANGE_NEVER_ALLOW) }
        }
    }
}

@Composable
private fun ChosenSection(
    grant: GrantDraft,
    original: GrantDraft?,
    appLabels: Map<String, String>,
    enabled: Boolean,
    onChange: (GrantDraft) -> Unit,
    onChooseApps: () -> Unit,
) {
    Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        GrantPresentation.capNotice(grant.maxRisk)?.let { notice ->
            Notice(notice, kind = NoticeKind.WARNING)
            TextButton(onClick = { onChange(grant.withoutRiskCap()) }, enabled = enabled) { Text(GrantPresentation.REMOVE_CAP) }
        }

        GroupTitle(GrantPresentation.APPS_HEADER)
        val legacyBlocklist = original?.apps?.takeIf { it.mode == AppAccessMode.BLOCKLIST }
        Column(Modifier.selectableGroup()) {
            GrantChoiceRow(GrantPresentation.ALL_APPS, null, grant.apps.mode == AppAccessMode.ALL, enabled) {
                onChange(grant.withAppMode(AppAccessMode.ALL))
            }
            GrantChoiceRow(GrantPresentation.CHOSEN_APPS, null, grant.apps.mode == AppAccessMode.ALLOWLIST, enabled) {
                onChange(grant.withAppMode(AppAccessMode.ALLOWLIST))
            }
            if (legacyBlocklist != null) {
                GrantChoiceRow(
                    GrantPresentation.blocklistOption(legacyBlocklist.packages.size), null,
                    grant.apps.mode == AppAccessMode.BLOCKLIST, enabled,
                ) { onChange(grant.copy(apps = legacyBlocklist)) }
            }
        }
        if (grant.apps.mode == AppAccessMode.ALLOWLIST) {
            Text(GrantPresentation.chosenAppsLine(grant.apps.packages, appLabels), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onChooseApps, enabled = enabled) { Text(GrantPresentation.CHOOSE_APPS) }
            grant.warning?.let { Notice(it, kind = NoticeKind.WARNING) }
        }

        GroupTitle(GrantPresentation.ABILITIES_HEADER)
        val full = grant.abilities
        val partial = grant.partialAbilities
        Ability.entries.forEach { ability ->
            val state = when (ability) {
                in full -> ToggleableState.On
                in partial -> ToggleableState.Indeterminate
                else -> ToggleableState.Off
            }
            AbilityRow(
                ability = ability,
                state = state,
                partly = if (state == ToggleableState.Indeterminate) GrantPresentation.partlyLine(ability, grant.scopes) else null,
                enabled = enabled,
                onToggle = { onChange(grant.toggle(ability)) },
            )
        }
        grant.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).semantics { heading() },
    )
}

@Composable
private fun AbilityRow(ability: Ability, state: ToggleableState, partly: String?, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .triStateToggleable(state = state, enabled = enabled, role = Role.Checkbox, onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The row handles clicks, so the checkbox itself is not separately focusable.
        TriStateCheckbox(state = state, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ability.label, style = MaterialTheme.typography.bodyLarge)
            Text(ability.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (partly != null) {
                Text(partly, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** A radio row with an optional explanation (the row is the click target). */
@Composable
internal fun GrantChoiceRow(title: String, description: String?, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = if (description == null) Alignment.CenterVertically else Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
