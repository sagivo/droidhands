package com.droidhands.android.ui.deeplink

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidhands.android.settings.BridgeSettings
import com.droidhands.android.ui.components.ApprovalScreenEffect
import com.droidhands.android.ui.components.CodeBlock
import com.droidhands.android.ui.components.Notice
import com.droidhands.android.ui.components.NoticeKind
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.android.ui.components.TouchShieldEffect
import com.droidhands.android.ui.components.rememberArming
import com.droidhands.android.ui.settings.RelayUrls

/**
 * Asks before applying a `droidhands://configure` link: any website can open such a link, so it
 * is never applied without an explicit tap. Shows the full relay URL (the access key masked).
 *
 * Connecting hands the phone's identity to that relay, so the prompt is hardened like the approval
 * screens: Connect arms only after [com.droidhands.android.ui.components.ARM_DELAY_MS], taps through
 * other apps' overlays are dropped and overlays hidden ([TouchShieldEffect]), and agents' screen
 * control is paused while it shows ([ApprovalScreenEffect]).
 */
@Composable
fun ConfigureLinkDialog(
    result: ConfigureLinkResult,
    settings: BridgeSettings,
    onConfirm: (ConfigureRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    when (result) {
        is ConfigureLinkResult.Invalid -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Can't use this setup link") },
            text = { Text("${result.message} Open the setup page of your relay again, or enter the relay URL in Settings.") },
            confirmButton = { TertiaryButton("OK", onClick = onDismiss) },
        )
        is ConfigureLinkResult.Valid -> {
            val request = result.request
            val current = RelayUrls.normalize(settings.relayUrl)
            val replaces = current.isNotEmpty() && !current.equals(request.relayUrl, ignoreCase = true)
            val alreadySet = ConfigureLinks.isAlreadyApplied(settings, request)
            ApprovalScreenEffect()
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("Connect to relay ${request.host}?") },
                text = {
                    // Composed inside the dialog, so it shields the dialog's own window.
                    TouchShieldEffect()
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("A setup link wants to connect DroidHands to this relay:", style = MaterialTheme.typography.bodyMedium)
                        CodeBlock(request.displayLink)
                        Text(
                            "Continue only if you opened this link from your own relay's setup page. AI agents reach this " +
                                "phone through the relay you choose." +
                                if (request.accessKey != null) " The link includes the relay's access key." else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (replaces) {
                            Notice("This replaces your current relay ($current). Agents connected through it will need to reconnect.", kind = NoticeKind.WARNING)
                        }
                        if (alreadySet) Notice("DroidHands already uses this relay. Connecting again just reconnects.")
                        if (request.insecure) Notice(RelayUrls.INSECURE_WARNING, kind = NoticeKind.DANGER)
                    }
                },
                confirmButton = {
                    val arming = rememberArming(request)
                    TertiaryButton(
                        "Connect",
                        onClick = {
                            onDismiss()
                            onConfirm(request)
                        },
                        enabled = arming.armed,
                        modifier = arming.modifier,
                    )
                },
                dismissButton = { TertiaryButton("Cancel", onClick = onDismiss) },
            )
        }
    }
}
