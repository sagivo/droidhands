package com.droidhands.android.ui.agents

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.droidhands.android.ui.components.Accents
import com.droidhands.android.ui.components.CardPadding
import com.droidhands.android.ui.components.EmptyState
import com.droidhands.android.ui.components.IconTile
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.Monogram
import com.droidhands.android.ui.components.PrimaryButton
import com.droidhands.android.ui.components.ScreenScaffold
import com.droidhands.android.ui.components.StatusPill
import com.droidhands.android.ui.components.padded
import com.droidhands.android.ui.components.rememberNow
import com.droidhands.android.ui.grant.GrantApps
import com.droidhands.android.ui.grant.GrantPresentation
import com.droidhands.android.ui.theme.StatusColors
import com.droidhands.android.ui.theme.statusColors
import com.droidhands.android.ui.tokens.TokenPresentation
import com.droidhands.android.ui.tokens.TokenState
import com.droidhands.core.audit.TokenUsage
import com.droidhands.core.auth.TokenRecord
import java.time.Instant
import java.time.ZoneId

/**
 * Agents tab: one row per agent (token) with where it connected from, when it was last active and
 * its status, then a "Connect an AI agent" row. Tap an agent for its details, settings and Revoke.
 */
@Composable
fun AgentsScreen(vm: AgentsViewModel, onOpenAgent: (String) -> Unit, onConnect: () -> Unit) {
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val usage by vm.usage.collectAsStateWithLifecycle()
    val appActions by vm.appActions.collectAsStateWithLifecycle()
    val appLabels = remember(appActions) { GrantApps.labels(appActions) }
    val now by rememberNow()
    val sorted = remember(tokens, usage, now) { AgentPresentation.sorted(tokens, usage, now) }
    val today = remember(now) { Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate() }
    val is24h = DateFormat.is24HourFormat(LocalContext.current)
    val colors = statusColors()

    ScreenScaffold(title = "Agents", large = true) { padding ->
        LazyColumn(contentPadding = padding.padded(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (tokens.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "No agents yet",
                        message = "Copy one message to Claude, ChatGPT or any other AI agent. " +
                            "Then tap Allow on this phone.",
                        icon = Icons.Filled.Face,
                        action = { PrimaryButton("Connect an AI agent", onClick = onConnect, icon = Icons.Filled.Add) },
                    )
                }
            } else {
                item(key = "agents") {
                    ListSection(
                        footer = "Each AI agent you connect appears here, with the access you gave it. Tap one to see what it did, " +
                            "change its access, or revoke it.",
                    ) {
                        sorted.forEach { record ->
                            AgentRow(
                                record = record,
                                usage = usage[record.id],
                                now = now,
                                callsToday = AgentPresentation.callsOn(usage[record.id], today),
                                appLabels = appLabels,
                                restrictions = AgentPresentation.restrictions(record) { Schedule.range(it, is24h) },
                                colors = colors,
                                onOpen = { onOpenAgent(record.id) },
                            )
                            ListDivider(AgentRowInset)
                        }
                        ListRow(
                            title = "Connect an AI agent",
                            titleColor = MaterialTheme.colorScheme.primary,
                            leading = { IconTile(Icons.Filled.Add, Accents.Indigo, size = MONOGRAM_SIZE) },
                            onClick = onConnect,
                            showChevron = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One agent: monogram, name, "client · last active", then its access (the grant's short summary), today's
 * calls and any restrictions; its status as a pill.
 */
@Composable
private fun AgentRow(
    record: TokenRecord,
    usage: TokenUsage?,
    now: Long,
    callsToday: Long,
    appLabels: Map<String, String>,
    restrictions: List<String>,
    colors: StatusColors,
    onOpen: () -> Unit,
) {
    val state = TokenPresentation.state(record, now)
    val color = when (state) {
        TokenState.ACTIVE -> colors.ok
        TokenState.REVOKED -> colors.error
        TokenState.EXPIRED -> colors.warning
    }
    val identity = listOf(
        AgentPresentation.clientLabel(record),
        AgentPresentation.lastActiveText(AgentPresentation.lastActive(record, usage), now),
    ).joinToString(" · ")
    val details = (listOf(AgentPresentation.callsTodayText(callsToday)) + restrictions).joinToString(" · ")
    ListRow(
        title = record.name,
        subtitle = "$identity\n${GrantPresentation.shortSummary(record, appLabels)}\n$details",
        subtitleMaxLines = 4,
        leading = { Monogram(record.name, size = MONOGRAM_SIZE) },
        trailing = { StatusPill(AgentPresentation.stateLabel(state), color, dot = state == TokenState.ACTIVE) },
        showChevron = true,
        onClick = onOpen,
    )
}

private val MONOGRAM_SIZE = 40.dp

/** Divider inset that lines up with the text of rows led by a [MONOGRAM_SIZE] monogram. */
private val AgentRowInset = CardPadding + MONOGRAM_SIZE + 14.dp
