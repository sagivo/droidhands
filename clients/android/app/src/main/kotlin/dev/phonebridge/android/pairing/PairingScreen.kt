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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.transport.PairingRequest
import kotlinx.coroutines.delay

/** Allow stays disabled this long after the screen appears, so a tap meant for something else can't approve. */
private const val ARM_DELAY_MS = 700L

/**
 * Approve a new agent: who it is, the code to compare with the browser, what it may do, when it
 * must ask, and its name. [onAllow] gets the user's choice; [approving] shows progress.
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
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text(
                "Connect an AI agent?",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(request.clientName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "wants to connect from ${request.redirectHost}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                CodeCard(request.displayCode)
                Spacer(Modifier.height(24.dp))

                SectionTitle("What may it do?")
                Column(Modifier.selectableGroup()) {
                    PairingPresentation.ACCESS_LEVELS.forEach { level ->
                        ChoiceRow(
                            title = if (level.recommended) "${level.name} (recommended)" else level.name,
                            description = level.description,
                            selected = level.name == levelName,
                            enabled = !approving,
                            onSelect = { levelName = level.name },
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                SectionTitle("When should it ask you first?")
                Column(Modifier.selectableGroup()) {
                    PairingPresentation.POLICIES.forEach { option ->
                        ChoiceRow(
                            title = PairingPresentation.policyLabel(option),
                            description = PairingPresentation.policyDescription(option),
                            selected = option == policy,
                            enabled = !approving,
                            onSelect = { policy = option },
                        )
                    }
                }
                if (policy == ConfirmPolicy.NEVER) {
                    Text(
                        PairingPresentation.NEVER_POLICY_WARNING,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 48.dp, top = 4.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(PairingRules.MAX_CLIENT_NAME) },
                    label = { Text("Name in PhoneBridge") },
                    supportingText = { Text("Shown in your agents list and in approval prompts.") },
                    singleLine = true,
                    enabled = !approving,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PairingPresentation.originLine(request)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    PairingPresentation.countdownLabel(secondsLeft),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            Text(
                PairingPresentation.ALLOW_HINT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDeny, enabled = !approving, modifier = Modifier.weight(1f)) { Text("Deny") }
                Button(
                    onClick = {
                        val level = PairingPresentation.level(levelName)
                        onAllow(PairingChoice.of(name, level, policy))
                    },
                    enabled = armed && secondsLeft > 0 && !approving,
                    modifier = Modifier.weight(1f),
                ) {
                    if (approving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Allow")
                }
            }
        }
    }
}

@Composable
private fun CodeCard(code: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Code", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                code,
                style = MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp,
                modifier = Modifier.semantics { contentDescription = "Code ${PairingPresentation.spokenCode(code)}" },
            )
            Spacer(Modifier.height(4.dp))
            Text(PairingPresentation.CODE_HINT, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun ChoiceRow(title: String, description: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The row handles clicks, so the button itself is not separately focusable.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
