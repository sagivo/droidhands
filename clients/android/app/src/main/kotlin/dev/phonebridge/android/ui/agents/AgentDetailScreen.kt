package dev.phonebridge.android.ui.agents

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.activity.AuditRow
import dev.phonebridge.android.ui.components.Accents
import dev.phonebridge.android.ui.components.AppCard
import dev.phonebridge.android.ui.components.BottomActionBar
import dev.phonebridge.android.ui.components.ButtonSize
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.ConfirmDialog
import dev.phonebridge.android.ui.components.IconTile
import dev.phonebridge.android.ui.components.ListDivider
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.Monogram
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.PrimaryButton
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SectionTitle
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.components.TertiaryButton
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.components.rememberNow
import dev.phonebridge.android.ui.components.rememberShortDayFormatter
import dev.phonebridge.android.ui.components.rememberTimestampFormatter
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.android.ui.tokens.TokenPresentation
import dev.phonebridge.android.ui.tokens.TokenState
import dev.phonebridge.core.audit.TokenUsage
import dev.phonebridge.core.auth.TokenRecord
import dev.phonebridge.core.tools.AppAccess
import java.time.Instant
import java.time.ZoneId

/** Navigation callbacks of the agent detail page. */
class AgentDetailActions(
    val onBack: () -> Unit,
    val onChooseApps: () -> Unit,
    val onSeeActivity: () -> Unit,
)

/**
 * One agent: a hero card (identity, status, key numbers), usage chart and totals, recent activity,
 * access (level, approvals, expiry), advanced controls (app access, tool rules, active hours), then
 * revoke / delete. Edits collect in a draft and are saved together from the bottom bar.
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
    val apps by vm.apps.collectAsStateWithLifecycle()
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
    val groups = remember(tools, draft.scopes, draft.maxRisk) { ToolRules.groups(tools, draft.scopes, draft.maxRisk) }
    val visibleToolNames = remember(groups) { groups.flatMap { g -> g.tools.map { it.name } }.toSet() }
    val access = remember(draft.appPolicy, neverAllow) { AppAccess.of(draft.appPolicy, neverAllow) }
    val actionsAvailable = ActionRules.canRunActions(draft.scopes, draft.maxRisk, draft.toolRules)
    val actionGroups = remember(appActions, draft.scopes, draft.maxRisk, draft.toolRules, access) {
        ActionRules.groups(appActions.orEmpty(), draft.scopes, draft.maxRisk, draft.toolRules, access)
    }
    val shownActionGroups = remember(actionGroups, appActions, draft.scopes, draft.maxRisk, draft.toolRules, access, actionQuery) {
        if (actionQuery.isBlank()) actionGroups else ActionRules.groups(appActions.orEmpty(), draft.scopes, draft.maxRisk, draft.toolRules, access, actionQuery)
    }
    val actionToolNames = remember(actionGroups) { ActionRules.names(actionGroups) }
    val colors = statusColors()
    val name = draft.trimmedName.ifEmpty { record.name }

    LaunchedEffect(Unit) {
        if (vm.apps.value == null) vm.loadApps()
        vm.loadAppActions()
    }
    val leave: () -> Unit = { if (dirty) confirm = DISCARD else actions.onBack() }
    BackHandler(enabled = dirty) { confirm = DISCARD }

    ScreenScaffold(
        title = name,
        onBack = leave,
        actions = {
            if (editable) IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = "Rename agent") }
        },
        bottomBar = {
            if (dirty || vm.saving) {
                BottomActionBar {
                    TertiaryButton("Discard", onClick = vm::discard, enabled = !vm.saving)
                    PrimaryButton(
                        "Save",
                        onClick = vm::save,
                        enabled = draft.canSave,
                        loading = vm.saving,
                        size = ButtonSize.LARGE,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded()) {
            item(key = "header") {
                AgentHero(
                    record = record,
                    name = name,
                    usage = usage,
                    callsToday = AgentPresentation.callsOn(usage, today),
                    state = state,
                    now = now,
                )
            }
            block(key = "usage") { AgentUsageSection(usage, today, formatDay, actionNames) }
            block(key = "recent") {
                if (recent.isEmpty()) {
                    ListSection(title = "Recent activity") {
                        ListRow(
                            title = "Nothing yet",
                            subtitle = "No activity from this agent in the log.",
                            leading = { IconTile(Icons.AutoMirrored.Filled.List, Accents.Gray) },
                        )
                    }
                } else {
                    ListSection(title = "Recent activity", action = { TertiaryButton("See all", onClick = actions.onSeeActivity) }) {
                        recent.forEachIndexed { index, entry ->
                            if (index > 0) ListDivider()
                            AuditRow(
                                entry, formatTime(entry.timestamp, now), colors, onClick = actions.onSeeActivity, showAgent = false,
                                names = actionNames,
                            )
                        }
                    }
                }
            }
            if (editable) {
                block(key = "access") { AgentAccessSection(draft, record.expiresAt, now, onChange = vm::edit) }
                block(key = "apps") {
                    AppAccessSection(draft, apps, neverAllow.size, onChange = vm::edit, onChooseApps = actions.onChooseApps)
                }
                lazySection(
                    key = "rules",
                    title = "Tool rules",
                    footer = TOOL_RULES_FOOTER,
                    rows = toolRuleRows(groups, draft, vm, visibleToolNames, showTools, onToggle = { showTools = !showTools }),
                )
                lazySection(
                    key = "action-rules",
                    title = "App action rules",
                    footer = ACTION_RULES_FOOTER,
                    rows = buildList {
                        add(
                            SectionRow("action-rules") {
                                ActionRulesToggleRow(
                                    summary = ToolRules.summary(draft.toolRules, actionToolNames),
                                    available = actionsAvailable,
                                    loading = appActions == null || appActionsLoading,
                                    appCount = actionGroups.size,
                                    expanded = showActions,
                                    onToggle = { showActions = !showActions },
                                )
                            },
                        )
                        if (showActions && actionsAvailable) {
                            addAll(
                                actionRuleRows(
                                    groups = shownActionGroups,
                                    query = actionQuery,
                                    onQueryChange = { actionQuery = it },
                                    expanded = expandedApps.toSet(),
                                    onToggleApp = { pkg -> expandedApps = ArrayList(if (pkg in expandedApps) expandedApps - pkg else expandedApps + pkg) },
                                    draft = draft,
                                    vm = vm,
                                ),
                            )
                        }
                    },
                )
                block(key = "hours") {
                    ActiveHoursSection(draft.activeHours, is24h, draft.activeHoursError, onChange = { hours -> vm.edit { it.copy(activeHours = hours) } })
                }
            }
            block(key = "danger") {
                RemoveSection(
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

/** The tool rules list: the toggle row, then (expanded) one titled run of rows per capability. */
private fun toolRuleRows(
    groups: List<ToolGroup>,
    draft: AgentDraft,
    vm: AgentsViewModel,
    visibleToolNames: Set<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
): List<SectionRow> = buildList {
    add(
        SectionRow("rules") {
            ToolRulesToggleRow(
                summary = ToolRules.summary(draft.toolRules, visibleToolNames),
                expanded = expanded,
                toolCount = visibleToolNames.size,
                onToggle = onToggle,
            )
        },
    )
    if (!expanded) return@buildList
    groups.forEach { group ->
        add(SectionRow("rules-${group.scope}") { ToolGroupHeader(group.scope) })
        group.tools.forEachIndexed { index, tool ->
            add(
                SectionRow("rule-${tool.name}", divider = if (index == 0) null else CardPadding) {
                    ToolRuleRow(
                        tool = tool,
                        choice = RuleChoice.of(draft.toolRules[tool.name]),
                        policy = draft.policy,
                        onChoose = { choice -> vm.edit { it.withRule(tool.name, choice) } },
                    )
                },
            )
        }
    }
}

