package com.droidhands.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidhands.android.ui.components.Accents
import com.droidhands.android.ui.components.AppCard
import com.droidhands.android.ui.components.IconBadge
import com.droidhands.android.ui.components.IconRowInset
import com.droidhands.android.ui.components.IconTile
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.Notice
import com.droidhands.android.ui.components.NoticeKind
import com.droidhands.android.ui.components.PrimaryButton
import com.droidhands.android.ui.components.SecondaryButton
import com.droidhands.android.ui.components.StatusPill
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.android.ui.setup.RestrictedSettings
import com.droidhands.android.ui.setup.RestrictedSettingsGuide
import com.droidhands.android.ui.setup.RestrictedSettingsHelp
import com.droidhands.android.ui.setup.SetupActions
import com.droidhands.android.ui.setup.SetupItem
import com.droidhands.android.ui.setup.SetupLogic
import com.droidhands.android.ui.setup.SetupStatus
import com.droidhands.android.ui.setup.SetupViewModel
import com.droidhands.android.ui.setup.WithSetupActions
import com.droidhands.android.ui.theme.statusColors

/**
 * Step 2: "Grant all" asks for every runtime permission in one go, then the special accesses are
 * walked one at a time: one tap opens the right system screen, and when the user comes back with
 * it granted, the next one is highlighted automatically. Below, every permission is one row with
 * Grant or a green check. Everything can be skipped.
 */
@Composable
internal fun PermissionsStep(setup: SetupViewModel) {
    val state by setup.state.collectAsStateWithLifecycle()
    var skipped by rememberSaveable { mutableStateOf(listOf<String>()) }
    var custom by rememberSaveable { mutableStateOf(false) }
    StepHeader(
        Icons.Filled.Lock,
        "Allow what your agents need",
        "Each permission unlocks specific abilities. You can change everything later in Settings.",
    )
    if (!state.loaded) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.padding(16.dp)) }
        return
    }
    val restricted = state.restrictedSettings
    WithSetupActions(setup) { actions ->
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // Installed from the web on Android 13+: SMS (Android 15), notification access and accessibility need
            // "Allow restricted settings" first, so the steps come before anything Android would refuse.
            if (restricted.guideFirst) {
                val accessibility = state.items.firstOrNull { it.id == SetupLogic.ACCESSIBILITY }
                RestrictedSettingsCard(actions.openAppInfo, restricted.unlocks, accessibility?.let { { actions.onAction(it) } })
            }
            if (!custom) {
                GrantAllSection(state.items, restricted, actions, onCustom = { custom = true })
            } else {
                TertiaryButton("Back to Grant all", onClick = { custom = false })
                RuntimeSection(state.items, restricted, actions)
            }
            val next = OnboardingFlow.nextSpecialAccess(state.items, skipped.toSet())
            if (next != null) {
                // Restricted settings not shown above yet: accessibility needs them first.
                if (next.restrictedSettingsGuide && !restricted.guideFirst) RestrictedSettingsCard(actions.openAppInfo)
                NextAccessCard(next, actions, restricted.guideFirst, onSkip = { skipped = skipped + next.id })
            }
            if (OnboardingFlow.essentialsDone(state.items)) {
                Notice("The essentials are done. Continue whenever you're ready.")
            }
            if (custom) SpecialAccessSection(state.items, actions)
        }
    }
}

/** The default view: one big "Grant all" and a small "Custom" that opens the per-permission list. */
@Composable
private fun GrantAllSection(items: List<SetupItem>, restricted: RestrictedSettings.Advice, actions: SetupActions, onCustom: () -> Unit) {
    val toRequest = OnboardingFlow.runtimeToRequest(items)
    val blocked = OnboardingFlow.blockedRuntime(items)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (toRequest.isNotEmpty()) {
            PrimaryButton("Grant all", onClick = { actions.requestPermissions(toRequest) }, fullWidth = true)
        }
        if (toRequest.isEmpty() && blocked.isNotEmpty()) {
            Notice(
                RestrictedSettings.blockedNotice(blocked, restricted),
                kind = NoticeKind.WARNING,
                action = { SecondaryButton("Open App info", onClick = actions.openAppInfo) },
            )
        }
        TertiaryButton("Custom", onClick = onCustom)
    }
}

/** Runtime permissions (Android's own dialogs): one row each, with "Grant all" in the header. */
@Composable
private fun RuntimeSection(items: List<SetupItem>, restricted: RestrictedSettings.Advice, actions: SetupActions) {
    val toRequest = OnboardingFlow.runtimeToRequest(items)
    val blocked = OnboardingFlow.blockedRuntime(items)
    val runtime = items.filterNot(OnboardingFlow::isSpecialAccess)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListSection(
            title = "App permissions",
            footer = when {
                toRequest.isNotEmpty() -> "Notifications are for the Allow/Deny prompts. Android asks about each one."
                blocked.isEmpty() -> "App permissions granted."
                else -> null
            },
            action = if (toRequest.isNotEmpty()) {
                { TertiaryButton("Grant all", onClick = { actions.requestPermissions(toRequest) }) }
            } else {
                null
            },
        ) {
            runtime.forEachIndexed { index, item ->
                if (index > 0) ListDivider(IconRowInset)
                PermissionRow(item, actions)
            }
        }
        if (toRequest.isEmpty() && blocked.isNotEmpty()) {
            Notice(
                RestrictedSettings.blockedNotice(blocked, restricted),
                kind = NoticeKind.WARNING,
                action = { SecondaryButton("Open App info", onClick = actions.openAppInfo) },
            )
        }
    }
}

