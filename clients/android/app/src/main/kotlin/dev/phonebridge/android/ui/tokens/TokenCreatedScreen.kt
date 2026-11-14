package dev.phonebridge.android.ui.tokens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.graph.LocalServerStatus
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.components.BottomActionBar
import dev.phonebridge.android.ui.components.CodeBlock
import dev.phonebridge.android.ui.components.ConfirmDialog
import dev.phonebridge.android.ui.components.CopyableCode
import dev.phonebridge.android.ui.components.LocalSnackbarHostState
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.SecureWindowEffect
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.grant.GrantPresentation
import dev.phonebridge.android.ui.settings.RelayUrls
import dev.phonebridge.core.auth.IssuedToken
import dev.phonebridge.core.transport.RelayStatus
import kotlinx.coroutines.launch

/**
 * One-time display of a new token with ready-to-paste client configs. The window is FLAG_SECURE
 * while shown; Done (or back, after confirming) drops the plaintext.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TokenCreatedScreen(token: IssuedToken, main: MainViewModel, onDone: () -> Unit) {
    SecureWindowEffect()
    val settings by main.settings.collectAsStateWithLifecycle()
    val relay by main.relayStatus.collectAsStateWithLifecycle()
    val local by main.localServerStatus.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    val endpoints = remember(settings, relay, local) {
        ConnectionSnippets.endpoints(
            relayEnabled = settings.relayEnabled,
            relayUrl = settings.relayUrl,
            relayConnectedUrl = (relay as? RelayStatus.Connected)?.mcpUrl,
            // A server listening on the tailnet only isn't reachable over USB (adb forward).
            localServerEnabled = settings.localServerEnabled && settings.localServerBind.reachesLoopback,
            localServerPort = settings.localServerPort,
            localUrls = (local as? LocalServerStatus.Running)?.urls.orEmpty(),
        )
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val endpoint = endpoints.getOrNull(selected) ?: endpoints.firstOrNull()
    val snippets = remember(endpoint, token.plaintext, settings.localServerPort) {
        endpoint?.let { ConnectionSnippets.forEndpoint(it, token.plaintext, settings.localServerPort) }.orEmpty()
    }

    var copiedOnce by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (copiedOnce) {
            onDone()
        } else {
            confirmLeave = true
        }
    }
    val markCopied: () -> Unit = { copiedOnce = true }
    val shareFailed: () -> Unit = { scope.launch { snackbar.showSnackbar("No app can receive shared text.") } }
    BackHandler(onBack = leave)

    ScreenScaffold(
        title = "Token created",
        bottomBar = { BottomActionBar { Button(onClick = leave) { Text("Done") } } },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "warning") {
                Notice(
                    title = "Copy it now: it's shown only once",
                    text = "PhoneBridge keeps only a hash of the token. If you lose it, revoke it and create a new one. " +
                        "Treat it like a password: anyone holding it can use this phone within its access.",
                    kind = NoticeKind.WARNING,
                )
            }
            item(key = "token") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("\"${token.record.name}\"")
                    Text(
                        "${GrantPresentation.shortSummary(token.record)} · " +
                            TokenPresentation.expiryText(token.record.expiresAt, System.currentTimeMillis()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CopyableCode(token.plaintext, copyLabel = "Token", sensitive = true, onCopied = markCopied)
                }
            }
            item(key = "endpoint") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Connect an agent")
                    if (endpoint == null) {
                        Notice(
                            "No endpoint is set up yet. Turn on the relay or the local server in Settings, then use the " +
                                "token with the header below. The token works with both.",
                        )
                        CopyableCode(
                            "Authorization: Bearer ${token.plaintext}",
                            copyLabel = "Header",
                            sensitive = true,
                            onCopied = markCopied,
                        )
                    } else {
                        if (endpoints.size > 1) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                endpoints.forEachIndexed { index, ep ->
                                    FilterChip(
                                        selected = ep == endpoint,
                                        onClick = { selected = index },
                                        label = { Text(ep.label) },
                                    )
                                }
                            }
                        } else {
                            Text(endpoint.label, style = MaterialTheme.typography.titleSmall)
                        }
                        CodeBlock(endpoint.url)
                        if (ConnectionSnippets.isInsecure(endpoint.url)) {
                            Notice(RelayUrls.INSECURE_WARNING, kind = NoticeKind.DANGER)
                        }
                        if (endpoint.kind == EndpointKind.RELAY && relay !is RelayStatus.Connected) {
                            Notice("The relay isn't connected right now. The setup below works once it is.", kind = NoticeKind.WARNING)
                        }
                    }
                }
            }
            items(snippets, key = { "snippet-${it.id}" }) { snippet ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(snippet.title, style = MaterialTheme.typography.titleSmall)
                        Text(snippet.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val secret = token.plaintext in snippet.text
                        CopyableCode(
                            snippet.text,
                            copyLabel = snippet.title,
                            sensitive = secret,
                            shareTitle = "Share setup for ${snippet.title}",
                            onShareFailed = shareFailed,
                            onCopied = { if (secret) markCopied() },
                        )
                    }
                }
            }
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = "Leave without copying?",
            text = "You haven't copied or shared the token. It can't be shown again; you'd have to create a new one.",
            confirmLabel = "Leave",
            dismissLabel = "Stay",
            destructive = true,
            onConfirm = onDone,
            onDismiss = { confirmLeave = false },
        )
    }
}