/**
 * The expanded app-action rules: a search field, then one collapsible row per app. While searching, every
 * matching app is open, so the matches show at once.
 */
private fun actionRuleRows(
    groups: List<ActionRuleGroup>,
    query: String,
    onQueryChange: (String) -> Unit,
    expanded: Set<String>,
    onToggleApp: (String) -> Unit,
    draft: AgentDraft,
    vm: AgentsViewModel,
): List<SectionRow> = buildList {
    add(SectionRow("action-rules-search") { ActionRulesSearch(query, onQueryChange) })
    if (groups.isEmpty()) {
        add(SectionRow("action-rules-none", divider = null) { ListRow(title = "No app or action matches.") })
    }
    groups.forEach { group ->
        val open = query.isNotBlank() || group.packageName in expanded
        add(
            SectionRow("action-app-${group.packageName}", divider = CardPadding) {
                ActionGroupHeader(group, expanded = open, onToggle = { onToggleApp(group.packageName) })
            },
        )
        if (open) {
            group.actions.forEach { action ->
                add(
                    SectionRow("action-rule-${action.toolName}", divider = ActionRowInset) {
                        ActionRuleRow(
                            action = action,
                            choice = RuleChoice.of(draft.toolRules[action.toolName]),
                            policy = draft.policy,
                            onChoose = { choice -> vm.edit { it.withRule(action.toolName, choice) } },
                        )
                    },
                )
            }
        }
    }
}

