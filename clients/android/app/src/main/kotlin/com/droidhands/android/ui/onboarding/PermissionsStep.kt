package com.droidhands.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
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
import com.droidhands.android.ui.components.CardPadding
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
 * Step 2: a clean four-row essential checklist (App permissions + Accessibility + Notification access +
 * Battery) where only the next undone item has a primary button. "Customize permissions" expands the full
 * per-permission list (including optional items). Everything can be skipped.
 */
@Composable
internal fun PermissionsStep(setup: SetupViewModel) {
    val state by setup.state.collectAsStateWithLifecycle()
    var custom by rememberSaveable { mutableStateOf(false) }
    val essentialsDone = state.loaded && OnboardingFlow.essentialsDone(state.items)
    if (essentialsDone) {
        StepHeader(
            Icons.Filled.CheckCircle,
            "Permissions ready",
            "You can change any permission later in Settings.",
            color = statusColors().ok,
        )
    } else {
        StepHeader(
            Icons.Filled.Lock,
            "Allow permissions",
            "Grant access so your agents can help. Change anytime in Settings.",
        )
    }
    if (!state.loaded) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.padding(16.dp)) }
        return
    }
    val restricted = state.restrictedSettings
    WithSetupActions(setup) { actions ->
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Installed from the web on Android 13+: SMS (Android 15), notification access and accessibility need
            // "Allow restricted settings" first, so the steps come before anything Android would refuse.
            if (restricted.guideFirst) {
                val accessibility = state.items.firstOrNull { it.id == SetupLogic.ACCESSIBILITY }
                RestrictedSettingsCard(actions.openAppInfo, restricted.unlocks, accessibility?.let { { actions.onAction(it) } })
            }
            if (!custom) {
                SimplePermissionsChecklist(state.items, restricted, actions)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TertiaryButton("Customize permissions", onClick = { custom = true })
                }
            } else {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TertiaryButton("Back to simple view", onClick = { custom = false })
                }
                RuntimeSection(state.items, restricted, actions)
                SpecialAccessSection(state.items, actions)
            }
        }
    }
}

/**
 * The default onboarding view: four essential rows in one list — bundled App permissions, then the three
 * essential special accesses. Exactly one undone row gets the primary button at a time.
 */
@Composable
private fun SimplePermissionsChecklist(
    items: List<SetupItem>,
    restricted: RestrictedSettings.Advice,
    actions: SetupActions,
) {
    val runtime = OnboardingFlow.runtimeSummary(items)
    val byId = items.associateBy { it.id }
    val special = OnboardingFlow.ESSENTIAL_SPECIAL_ORDER.mapNotNull { byId[it] }
    val nextId = OnboardingFlow.nextPermissionStepId(items)

    ListSection {
        AppPermissionsRow(
            summary = runtime,
            isNext = nextId == OnboardingFlow.APP_PERMISSIONS_ID,
            actions = actions,
        )
        special.forEach { item ->
            ListDivider(IconRowInset)
            SimpleSpecialRow(
                item = item,
                isNext = nextId == item.id,
                restrictedGuideShown = restricted.guideFirst,
                actions = actions,
            )
        }
    }
    if (runtime.toRequest.isEmpty() && runtime.blocked.isNotEmpty()) {
        Notice(
            RestrictedSettings.blockedNotice(runtime.blocked, restricted),
            kind = NoticeKind.WARNING,
            action = { SecondaryButton("Open App info", onClick = actions.openAppInfo) },
        )
    }
}

/** Row 1 of the simple checklist: requests every missing runtime permission in one tap. */
@Composable
private fun AppPermissionsRow(summary: RuntimeSummary, isNext: Boolean, actions: SetupActions) {
    val subtitle = when {
        summary.allDone -> "Granted"
        summary.granted > 0 -> "${summary.granted} of ${summary.total} granted"
        summary.toRequest.isEmpty() && summary.blocked.isNotEmpty() -> "Blocked — allow in App info"
        else -> "Notifications, contacts, messages, calls & location"
    }
    val actionLabel = when {
        summary.allDone -> null
        summary.toRequest.isNotEmpty() -> "Allow"
        summary.blocked.isNotEmpty() -> "App info"
        else -> null
    }
    val onAction: () -> Unit = {
        if (summary.toRequest.isNotEmpty()) actions.requestPermissions(summary.toRequest) else actions.openAppInfo()
    }
    ListRow(
        title = "App permissions",
        subtitle = subtitle,
        subtitleMaxLines = 2,
        leading = { IconTile(Icons.Filled.Notifications, Accents.Indigo) },
        trailing = {
            PermissionTrailing(
                done = summary.allDone,
                label = actionLabel,
                primary = isNext,
                description = "Allow app permissions",
                onClick = onAction,
            )
        },
    )
}

