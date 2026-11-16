package com.droidhands.android.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidhands.android.ui.components.AppCard
import com.droidhands.android.ui.components.IconBadge
import com.droidhands.android.ui.components.IconRowInset
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.ScreenScaffold
import com.droidhands.android.ui.components.padded
import com.droidhands.android.ui.theme.statusColors

/**
 * Settings > Permissions & access: a progress card, the "Allow restricted settings" steps when Android
 * blocks accessibility for this install, then the Essential and Optional runtime permissions and
 * special accesses, one row each with its fix-it action.
 */
@Composable
fun SetupScreen(vm: SetupViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    WithSetupActions(vm) { actions ->
        ScreenScaffold(title = "Permissions & access", onBack = onBack) { padding ->
            LazyColumn(
                contentPadding = padding.padded(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item(key = "header") { SetupHeader(state) }
                if (state.loaded) {
                    // The accessibility row of an app installed from an APK file: the steps come first, with their own
                    // Open Accessibility button for step 1.
                    state.items.firstOrNull { it.restrictedSettingsGuide }?.let { guided ->
                        item(key = "restricted-guide") {
                            AppCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                                RestrictedSettingsGuide(actions.openAppInfo, onOpenAccessibility = { actions.onAction(guided) })
                            }
                        }
                    }
                    val (essential, optional) = state.items.partition { it.essential }
                    item(key = "essential") { SetupSection("Essential", essential, actions) }
                    item(key = "optional") {
                        SetupSection(
                            "Optional",
                            optional,
                            actions,
                            footer = "Agents are told exactly what's missing when a tool can't run, so grant these only " +
                                "when you need them.",
                        )
                    }
                }
            }
        }
    }
}

/** One group of checklist rows. */
@Composable
private fun SetupSection(title: String, items: List<SetupItem>, actions: SetupActions, footer: String? = null) {
    if (items.isEmpty()) return
    ListSection(title = title, footer = footer) {
        items.forEachIndexed { index, item ->
            if (index > 0) ListDivider(IconRowInset)
            SetupRow(item, actions.onAction, onOpenAppInfo = actions.openAppInfo)
        }
    }
}

/** The progress hero: "5 of 13 done", what is still essential, and a progress bar (green once essentials are done). */
@Composable
private fun SetupHeader(state: SetupUiState) {
    AppCard {
        if (!state.loaded) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            return@AppCard
        }
        val p = state.progress
        val colors = statusColors()
        val color = if (p.essentialComplete) colors.ok else colors.warning
        val summary = "${p.done} of ${p.total} done"
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (p.essentialComplete) Icons.Filled.CheckCircle else Icons.Filled.Warning, color)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(summary, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (p.essentialComplete) {
                        "Essentials done. Grant the rest when your agents need them."
                    } else {
                        "${p.essentialMissing.size} essential ${if (p.essentialMissing.size == 1) "item" else "items"} " +
                            "missing. Grant what your agents need."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        LinearProgressIndicator(
            progress = { p.fraction },
            color = color,
            trackColor = color.copy(alpha = 0.15f),
            gapSize = 0.dp,
            drawStopIndicator = {},
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .semantics { contentDescription = summary },
        )
    }
}
