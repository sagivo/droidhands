package dev.phonebridge.android.ui.setup

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.components.LocalSnackbarHostState
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.SystemIntents
import dev.phonebridge.android.ui.components.findActivity
import dev.phonebridge.android.ui.components.padded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Setup checklist: runtime permissions and special accesses, each with a fix-it action. */
@Composable
fun SetupScreen(vm: SetupViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    var backgroundLocationDialog by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refresh(activity)
    }

    fun open(vararg intents: Intent) {
        if (!SystemIntents.launch(context, *intents)) {
            scope.toast(snackbar, "Couldn't open that settings screen. Open Settings > Apps > PhoneBridge instead.")
        }
    }

    fun request(permissions: List<String>) {
        vm.onPermissionsRequested(permissions)
        try {
            permissionLauncher.launch(permissions.toTypedArray())
        } catch (_: ActivityNotFoundException) {
            open(SystemIntents.appDetails(context))
        }
    }

    val onAction: (SetupItem) -> Unit = { item ->
        when {
            item.id == SetupLogic.NOTIFICATIONS && (item.blocked || item.permissions.isEmpty()) ->
                open(SystemIntents.appNotificationSettings(context), SystemIntents.appDetails(context))
            item.blocked -> open(SystemIntents.appDetails(context))
            item.id == SetupLogic.BACKGROUND_LOCATION -> backgroundLocationDialog = true
            item.permissions.isNotEmpty() -> request(item.permissions)
            else -> open(*specialAccessIntents(context, item.id))
        }
    }

    ScreenScaffold(title = "Setup") { padding ->
        LazyColumn(
            contentPadding = padding.padded(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item(key = "header") { SetupHeader(state) }
            if (state.loaded) {
                val (essential, optional) = state.items.partition { it.essential }
                item(key = "essential") { SectionHeader("Essential") }
                items(essential, key = { it.id }) { SetupRow(it, onAction, onOpenAppInfo = { open(SystemIntents.appDetails(context)) }) }
                item(key = "optional") { SectionHeader("Optional") }
                items(optional, key = { it.id }) { SetupRow(it, onAction, onOpenAppInfo = { open(SystemIntents.appDetails(context)) }) }
            }
        }
    }

    if (backgroundLocationDialog) {
        AlertDialog(
            onDismissRequest = { backgroundLocationDialog = false },
            title = { Text("Allow location all the time?") },
            text = {
                Text(
                    "Agents ask for the location while PhoneBridge is in the background. On the next screen choose " +
                        "\"Allow all the time\". The location is read only when an agent with the location scope " +
                        "asks, and every request is logged in Activity.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    backgroundLocationDialog = false
                    request(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { backgroundLocationDialog = false }) { Text("Not now") } },
        )
    }
}

@Composable
private fun SetupHeader(state: SetupUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Grant what your agents need. Each item says which capabilities it unlocks; agents are told exactly " +
                "what's missing when a tool can't run.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.loaded) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else {
            val p = state.progress
            val summary = "${p.done} of ${p.total} done"
            Text(summary, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { p.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = summary },
            )
        }
    }
}

/** System screen(s) for a special access, most specific first (later ones are fallbacks). */
private fun specialAccessIntents(context: Context, id: String): Array<Intent> = when (id) {
    SetupLogic.ACCESSIBILITY -> arrayOf(SystemIntents.accessibilitySettings(context))
    SetupLogic.NOTIFICATION_ACCESS ->
        arrayOf(SystemIntents.notificationListenerDetail(context), SystemIntents.notificationListenerList())
    SetupLogic.BATTERY ->
        arrayOf(SystemIntents.requestIgnoreBatteryOptimizations(context), SystemIntents.batteryOptimizationList())
    SetupLogic.OVERLAY -> arrayOf(SystemIntents.overlaySettings(context), SystemIntents.appDetails(context))
    SetupLogic.DND -> arrayOf(SystemIntents.dndAccessSettings())
    else -> arrayOf(SystemIntents.appDetails(context))
}

private fun CoroutineScope.toast(snackbar: SnackbarHostState, message: String) {
    launch { snackbar.showSnackbar(message) }
}