/** One essential special-access row with a concise inline hint only when it is the active step or pending. */
@Composable
private fun SimpleSpecialRow(
    item: SetupItem,
    isNext: Boolean,
    restrictedGuideShown: Boolean,
    actions: SetupActions,
) {
    val done = item.status == SetupStatus.DONE
    val (icon, color) = permissionIcon(item.id)
    val subtitle = when {
        done -> "Granted"
        item.id == SetupLogic.ACCESSIBILITY && item.status == SetupStatus.PENDING -> "Enabled, connecting…"
        else -> shortDescription(item.id) ?: item.description
    }
    val buttonLabel = if (done) null else "Allow"
    Column(Modifier.fillMaxWidth()) {
        ListRow(
            title = shortTitle(item.id) ?: item.title,
            subtitle = subtitle,
            subtitleMaxLines = 2,
            leading = { IconTile(icon, color) },
            trailing = {
                PermissionTrailing(
                    done = done,
                    label = buttonLabel,
                    primary = isNext,
                    description = "${SetupLogic.actionLabel(item) ?: "Allow"}: ${item.title}",
                    onClick = { actions.onAction(item) },
                )
            },
        )
        val pendingAccessibility = item.id == SetupLogic.ACCESSIBILITY && item.status == SetupStatus.PENDING
        val showAccessibilityHint = item.id == SetupLogic.ACCESSIBILITY && isNext && !done
        val showRestrictedHelp = item.restrictedSettingsHelp && !restrictedGuideShown && (isNext || pendingAccessibility)
        if (pendingAccessibility || showAccessibilityHint || showRestrictedHelp) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = IconRowInset, end = CardPadding, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pendingAccessibility) {
                    Notice(
                        "Turned on, but Android hasn't connected yet. Toggle DroidHands off and on again in Accessibility settings.",
                        kind = NoticeKind.WARNING,
                    )
                } else if (showAccessibilityHint) {
                    Notice("On the next screen tap \"Installed apps\" (or \"Downloaded apps\") > DroidHands, then turn it on.")
                }
                if (showRestrictedHelp) {
                    RestrictedSettingsHelp(actions.openAppInfo)
                }
            }
        }
    }
}

@Composable
private fun PermissionTrailing(
    done: Boolean,
    label: String?,
    primary: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    when {
        done -> IconBadge(
            Icons.Filled.Check,
            statusColors().ok,
            size = 28.dp,
            modifier = Modifier.semantics { contentDescription = "Granted" },
        )
        label != null && primary -> PrimaryButton(
            label,
            onClick = onClick,
            modifier = Modifier.semantics { contentDescription = description },
        )
        label != null -> SecondaryButton(
            label,
            onClick = onClick,
            modifier = Modifier.semantics { contentDescription = description },
        )
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
                toRequest.isNotEmpty() -> "Notifications are for Allow/Deny prompts. Android asks about each one."
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

/** Special accesses (system settings screens), in the order the wizard lists them, with progress below. */
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

/** One permission in the custom view: its tile, title and state, and Grant (or a green check once granted). */
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

/**
 * The "Allow restricted settings" steps as their own card: shown first for sideloaded Android 13+ installs.
 */
@Composable
private fun RestrictedSettingsCard(onOpenAppInfo: () -> Unit, unlocks: String = "accessibility", onOpenAccessibility: (() -> Unit)? = null) {
    AppCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        RestrictedSettingsGuide(onOpenAppInfo, Modifier.fillMaxWidth(), unlocks, onOpenAccessibility)
    }
}

private fun shortTitle(id: String): String? = when (id) {
    SetupLogic.ACCESSIBILITY -> "Accessibility"
    SetupLogic.NOTIFICATION_ACCESS -> "Notification access"
    SetupLogic.BATTERY -> "Unrestricted battery"
    else -> null
}

private fun shortDescription(id: String): String? = when (id) {
    SetupLogic.ACCESSIBILITY -> "Tap, type and use apps for you"
    SetupLogic.NOTIFICATION_ACCESS -> "Read and reply to notifications"
    SetupLogic.BATTERY -> "Stay reachable with the screen off"
    else -> null
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
