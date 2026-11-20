package com.droidhands.android.ui.tokens

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
import com.droidhands.android.graph.LocalServerStatus
import com.droidhands.android.ui.MainViewModel
import com.droidhands.android.ui.components.BottomActionBar
import com.droidhands.android.ui.components.ButtonSize
import com.droidhands.android.ui.components.CardPadding
import com.droidhands.android.ui.components.ChoiceChip
import com.droidhands.android.ui.components.CodeBlock
import com.droidhands.android.ui.components.ConfirmDialog
import com.droidhands.android.ui.components.CopyableCode
import com.droidhands.android.ui.components.IconBadge
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.LocalSnackbarHostState
import com.droidhands.android.ui.components.Notice
import com.droidhands.android.ui.components.NoticeKind
import com.droidhands.android.ui.components.PrimaryButton
import com.droidhands.android.ui.components.ScreenScaffold
import com.droidhands.android.ui.components.SecureWindowEffect
import com.droidhands.android.ui.components.padded
import com.droidhands.android.ui.grant.GrantPresentation
import com.droidhands.android.ui.settings.RelayUrls
import com.droidhands.android.ui.theme.statusColors
import com.droidhands.core.auth.IssuedToken
import com.droidhands.core.transport.RelayStatus
import kotlinx.coroutines.launch

/**
 * One-time display of a new token: a success hero, the "shown only once" warning, the token itself,
 * then the message that adds it to any agent ([ConnectionSnippets.prompt]). The window is
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
    val prompt = remember(endpoint, token.plaintext, settings.localServerPort) {
        endpoint?.let { ConnectionSnippets.prompt(it, token.plaintext, settings.localServerPort) }
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
                        "${GrantPresentation.shortSummary(token.record)} · " +
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
                    text = "DroidHands keeps only a hash of the token. If you lose it, revoke it and create a new one. " +
                        "Treat it like a password: anyone holding it can use this phone within its access.",
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
                            Notice("The relay isn't connected right now. The message below works once it is.", kind = NoticeKind.WARNING)
                        }
                    }
                }
            }
            if (prompt != null) {
                item(key = "prompt") {
                    CardSection(
                        title = "Send this to your agent",
                        footer = "Send it to your agent in a chat, or on the computer it runs on. It has the token: send it only to this agent.",
                    ) {
                        CopyableCode(
                            prompt,
                            copyLabel = "Message",
                            sensitive = true,
                            shareTitle = "Send to your agent",
                            onShareFailed = shareFailed,
                            onCopied = markCopied,
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

/** A titled white group holding free-form content (code, chips, notices), padded like a card. */
@Composable
private fun CardSection(title: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ListSection(title = title, footer = footer) {
        Column(Modifier.padding(CardPadding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