/** Special accesses (system settings screens), in the order the wizard walks them, with progress below. */
@Composable
private fun SpecialAccessSection(items: List<SetupItem>, actions: SetupActions) {
    val byId = items.associateBy { it.id }
    val special = OnboardingFlow.SPECIAL_ACCESS_ORDER.mapNotNull { byId[it] }
    if (special.isEmpty()) return
    val (done, total) = OnboardingFlow.specialProgress(items)
    ListSection(
        title = "Special access",
        footer = if (done == total) {
            "All special accesses granted."
        } else {
            "$done of $total granted. Agents are told exactly what's missing."
        },
    ) {
        special.forEachIndexed { index, item ->
            if (index > 0) ListDivider(IconRowInset)
            PermissionRow(item, actions)
        }
    }
}

/** One permission: its tile, title and state, and Grant (or a green check once granted). */
@Composable
private fun PermissionRow(item: SetupItem, actions: SetupActions) {
    val done = item.status == SetupStatus.DONE
    val (icon, color) = permissionIcon(item.id)
    val state = if (item.status == SetupStatus.MISSING && !item.blocked) item.description else SetupLogic.statusLabel(item.status, item.blocked)
    ListRow(
        title = item.title,
        subtitle = state + if (!item.essential && !done) " · Optional" else "",
        subtitleMaxLines = 2,
        leading = { IconTile(icon, color) },
        trailing = {
            val label = SetupLogic.actionLabel(item)
            when {
                done -> IconBadge(
                    Icons.Filled.Check,
                    statusColors().ok,
                    size = 28.dp,
                    modifier = Modifier.semantics { contentDescription = "Granted" },
                )
                label != null -> SecondaryButton(
                    if (item.blocked) "App info" else "Grant",
                    onClick = { actions.onAction(item) },
                    modifier = Modifier.semantics { contentDescription = "$label: ${item.title}" },
                )
            }
        },
    )
}

/** The special access to do next, highlighted; it moves on by itself once granted. */
@Composable
private fun NextAccessCard(item: SetupItem, actions: SetupActions, restrictedGuideShown: Boolean, onSkip: () -> Unit) {
    val (icon, color) = permissionIcon(item.id)
    AppCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, color, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Next", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(item.title, style = MaterialTheme.typography.titleMedium)
            }
            if (!item.essential) {
                Spacer(Modifier.width(8.dp))
                StatusPill("Optional", statusColors().off)
            }
        }
        Text(item.description, style = MaterialTheme.typography.bodyMedium)
        Text(
            "Unlocks: ${item.unlocks}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (item.id == SetupLogic.ACCESSIBILITY && item.status == SetupStatus.PENDING) {
            Notice(
                "Turned on, but Android hasn't connected the service yet. If this doesn't change in a few seconds, " +
                    "turn DroidHands off and on again in Accessibility settings.",
                kind = NoticeKind.WARNING,
            )
        }
        if (item.id == SetupLogic.ACCESSIBILITY) {
            Notice("On the next screen tap \"Installed apps\" (on some phones \"Downloaded apps\") > DroidHands, then turn it on.")
        }
        // The guided card above already covers it.
        if (item.restrictedSettingsHelp && !item.restrictedSettingsGuide && !restrictedGuideShown) {
            RestrictedSettingsHelp(actions.openAppInfo)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TertiaryButton("Skip", onClick = onSkip)
            PrimaryButton(SetupLogic.actionLabel(item) ?: "Open settings", onClick = { actions.onAction(item) })
        }
    }
}

/**
 * The "Allow restricted settings" steps as their own card: first on the step ([onOpenAccessibility] set, for step 1),
 * or right above the accessibility card.
 */
@Composable
private fun RestrictedSettingsCard(onOpenAppInfo: () -> Unit, unlocks: String = "accessibility", onOpenAccessibility: (() -> Unit)? = null) {
    AppCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        RestrictedSettingsGuide(onOpenAppInfo, Modifier.fillMaxWidth(), unlocks, onOpenAccessibility)
    }
}

/** Icon and tile color of a checklist row, so the same permission looks the same everywhere in the wizard. */
private fun permissionIcon(id: String): Pair<ImageVector, Color> = when (id) {
    SetupLogic.NOTIFICATIONS -> Icons.Filled.Notifications to Accents.Indigo
    SetupLogic.CONTACTS -> Icons.Filled.Person to Accents.Blue
    RestrictedSettings.SMS_ID -> Icons.Filled.Email to Accents.Teal
    "phone" -> Icons.Filled.Call to Accents.Green
    "calendar" -> Icons.Filled.DateRange to Accents.Pink
    SetupLogic.LOCATION, SetupLogic.BACKGROUND_LOCATION -> Icons.Filled.LocationOn to Accents.Blue
    SetupLogic.ACCESSIBILITY -> Icons.Filled.Build to Accents.Indigo
    SetupLogic.NOTIFICATION_ACCESS -> Icons.Filled.Notifications to Accents.Purple
    SetupLogic.BATTERY -> Icons.Filled.Refresh to Accents.Orange
    SetupLogic.OVERLAY -> Icons.Filled.AccountBox to Accents.Teal
    SetupLogic.DND -> Icons.Filled.Lock to Accents.Purple
    else -> Icons.Filled.Settings to Accents.Gray
}
