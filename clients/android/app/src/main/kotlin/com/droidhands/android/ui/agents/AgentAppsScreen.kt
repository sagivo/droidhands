package com.droidhands.android.ui.agents

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidhands.android.ui.Destination
import com.droidhands.android.ui.MainActivity
import com.droidhands.android.ui.components.BottomActionBar
import com.droidhands.android.ui.components.ButtonSize
import com.droidhands.android.ui.components.ConfirmDialog
import com.droidhands.android.ui.components.PrimaryButton
import com.droidhands.android.ui.components.ScreenScaffold
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.android.ui.components.padded
import com.droidhands.android.ui.grant.GrantAppPicker
import com.droidhands.android.ui.grant.GrantApps
import com.droidhands.android.ui.grant.GrantEditor
import com.droidhands.android.ui.grant.GrantPresentation

/**
 * Agents > (agent) > Access: the same grant editor as at pairing, pre-filled with the agent's current access
 * (older settings such as a blocklist, a read-only limit or "ask before every action" are shown and kept until
 * changed). Save applies it at once to the agent's next requests. The app picker opens in place of the editor.
 * (The route is still `agent-apps`, from when this page only picked apps.)
 */
@Composable
fun AgentAppsScreen(vm: AgentsViewModel, tokenId: String, onBack: () -> Unit) {
    LaunchedEffect(tokenId) {
        vm.openGrant(tokenId)
        vm.loadAppActions()
    }
    val apps by vm.appActions.collectAsStateWithLifecycle()
    val neverAllow by vm.neverAllowApps.collectAsStateWithLifecycle()
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val record = tokens.firstOrNull { it.id == tokenId }
    val grant = vm.grant?.takeIf { vm.grantId == tokenId }
    var pickingApps by rememberSaveable { mutableStateOf(false) }
    var appQuery by rememberSaveable { mutableStateOf("") }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val dirty = vm.isGrantDirty()
    val leave: () -> Unit = {
        if (dirty) {
            confirmDiscard = true
        } else {
            vm.closeGrant()
            onBack()
        }
    }
    BackHandler { if (pickingApps) pickingApps = false else leave() }

    if (record == null || grant == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    if (pickingApps) {
        ScreenScaffold(
            title = "Apps ${record.name} may use",
            onBack = { pickingApps = false },
            actions = { TertiaryButton("Done", onClick = { pickingApps = false }) },
        ) { padding ->
            GrantAppPicker(
                apps = apps,
                selected = grant.apps.packages,
                neverAllow = neverAllow,
                query = appQuery,
                onQueryChange = { appQuery = it },
                onToggle = { pkg -> vm.editGrant(grant.toggleApp(pkg)) },
                contentPadding = padding.padded(horizontal = 0.dp),
            )
        }
        return
    }

    ScreenScaffold(
        title = GrantPresentation.title(record.name),
        onBack = leave,
        bottomBar = {
            if (dirty || vm.savingGrant) {
                BottomActionBar {
                    TertiaryButton("Discard", onClick = { vm.editGrant(vm.grantOriginal ?: grant) }, enabled = !vm.savingGrant)
                    PrimaryButton(
                        "Save",
                        onClick = { vm.saveGrant(onBack) },
                        enabled = grant.isValid,
                        loading = vm.savingGrant,
                        size = ButtonSize.LARGE,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(padding.padded()),
        ) {
            GrantEditor(
                grant = grant,
                original = vm.grantOriginal,
                appLabels = GrantApps.labels(apps),
                neverAllowCount = neverAllow.size,
                enabled = !vm.savingGrant,
                onChange = vm::editGrant,
                onChooseApps = { pickingApps = true },
                onChangeNeverAllow = { context.startActivity(MainActivity.intent(context, Destination.NEVER_ALLOW_ROUTE)) },
            )
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            text = "Your changes to ${record.name}'s access haven't been saved.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            destructive = true,
            onConfirm = {
                confirmDiscard = false
                vm.closeGrant()
                onBack()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}
