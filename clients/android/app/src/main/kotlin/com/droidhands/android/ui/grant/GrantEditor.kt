package com.droidhands.android.ui.grant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.droidhands.android.Edition
import com.droidhands.android.ui.components.Accents
import com.droidhands.android.ui.components.AppsIcon
import com.droidhands.android.ui.components.CardPadding
import com.droidhands.android.ui.components.IconTile
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.Notice
import com.droidhands.android.ui.components.NoticeKind
import com.droidhands.android.ui.components.SwitchRow
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.core.auth.Ability
import com.droidhands.core.auth.AppAccessMode
import com.droidhands.core.auth.GrantDraft
import com.droidhands.core.auth.GrantMode

/**
 * The grant ("Give <agent> access"): Everything, or Only what I choose (apps and abilities), the one
 * "ask me before it sends messages, calls or pays" switch and the never-allow line. One stateless run of list
 * sections, to be placed inside the caller's scrolling column; used by the pairing screen, the manual token
 * form and Agents > (agent) > Access, so all three look and behave the same. [title] heads the first section
 * (null when the page title already says it).
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
    title: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ListSection(title = title) {
                Column(Modifier.selectableGroup()) {
                    GrantChoiceRow(
                        title = GrantPresentation.EVERYTHING,
                        description = GrantPresentation.everythingDescription(),
                        selected = grant.mode == GrantMode.EVERYTHING,
                        enabled = enabled,
                        onSelect = { onChange(grant.withMode(GrantMode.EVERYTHING)) },
                    )
                    ListDivider(ControlRowInset)
                    GrantChoiceRow(
                        title = GrantPresentation.CHOSEN,
                        description = GrantPresentation.CHOSEN_DESCRIPTION,
                        selected = grant.mode == GrantMode.CHOSEN,
                        enabled = enabled,
                        onSelect = { onChange(grant.withMode(GrantMode.CHOSEN)) },
                    )
                }
            }
            NeverAllowFooter(neverAllowCount, enabled, onChangeNeverAllow)
        }
        if (grant.mode == GrantMode.CHOSEN) {
            ChosenSections(grant, original, appLabels, enabled, onChange, onChooseApps)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ListSection {
                SwitchRow(
                    title = GrantPresentation.ASK_SWITCH,
                    subtitle = if (grant.asksBeforeSensitive) GrantPresentation.ASK_SWITCH_ON else GrantPresentation.ASK_SWITCH_OFF,
                    checked = grant.asksBeforeSensitive,
                    onCheckedChange = { onChange(grant.withAsk(it)) },
                    enabled = enabled,
                    leading = { IconTile(Icons.Filled.Notifications, Accents.Orange) },
                )
            }
            if (grant.asksBeforeEveryAction) {
                Notice(
                    GrantPresentation.EVERY_ACTION_NOTICE,
                    kind = NoticeKind.INFO,
                    action = { TertiaryButton(GrantPresentation.NARROW_ASKING, onClick = { onChange(grant.withAsk(true)) }, enabled = enabled) },
                )
            }
        }
    }
}

/** Apps and abilities of "Only what I choose", with an older token's risk cap above them. */
@Composable
private fun ChosenSections(
    grant: GrantDraft,
    original: GrantDraft?,
    appLabels: Map<String, String>,
    enabled: Boolean,
    onChange: (GrantDraft) -> Unit,
    onChooseApps: () -> Unit,
) {
    GrantPresentation.capNotice(grant.maxRisk)?.let { notice ->
        Notice(
            notice,
            kind = NoticeKind.WARNING,
            action = { TertiaryButton(GrantPresentation.REMOVE_CAP, onClick = { onChange(grant.withoutRiskCap()) }, enabled = enabled) },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val legacyBlocklist = original?.apps?.takeIf { it.mode == AppAccessMode.BLOCKLIST }
        ListSection(title = GrantPresentation.APPS_HEADER) {
            Column(Modifier.selectableGroup()) {
                GrantChoiceRow(GrantPresentation.ALL_APPS, null, grant.apps.mode == AppAccessMode.ALL, enabled) {
                    onChange(grant.withAppMode(AppAccessMode.ALL))
                }
                ListDivider(ControlRowInset)
                GrantChoiceRow(GrantPresentation.CHOSEN_APPS, null, grant.apps.mode == AppAccessMode.ALLOWLIST, enabled) {
                    onChange(grant.withAppMode(AppAccessMode.ALLOWLIST))
                }
                if (legacyBlocklist != null) {
                    ListDivider(ControlRowInset)
                    GrantChoiceRow(
                        GrantPresentation.blocklistOption(legacyBlocklist.packages.size), null,
                        grant.apps.mode == AppAccessMode.BLOCKLIST, enabled,
                    ) { onChange(grant.copy(apps = legacyBlocklist)) }
                }
            }
            if (grant.apps.mode == AppAccessMode.ALLOWLIST) {
                ListDivider()
                ListRow(
                    title = GrantPresentation.CHOOSE_APPS,
                    subtitle = GrantPresentation.chosenAppsLine(grant.apps.packages, appLabels),
                    leading = { IconTile(AppsIcon, Accents.Blue) },
                    onClick = onChooseApps,
                    enabled = enabled,
                )
            }
        }
        if (grant.apps.mode == AppAccessMode.ALLOWLIST) {
            grant.warning?.let { Notice(it, kind = NoticeKind.WARNING) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val full = grant.abilities
        val partial = grant.partialAbilities
        ListSection(title = GrantPresentation.ABILITIES_HEADER) {
            Ability.offered(Edition.PHONE_USE).forEachIndexed { index, ability ->
                if (index > 0) ListDivider(ControlRowInset)
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
        }
        grant.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = CardPadding),
            )
        }
    }
}

/**
 * The never-allow line under the Everything / Only what I choose choice, styled as that section's footer,
 * with "Change list" when the caller can open the list.
 */
@Composable
private fun NeverAllowFooter(count: Int, enabled: Boolean, onChangeNeverAllow: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = CardPadding), verticalAlignment = Alignment.CenterVertically) {
        Text(
            GrantPresentation.neverAllowLine(count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onChangeNeverAllow != null) {
            TertiaryButton(GrantPresentation.CHANGE_NEVER_ALLOW, onClick = onChangeNeverAllow, enabled = enabled)
        }
    }
}

/** An ability with a three-state checkbox (partly granted on older tokens), laid out like a list row. */
@Composable
private fun AbilityRow(ability: Ability, state: ToggleableState, partly: String?, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .triStateToggleable(state = state, enabled = enabled, role = Role.Checkbox, onClick = onToggle)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = CardPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row handles clicks, so the checkbox itself is not separately focusable.
        TriStateCheckbox(state = state, onClick = null, enabled = enabled)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(ability.label, style = MaterialTheme.typography.bodyLarge)
            Text(GrantPresentation.abilityDescription(ability), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (partly != null) {
                Text(partly, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** A radio row with an optional explanation, as a list row (the row is the click target). */
@Composable
internal fun GrantChoiceRow(title: String, description: String?, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    ListRow(
        title = title,
        subtitle = description,
        enabled = enabled,
        // The row handles clicks, so the button itself is not separately focusable.
        leading = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    )
}

/** Divider inset that lines up with the text of rows led by a checkbox or radio button (24dp). */
private val ControlRowInset = CardPadding + 24.dp + 14.dp
