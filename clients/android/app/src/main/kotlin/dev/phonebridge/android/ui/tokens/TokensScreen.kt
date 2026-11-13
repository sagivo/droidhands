package dev.phonebridge.android.ui.tokens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.components.ConfirmDialog
import dev.phonebridge.android.ui.components.EmptyState
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.components.rememberNow
import dev.phonebridge.android.ui.setup.SetupLogic
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.core.auth.TokenRecord

/** Tokens tab: switches between the list, the editor and the one-time "Token created" screen. */
@Composable
fun TokensRoute(vm: TokensViewModel, main: MainViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    when (val s = screen) {
        TokenScreen.List -> TokenListScreen(vm)
        TokenScreen.Editor -> TokenEditorScreen(
            draft = vm.draft,
            creating = vm.creating,
            onChange = vm::updateDraft,
            onCancel = vm::cancelCreate,
            onCreate = vm::create,
        )
        is TokenScreen.Created -> TokenCreatedScreen(token = s.token, main = main, onDone = vm::finish)
    }
}

private sealed interface PendingTokenAction {
    val record: TokenRecord

    data class Revoke(override val record: TokenRecord) : PendingTokenAction
    data class Delete(override val record: TokenRecord) : PendingTokenAction
}

@Composable
private fun TokenListScreen(vm: TokensViewModel) {
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val now by rememberNow()
    var confirm by remember { mutableStateOf<PendingTokenAction?>(null) }

    ScreenScaffold(
        title = "Tokens",
        floatingActionButton = {
            if (tokens.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = vm::startCreate,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New token") },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            contentPadding = padding.padded(extraBottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                Text(
                    "Each AI agent gets its own token. It decides what the agent may do (scopes) and when you are " +
                        "asked to approve. Revoke a token anytime; the agent is locked out immediately.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tokens.isEmpty()) {
                item(key = "empty") {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        EmptyState(
                            title = "No tokens yet",
                            message = "Create one for each agent, for example \"Claude Code on my laptop\". " +
                                "You'll get ready-to-paste setup commands.",
                            icon = Icons.Filled.Lock,
                        )
                        Button(onClick = vm::startCreate) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text("Create a token")
                        }
                    }
                }
            }
            items(tokens, key = { it.id }) { record ->
                TokenCard(
                    record = record,
                    now = now,
                    onRevoke = { confirm = PendingTokenAction.Revoke(record) },
                    onDelete = { confirm = PendingTokenAction.Delete(record) },
                )
            }
        }
    }

    when (val action = confirm) {
        is PendingTokenAction.Revoke -> ConfirmDialog(
            title = "Revoke \"${action.record.name}\"?",
            text = "Agents using this token are locked out immediately. This can't be undone; create a new token " +
                "to reconnect.",
            confirmLabel = "Revoke",
            destructive = true,
            onConfirm = { vm.revoke(action.record) },
            onDismiss = { confirm = null },
        )
        is PendingTokenAction.Delete -> ConfirmDialog(
            title = "Delete \"${action.record.name}\"?",
            text = if (TokenPresentation.state(action.record, now) == TokenState.ACTIVE) {
                "The token stops working immediately and is removed from this list."
            } else {
                "The token is removed from this list."
            },
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { vm.delete(action.record) },
            onDismiss = { confirm = null },
        )
        null -> Unit
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenCard(record: TokenRecord, now: Long, onRevoke: () -> Unit, onDelete: () -> Unit) {
    val colors = statusColors()
    val state = TokenPresentation.state(record, now)
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }

    OutlinedCard(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { stateDescription = if (expanded) "Scopes shown" else "Scopes hidden" },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                when (state) {
                    TokenState.ACTIVE -> StatusPill("Active", colors.ok)
                    TokenState.REVOKED -> StatusPill("Revoked", colors.error)
                    TokenState.EXPIRED -> StatusPill("Expired", colors.warning)
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                "${TokenPresentation.scopeSummary(record.scopes)} · ${TokenPresentation.policyLabel(record.confirmPolicy)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                listOf(
                    TokenPresentation.createdText(record.createdAt, now),
                    TokenPresentation.lastUsedText(record.lastUsedAt, now),
                    if (state == TokenState.REVOKED) null else TokenPresentation.expiryText(record.expiresAt, now),
                ).filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (expanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TokenPresentation.orderedScopes(record.scopes).forEach { scope ->
                        StatusPill(SetupLogic.shortLabel(scope), MaterialTheme.colorScheme.secondary)
                    }
                }
                Text(
                    "ID ${record.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (state == TokenState.ACTIVE) {
                    TextButton(
                        onClick = onRevoke,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Revoke") }
                }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}
