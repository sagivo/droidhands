package dev.phonebridge.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.apps.AppPickerList
import dev.phonebridge.android.ui.components.BottomActionBar
import dev.phonebridge.android.ui.components.ConfirmDialog
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.PrimaryButton
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SecondaryButton
import dev.phonebridge.android.ui.components.TertiaryButton
import dev.phonebridge.android.ui.components.padded

/**
 * Settings > Security > Never-allow apps: apps no agent can see or operate, whatever its own app
 * access says. Suggested apps (banking, payments, passwords, authenticators) come first.
 * Changes are saved with the bottom bar.
 */
@Composable
fun NeverAllowScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.loadApps() }
    val saved by vm.neverAllowApps.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val suggested by vm.suggestedApps.collectAsStateWithLifecycle()
    var selection by rememberSaveable { mutableStateOf(ArrayList(saved)) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val selected = selection.toSet()
    val dirty = selected != saved
    val missingSuggestions = suggested - selected

    BackHandler(enabled = dirty) { confirmLeave = true }

    ScreenScaffold(
        title = "Never-allow apps",
        onBack = { if (dirty) confirmLeave = true else onBack() },
        bottomBar = {
            if (dirty) {
                BottomActionBar {
                    TertiaryButton("Discard", onClick = { selection = ArrayList(saved) })
                    PrimaryButton("Save", onClick = {
                        vm.saveNeverAllowApps(selected)
                        onBack()
                    })
                }
            }
        },
    ) { padding ->
        AppPickerList(
            apps = apps,
            selected = selected,
            query = query,
            onQueryChange = { query = it },
            onToggle = { pkg -> selection = ArrayList(if (pkg in selected) selected - pkg else selected + pkg) },
            contentPadding = padding.padded(horizontal = 0.dp),
            suggested = suggested,
            header = {
                item(key = "intro") {
                    Notice(
                        "No agent can read, open or operate these apps, or see their notifications, whatever its own " +
                            "settings say. PhoneBridge itself is always protected.",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        action = if (missingSuggestions.isNotEmpty()) {
                            {
                                SecondaryButton(
                                    "Add all ${missingSuggestions.size} suggested",
                                    onClick = { selection = ArrayList(selected + missingSuggestions) },
                                    icon = Icons.Filled.Add,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            },
        )
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Discard changes?",
            text = "Your changes to the never-allow list haven't been saved.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            destructive = true,
            onConfirm = onBack,
            onDismiss = { confirmLeave = false },
        )
    }
}
