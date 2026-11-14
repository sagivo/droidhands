package dev.phonebridge.android.ui.agents

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.activity.AuditRow
import dev.phonebridge.android.ui.components.BottomActionBar
import dev.phonebridge.android.ui.components.ConfirmDialog
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.components.rememberNow
import dev.phonebridge.android.ui.components.rememberShortDayFormatter
import dev.phonebridge.android.ui.components.rememberTimestampFormatter
import dev.phonebridge.android.ui.grant.GrantApps
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.android.ui.tokens.TokenPresentation
import dev.phonebridge.android.ui.tokens.TokenState
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.tools.AppAccess
import java.time.Instant
import java.time.ZoneId

/** Navigation callbacks of the agent detail page. */
class AgentDetailActions(
    val onBack: () -> Unit,
    /** Opens the agent's Access page (the grant editor; the route keeps its older name, agent-apps). */
    val onChooseApps: () -> Unit,
    val onSeeActivity: () -> Unit,
)

/**
 * One agent: usage chart and totals, recent activity, its access (the grant; "Change access" opens the grant
 * editor, which saves at once), advanced controls (tool rules, app action rules, active hours, expiry), then
 * revoke / delete. Advanced edits collect in a draft and are saved together from the bottom bar.
 */
@Composable
fun AgentDetailScreen(vm: AgentsViewModel, tokenId: String, actions: AgentDetailActions) {
    LaunchedEffect(tokenId) { vm.open(tokenId) }
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val record = tokens.firstOrNull { it.id == tokenId }
    val draft = vm.draft?.takeIf { vm.editingId == tokenId }
    if (record == null || draft == null) {
        // Deleted (the root pops this page) or still opening.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    AgentDetailContent(vm, record, draft, actions)
}

@Composable
private fun AgentDetailContent(vm: AgentsViewModel, record: TokenRecord, draft: AgentDraft, actions: AgentDetailActions) {
    val usageMap by vm.usage.collectAsStateWithLifecycle()
    val entries by vm.auditEntries.collectAsStateWithLifecycle()
    val tools by vm.tools.collectAsStateWithLifecycle()
    val neverAllow by vm.neverAllowApps.collectAsStateWithLifecycle()
    val appActions by vm.appActions.collectAsStateWithLifecycle()
    val appActionsLoading by vm.appActionsLoading.collectAsStateWithLifecycle()
    val actionNames by vm.actionNames.collectAsStateWithLifecycle()
    val now by rememberNow()
    val formatTime = rememberTimestampFormatter()
    val formatDay = rememberShortDayFormatter()
    val is24h = DateFormat.is24HourFormat(LocalContext.current)

    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var showActions by rememberSaveable { mutableStateOf(false) }
    var actionQuery by rememberSaveable { mutableStateOf("") }
    var expandedApps by rememberSaveable { mutableStateOf(ArrayList<String>()) }

    val usage = usageMap[record.id]
    val state = TokenPresentation.state(record, now)
    val editable = state != TokenState.REVOKED
    val dirty = vm.isDirty()
    val today = remember(now) { Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate() }
    val recent = remember(entries, record.id) { entries.asSequence().filter { it.tokenId == record.id }.take(RECENT_ROWS).toList() }
    // The agent's access is edited on its own page and saved at once, so the stored record is current.
    val scopes = record.scopes
    val maxRisk = record.maxRisk
    val groups = remember(tools, scopes, maxRisk) { ToolRules.groups(tools, scopes, maxRisk) }
    val visibleToolNames = remember(groups) { groups.flatMap { g -> g.tools.map { it.name } }.toSet() }
    val access = remember(record.appPolicy, neverAllow) { AppAccess.of(record.appPolicy, neverAllow) }
    val actionsAvailable = ActionRules.canRunActions(scopes, maxRisk, draft.toolRules)
    val actionGroups = remember(appActions, scopes, maxRisk, draft.toolRules, access) {
        ActionRules.groups(appActions.orEmpty(), scopes, maxRisk, draft.toolRules, access)
    }
    val shownActionGroups = remember(actionGroups, appActions, scopes, maxRisk, draft.toolRules, access, actionQuery) {
        if (actionQuery.isBlank()) actionGroups else ActionRules.groups(appActions.orEmpty(), scopes, maxRisk, draft.toolRules, access, actionQuery)
    }
    val appLabels = remember(appActions) { GrantApps.labels(appActions) }
    val actionToolNames = remember(actionGroups) { ActionRules.names(actionGroups) }
    val colors = statusColors()

    LaunchedEffect(Unit) { vm.loadAppActions() }
    val leave: () -> Unit = { if (dirty) confirm = DISCARD else actions.onBack() }
    BackHandler(enabled = dirty) { confirm = DISCARD }

    ScreenScaffold(
        title = draft.trimmedName.ifEmpty { record.name },
        navigationIcon = { IconButton(onClick = leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        actions = {
            if (editable) IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = "Rename agent") }
        },
        bottomBar = {
            if (dirty || vm.saving) {
                BottomActionBar {
                    TextButton(onClick = vm::discard, enabled = !vm.saving) { Text("Discard") }
                    Button(onClick = vm::save, enabled = draft.canSave && !vm.saving) {
                        if (vm.saving) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Save")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "header") { AgentHeader(record, usage?.lastCallAt, state, now) }
            item(key = "usage-header") { SectionHeader("Usage") }
            item(key = "usage") { AgentUsageCard(usage, today, formatDay, actionNames) }
            item(key = "recent-header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("Recent activity", Modifier.weight(1f))
                    if (recent.isNotEmpty()) TextButton(onClick = actions.onSeeActivity) { Text("See all") }
                }
            }
            item(key = "recent") {
                if (recent.isEmpty()) {
                    Text("No activity in the log.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column {
                            recent.forEachIndexed { index, entry ->
                                if (index > 0) HorizontalDivider()
                                AuditRow(
                                    entry, formatTime(entry.timestamp, now), colors, onClick = actions.onSeeActivity, showAgent = false,
                                    names = actionNames,
                                )
                            }
                        }
                    }
                }
            }
            item(key = "access") { AgentAccessCard(record, appLabels, editable, onChange = actions.onChooseApps) }
            if (editable) {
                item(key = "advanced-header") { SectionHeader("Advanced") }
                item(key = "rules") {
                    ToolRulesHeader(
                        summary = ToolRules.summary(draft.toolRules, visibleToolNames),
                        expanded = showTools,
                        toolCount = visibleToolNames.size,
                        onToggle = { showTools = !showTools },
                    )
                }
                if (showTools) toolRuleItems(groups, draft, record.confirmPolicy, vm)
                item(key = "action-rules") {
                    ActionRulesHeader(
                        summary = ToolRules.summary(draft.toolRules, actionToolNames),
                        available = actionsAvailable,
                        loading = appActions == null || appActionsLoading,
                        appCount = actionGroups.size,
                        expanded = showActions,
                        onToggle = { showActions = !showActions },
                    )
                }
                if (showActions && actionsAvailable) {
                    actionRuleItems(
                        groups = shownActionGroups,
                        query = actionQuery,
                        onQueryChange = { actionQuery = it },
                        expanded = expandedApps.toSet(),
                        onToggleApp = { pkg -> expandedApps = ArrayList(if (pkg in expandedApps) expandedApps - pkg else expandedApps + pkg) },
                        draft = draft,
                        policy = record.confirmPolicy,
                        vm = vm,
                    )
                }
                item(key = "hours") {
                    ActiveHoursCard(draft.activeHours, is24h, draft.activeHoursError, onChange = { hours -> vm.edit { it.copy(activeHours = hours) } })
                }
                item(key = "expiry") { ExpiryCard(draft, record.expiresAt, now, onChange = vm::edit) }
            }
            item(key = "danger") {
                DangerZone(
                    active = state == TokenState.ACTIVE,
                    onRevoke = { confirm = REVOKE },
                    onDelete = { confirm = DELETE },
                )
            }
        }
    }

    if (renaming) {
        RenameAgentDialog(draft.name, onRename = { name -> vm.edit { it.copy(name = name) } }, onDismiss = { renaming = false })
    }
    when (confirm) {
        REVOKE -> RevokeAgentDialog(record, onConfirm = { vm.revoke(record) }, onDismiss = { confirm = null })
        DELETE -> DeleteAgentDialog(
            record,
            active = state == TokenState.ACTIVE,
            onConfirm = {
                vm.delete(record)
                actions.onBack()
            },
            onDismiss = { confirm = null },
        )
        DISCARD -> ConfirmDialog(
            title = "Discard changes?",
            text = "Your changes to \"${record.name}\" haven't been saved.",
            confirmLabel = "Discard",
            dismissLabel = "Keep editing",
            destructive = true,
            onConfirm = {
                vm.discard()
                actions.onBack()
            },
            onDismiss = { confirm = null },
        )
    }
}

private fun LazyListScope.toolRuleItems(groups: List<ToolGroup>, draft: AgentDraft, policy: ConfirmPolicy, vm: AgentsViewModel) {
    groups.forEach { group ->
        item(key = "rules-${group.scope}") { ToolGroupHeader(group.scope) }
        group.tools.forEach { tool ->
            item(key = "rule-${tool.name}") {
                ToolRuleRow(
                    tool = tool,
                    choice = RuleChoice.of(draft.toolRules[tool.name]),
                    policy = policy,
                    onChoose = { choice -> vm.edit { it.withRule(tool.name, choice) } },
                )
            }
        }
    }
}

/**
 * The expanded app-action rules: a search field, then one collapsible row per app. While searching, every
 * matching app is open, so the matches show at once.
 */
private fun LazyListScope.actionRuleItems(
    groups: List<ActionRuleGroup>,
    query: String,
    onQueryChange: (String) -> Unit,
    expanded: Set<String>,
    onToggleApp: (String) -> Unit,
    draft: AgentDraft,
    policy: ConfirmPolicy,
    vm: AgentsViewModel,
) {
    item(key = "action-rules-search") { ActionRulesSearch(query, onQueryChange) }
    if (groups.isEmpty()) {
        item(key = "action-rules-none") {
            Text("No app or action matches.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    groups.forEach { group ->
        val open = query.isNotBlank() || group.packageName in expanded
        item(key = "action-app-${group.packageName}") {
            ActionGroupHeader(group, expanded = open, onToggle = { onToggleApp(group.packageName) })
        }
        if (open) {
            group.actions.forEach { action ->
                item(key = "action-rule-${action.toolName}") {
                    ActionRuleRow(
                        action = action,
                        choice = RuleChoice.of(draft.toolRules[action.toolName]),
                        policy = policy,
                        onChoose = { choice -> vm.edit { it.withRule(action.toolName, choice) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentHeader(record: TokenRecord, lastCallAt: Long?, state: TokenState, now: Long) {
    val colors = statusColors()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                AgentPresentation.clientLabel(record),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            val color = when (state) {
                TokenState.ACTIVE -> colors.ok
                TokenState.REVOKED -> colors.error
                TokenState.EXPIRED -> colors.warning
            }
            StatusPill(AgentPresentation.stateLabel(state), color)
        }
        Text(
            listOfNotNull(
                AgentPresentation.lastActiveText(listOfNotNull(record.lastUsedAt, lastCallAt).maxOrNull(), now),
                TokenPresentation.createdText(record.createdAt, now),
                if (state == TokenState.REVOKED) null else TokenPresentation.expiryText(record.expiresAt, now),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state) {
            TokenState.REVOKED -> Notice(
                "This agent is locked out. To use it again, connect it again; you can delete this entry.",
                kind = NoticeKind.WARNING,
            )
            TokenState.EXPIRED -> Notice(
                "This agent expired and can't act. Pick a new expiry under Advanced > Expires and save to let it back in.",
                kind = NoticeKind.WARNING,
            )
            TokenState.ACTIVE -> Unit
        }
    }
}

@Composable
private fun DangerZone(active: Boolean, onRevoke: () -> Unit, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Remove")
        if (active) {
            Button(
                onClick = onRevoke,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Revoke access") }
        }
        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete agent") }
    }
}

private const val RECENT_ROWS = 5
private const val REVOKE = "revoke"
private const val DELETE = "delete"
private const val DISCARD = "discard"
