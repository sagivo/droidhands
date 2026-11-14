package dev.phonebridge.android.ui.agents

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
import dev.phonebridge.android.ui.components.Accents
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.EmptyState
import dev.phonebridge.android.ui.components.IconTile
import dev.phonebridge.android.ui.components.ListDivider
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.Monogram
import dev.phonebridge.android.ui.components.PrimaryButton
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.components.rememberNow
import dev.phonebridge.android.ui.theme.StatusColors
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.android.ui.tokens.TokenPresentation
import dev.phonebridge.android.ui.tokens.TokenState
import dev.phonebridge.core.audit.TokenUsage
import dev.phonebridge.core.auth.TokenRecord
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
                        message = "Connect Claude, ChatGPT or any MCP client: paste one URL into it, then tap Allow on this phone.",
                        icon = Icons.Filled.Face,
                        action = { PrimaryButton("Connect an AI agent", onClick = onConnect, icon = Icons.Filled.Add) },
                    )
                }
            } else {
                item(key = "agents") {
                    ListSection(footer = "Tap an agent to see what it did, change what it may do, or revoke it.") {
                        sorted.forEach { record ->
                            AgentRow(
                                record = record,
                                usage = usage[record.id],
                                now = now,
                                callsToday = AgentPresentation.callsOn(usage[record.id], today),
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
 * One agent: monogram, name, "client · last active", then access level, today's calls and any
 * restrictions; its status as a pill.
 */
@Composable
private fun AgentRow(
    record: TokenRecord,
    usage: TokenUsage?,
    now: Long,
    callsToday: Long,
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
    val details = (listOf(AccessLevel.of(record).label, AgentPresentation.callsTodayText(callsToday)) + restrictions)
        .joinToString(" · ")
    ListRow(
        title = record.name,
        subtitle = "$identity\n$details",
        subtitleMaxLines = 3,
        leading = { Monogram(record.name, size = MONOGRAM_SIZE) },
        trailing = { StatusPill(AgentPresentation.stateLabel(state), color, dot = state == TokenState.ACTIVE) },
        showChevron = true,
        onClick = onOpen,
    )
}

private val MONOGRAM_SIZE = 40.dp

/** Divider inset that lines up with the text of rows led by a [MONOGRAM_SIZE] monogram. */
private val AgentRowInset = CardPadding + MONOGRAM_SIZE + 14.dp
