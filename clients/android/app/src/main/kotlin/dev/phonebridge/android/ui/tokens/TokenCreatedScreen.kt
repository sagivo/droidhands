package dev.phonebridge.android.ui.tokens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.graph.LocalServerStatus
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.components.BottomActionBar
import dev.phonebridge.android.ui.components.ButtonSize
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.ChoiceChip
import dev.phonebridge.android.ui.components.CodeBlock
import dev.phonebridge.android.ui.components.ConfirmDialog
import dev.phonebridge.android.ui.components.CopyableCode
import dev.phonebridge.android.ui.components.IconBadge
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.LocalSnackbarHostState
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.PrimaryButton
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SecureWindowEffect
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.settings.RelayUrls
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.core.auth.IssuedToken
import dev.phonebridge.core.transport.RelayStatus
import kotlinx.coroutines.launch

/**
 * One-time display of a new token with ready-to-paste client configs: a success hero, the
 * "shown only once" warning, the token itself, then the setup per client. The window is
 * FLAG_SECURE while shown; Done (or back, after confirming) drops the plaintext.
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
        bottomBar = { BottomActionBar { PrimaryButton("Done", onClick = leave, size = ButtonSize.LARGE, fullWidth = true) } },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "hero") {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconBadge(Icons.Filled.CheckCircle, statusColors().ok, size = 64.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "\"${token.record.name}\"",
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "${TokenPresentation.scopeSummary(token.record.scopes, token.record.maxRisk)} · " +
                            "${TokenPresentation.policyLabel(token.record.confirmPolicy)} · " +
                            TokenPresentation.expiryText(token.record.expiresAt, System.currentTimeMillis()),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            item(key = "warning") {
                Notice(
                    title = "Copy it now: it's shown only once",
                    text = "PhoneBridge keeps only a hash of the token. If you lose it, revoke it and create a new one. " +
                        "Treat it like a password: anyone holding it can use this phone within its scopes.",
                    kind = NoticeKind.WARNING,
                )
            }
            item(key = "token") {
                CardSection(title = "Token") {
                    CopyableCode(token.plaintext, copyLabel = "Token", sensitive = true, onCopied = markCopied)
                }
            }
            item(key = "endpoint") {
                CardSection(title = "Connect an agent") {
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
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.selectableGroup(),
                            ) {
                                endpoints.forEachIndexed { index, ep ->
                                    ChoiceChip(ep.label, selected = ep == endpoint, onClick = { selected = index })
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
                CardSection(title = snippet.title, footer = snippet.hint) {
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

/** A titled white group holding free-form content (code, chips, notices), padded like a card. */
@Composable
private fun CardSection(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ListSection(title = title, footer = footer) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
