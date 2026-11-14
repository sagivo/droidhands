package dev.phonebridge.android.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.components.AppCard
import dev.phonebridge.android.ui.components.ButtonSize
import dev.phonebridge.android.ui.components.CardPadding
import dev.phonebridge.android.ui.components.IconBadge
import dev.phonebridge.android.ui.components.ListDivider
import dev.phonebridge.android.ui.components.ListRow
import dev.phonebridge.android.ui.components.ListSection
import dev.phonebridge.android.ui.components.Notice
import dev.phonebridge.android.ui.components.NoticeKind
import dev.phonebridge.android.ui.components.PrimaryButton
import dev.phonebridge.android.ui.components.RowValue
import dev.phonebridge.android.ui.components.SecondaryButton
import dev.phonebridge.android.ui.components.StatusPill
import dev.phonebridge.android.ui.theme.statusColors
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.transport.PairingRequest
import kotlinx.coroutines.delay

/** Allow stays disabled this long after the screen appears, so a tap meant for something else can't approve. */
private const val ARM_DELAY_MS = 700L

/** Inset of a [ListDivider] after a radio row, lining up with its text. */
private val RadioRowInset = CardPadding + 24.dp + 14.dp

/**
 * Approve a new agent: who it is, the code to compare with the browser, what it may do, when it
 * must ask, and its name. [onAllow] gets the user's choice; [approving] shows progress. Deny · Allow
 * split the width at the bottom; Allow arms after [ARM_DELAY_MS].
 */
@Composable
internal fun PairingScreen(
    request: PairingRequest,
    approving: Boolean,
    error: String?,
    onAllow: (PairingChoice) -> Unit,
    onDeny: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(request.expiresAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val secondsLeft = PairingPresentation.secondsRemaining(request.expiresAt, now)
    var levelName by rememberSaveable(request.id) { mutableStateOf(PairingPresentation.DEFAULT_LEVEL) }
    var policy by rememberSaveable(request.id) { mutableStateOf(ConfirmPolicy.SENSITIVE) }
    var name by rememberSaveable(request.id) { mutableStateOf(request.clientName) }
    var armed by remember(request.id) { mutableStateOf(false) }
    LaunchedEffect(request.id) {
        delay(ARM_DELAY_MS)
        armed = true
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding(),
        ) {
            Text(
                "Connect an AI agent?",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { heading() },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Hero(request, secondsLeft)
                CodeBox(request.displayCode)

                ListSection(title = "What may it do?") {
                    Column(Modifier.selectableGroup()) {
                        PairingPresentation.ACCESS_LEVELS.forEachIndexed { index, level ->
                            if (index > 0) ListDivider(RadioRowInset)
                            ChoiceRow(
                                title = level.name,
                                description = level.description,
                                selected = level.name == levelName,
                                enabled = !approving,
                                recommended = level.recommended,
                                onSelect = { levelName = level.name },
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ListSection(title = "When should it ask you first?") {
                        Column(Modifier.selectableGroup()) {
                            PairingPresentation.POLICIES.forEachIndexed { index, option ->
                                if (index > 0) ListDivider(RadioRowInset)
                                ChoiceRow(
                                    title = PairingPresentation.policyLabel(option),
                                    description = PairingPresentation.policyDescription(option),
                                    selected = option == policy,
                                    enabled = !approving,
                                    onSelect = { policy = option },
                                )
                            }
                        }
                    }
                    if (policy == ConfirmPolicy.NEVER) {
                        Notice(PairingPresentation.NEVER_POLICY_WARNING, kind = NoticeKind.DANGER)
                    }
                }

                AppCard {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(PairingRules.MAX_CLIENT_NAME) },
                        label = { Text("Name in PhoneBridge") },
                        supportingText = { Text("Shown in your agents list and in approval prompts.") },
                        singleLine = true,
                        enabled = !approving,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ListSection(title = "Request") {
                    ListRow(title = "Website", trailing = { RowValue(request.redirectHost) })
                    request.remoteAddress?.let { address ->
                        ListDivider()
                        // Same fact as PairingPresentation.originLine ("Requested from 203.0.113.7"), as a row.
                        ListRow(title = "Requested from", trailing = { RowValue(address) })
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (error != null) Notice(error, kind = NoticeKind.DANGER)
                Text(
                    PairingPresentation.ALLOW_HINT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Deny", onClick = onDeny, enabled = !approving, size = ButtonSize.LARGE, modifier = Modifier.weight(1f))
                    PrimaryButton(
                        if (approving) "Allowing…" else "Allow",
                        onClick = {
                            val level = PairingPresentation.level(levelName)
                            onAllow(PairingChoice.of(name, level, policy))
                        },
                        enabled = armed && secondsLeft > 0 && !approving,
                        size = ButtonSize.LARGE,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** The agent's name, where it connects from, and the time left, centered under a big badge. */
@Composable
private fun Hero(request: PairingRequest, secondsLeft: Long) {
    val colors = statusColors()
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconBadge(Icons.Filled.Face, MaterialTheme.colorScheme.primary, size = 64.dp)
        Spacer(Modifier.height(4.dp))
        Text(request.clientName, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            "wants to connect from ${request.redirectHost}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        StatusPill(PairingPresentation.countdownLabel(secondsLeft), if (secondsLeft > 0) colors.warning else colors.error)
    }
}

/** The pairing code in a big monospace fill box (as on Home's pairing card), with what to compare it to. */
@Composable
private fun CodeBox(code: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = CardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Code", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    code,
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.semantics { contentDescription = "Code ${PairingPresentation.spokenCode(code)}" },
                )
            }
            Text(PairingPresentation.CODE_HINT, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

/** A radio row with its explanation, laid out like a list row; the whole row selects. */
@Composable
private fun ChoiceRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    recommended: Boolean = false,
) {
    ListRow(
        title = title,
        subtitle = description,
        enabled = enabled,
        // The row handles clicks, so the button itself is not separately focusable.
        leading = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
        trailing = if (recommended) {
            { StatusPill("Recommended", statusColors().ok) }
        } else {
            null
        },
        modifier = Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    )
}
