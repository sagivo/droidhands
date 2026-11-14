package dev.phonebridge.android.ui.setup

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.phonebridge.android.ui.components.LocalSnackbarHostState
import dev.phonebridge.android.ui.components.SystemIntents
import dev.phonebridge.android.ui.components.findActivity
import kotlinx.coroutines.launch

/** What a setup row's button does; shared by Settings > Permissions & access and onboarding. */
@Stable
internal class SetupActions(
    /** Fixes [SetupItem]: asks for its permissions or opens the right system settings screen. */
    val onAction: (SetupItem) -> Unit,
    /** Launches the system permission dialogs for [permissions] (Android shows them one after another). */
    val requestPermissions: (List<String>) -> Unit,
    val openAppInfo: () -> Unit,
)

/**
 * Provides [SetupActions] to [content] and hosts what they need: the permission launcher (results
 * refresh the checklist) and the "Allow location all the time?" explanation dialog.
 */
@Composable
internal fun WithSetupActions(vm: SetupViewModel, content: @Composable (SetupActions) -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    var backgroundLocationDialog by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refresh(activity)
    }

    val actions = remember(context, vm, launcher, snackbar, scope) {
        fun open(vararg intents: Intent) {
            if (!SystemIntents.launch(context, *intents)) {
                scope.launch { snackbar.showSnackbar("Couldn't open that settings screen. Open Settings > Apps > PhoneBridge instead.") }
            }
        }

        fun request(permissions: List<String>) {
            if (permissions.isEmpty()) return
            vm.onPermissionsRequested(permissions)
            try {
                launcher.launch(permissions.toTypedArray())
            } catch (_: ActivityNotFoundException) {
                open(SystemIntents.appDetails(context))
            }
        }

        SetupActions(
            onAction = { item ->
                when {
                    item.id == SetupLogic.NOTIFICATIONS && (item.blocked || item.permissions.isEmpty()) ->
                        open(SystemIntents.appNotificationSettings(context), SystemIntents.appDetails(context))
                    item.blocked -> open(SystemIntents.appDetails(context))
                    item.id == SetupLogic.BACKGROUND_LOCATION -> backgroundLocationDialog = true
                    item.permissions.isNotEmpty() -> request(item.permissions)
                    else -> open(*specialAccessIntents(context, item.id))
                }
            },
            requestPermissions = ::request,
            openAppInfo = { open(SystemIntents.appDetails(context)) },
        )
    }

    content(actions)

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
                    actions.requestPermissions(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { backgroundLocationDialog = false }) { Text("Not now") } },
        )
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