/**
 * The hero card: monogram, name, where it connected from and its access level, its status, then
 * today's and all-time calls and the last-active / created / expiry line. A revoked or expired agent
 * gets a notice explaining what that means.
 */
@Composable
private fun AgentHero(record: TokenRecord, name: String, usage: TokenUsage?, callsToday: Long, state: TokenState, now: Long) {
    val colors = statusColors()
    val color = when (state) {
        TokenState.ACTIVE -> colors.ok
        TokenState.REVOKED -> colors.error
        TokenState.EXPIRED -> colors.warning
    }
    val total = usage?.totalCalls ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Monogram(name, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        AgentPresentation.clientLabel(record) + " · " + AccessLevel.of(record).label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                StatusPill(AgentPresentation.stateLabel(state), color, dot = state == TokenState.ACTIVE)
            }
            Row(Modifier.fillMaxWidth()) {
                HeroStat(UsageStats.compact(callsToday), if (callsToday == 1L) "call today" else "calls today", Modifier.weight(1f))
                HeroStat(UsageStats.compact(total), if (total == 1L) "call in all" else "calls in all", Modifier.weight(1f))
            }
            Text(
                listOfNotNull(
                    AgentPresentation.lastActiveText(listOfNotNull(record.lastUsedAt, usage?.lastCallAt).maxOrNull(), now),
                    TokenPresentation.createdText(record.createdAt, now),
                    if (state == TokenState.REVOKED) null else TokenPresentation.expiryText(record.expiresAt, now),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (state) {
            TokenState.REVOKED -> Notice(
                "This agent is locked out. To use it again, connect it again; you can delete this entry.",
                kind = NoticeKind.WARNING,
            )
            TokenState.EXPIRED -> Notice(
                "This agent expired and can't act. Pick a new expiry under Expires and save to let it back in.",
                kind = NoticeKind.WARNING,
            )
            TokenState.ACTIVE -> Unit
        }
    }
}

/** A key number of the hero card; TalkBack reads value and label together. */
@Composable
private fun HeroStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Remove", last on the page: Revoke (while active) and Delete, both red and both confirmed. */
@Composable
private fun RemoveSection(active: Boolean, onRevoke: () -> Unit, onDelete: () -> Unit) {
    val red = MaterialTheme.colorScheme.error
    ListSection(title = "Remove") {
        if (active) {
            ListRow(title = "Revoke access", titleColor = red, onClick = onRevoke, showChevron = false)
            ListDivider()
        }
        ListRow(title = "Delete agent", titleColor = red, onClick = onDelete, showChevron = false)
    }
}

/** One top-level block of the page, [BLOCK_GAP] below the previous one. */
private fun LazyListScope.block(key: String, content: @Composable () -> Unit) {
    item(key = key) { Box(Modifier.padding(top = BLOCK_GAP)) { content() } }
}

/** A row of a [lazySection]; [divider] is the inset of the hairline above it (none for the first row or when null). */
private class SectionRow(val key: String, val divider: Dp? = CardPadding, val content: @Composable () -> Unit)

/**
 * A [ListSection] laid out as separate lazy items, for lists too long to compose at once (tool and
 * app-action rules): the title, one item per row sharing one rounded group, then the footer.
 */
private fun LazyListScope.lazySection(key: String, title: String, footer: String?, rows: List<SectionRow>) {
    item(key = "$key-title") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = BLOCK_GAP, bottom = 6.dp)
                .defaultMinSize(minHeight = 32.dp)
                .padding(start = CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle(title)
        }
    }
    rows.forEachIndexed { index, row ->
        item(key = row.key) {
            GroupPiece(first = index == 0, last = index == rows.lastIndex) {
                if (index > 0 && row.divider != null) ListDivider(row.divider)
                row.content()
            }
        }
    }
    if (footer != null) {
        item(key = "$key-footer") {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = CardPadding, end = CardPadding, top = 6.dp),
            )
        }
    }
}

/** A slice of a list group's white card: only the group's first and last slices have rounded corners. */
@Composable
private fun GroupPiece(first: Boolean, last: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val large = MaterialTheme.shapes.large
    val shape = large.copy(
        topStart = if (first) large.topStart else ZeroCornerSize,
        topEnd = if (first) large.topEnd else ZeroCornerSize,
        bottomEnd = if (last) large.bottomEnd else ZeroCornerSize,
        bottomStart = if (last) large.bottomStart else ZeroCornerSize,
    )
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

private val BLOCK_GAP = 20.dp
private const val RECENT_ROWS = 5
private const val REVOKE = "revoke"
private const val DELETE = "delete"
private const val DISCARD = "discard"
