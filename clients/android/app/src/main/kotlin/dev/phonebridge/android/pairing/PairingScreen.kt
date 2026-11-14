package dev.phonebridge.android.pairing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.phonebridge.android.ui.apps.AppListing
import dev.phonebridge.android.ui.grant.GrantAppPicker
import dev.phonebridge.android.ui.grant.GrantApps
import dev.phonebridge.android.ui.grant.GrantDraftSaver
import dev.phonebridge.android.ui.grant.GrantEditor
import dev.phonebridge.android.ui.grant.GrantPresentation
import dev.phonebridge.core.auth.GrantDraft
import dev.phonebridge.core.transport.PairingRequest
import kotlinx.coroutines.delay

/** Allow stays disabled this long after the screen appears, so a tap meant for something else can't approve. */
private const val ARM_DELAY_MS = 700L

/**
 * Approve a new agent: who it is, the code to compare with the browser, the grant ("Give <agent> access":
 * Everything, or chosen apps and abilities, and whether it asks first) and its name. The app picker opens in
 * place of the grant, in this same window. [onAllow] gets the user's choice; [approving] shows progress.
 * [apps] are the installed apps for the picker (null while loading); [onChangeNeverAllow] opens the
 * never-allow list.
 */
@Composable
internal fun PairingScreen(
    request: PairingRequest,
    approving: Boolean,
    error: String?,
    apps: List<AppListing>?,
    neverAllow: Set<String>,
    onAllow: (PairingChoice) -> Unit,
    onDeny: () -> Unit,
    onChangeNeverAllow: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(request.expiresAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val secondsLeft = PairingPresentation.secondsRemaining(request.expiresAt, now)
    var grant by rememberSaveable(request.id, stateSaver = GrantDraftSaver) { mutableStateOf(GrantDraft.forNewAgent()) }
    var pickingApps by rememberSaveable(request.id) { mutableStateOf(false) }
    var appQuery by rememberSaveable(request.id) { mutableStateOf("") }
    var name by rememberSaveable(request.id) { mutableStateOf(request.clientName) }
    var armed by remember(request.id) { mutableStateOf(false) }
    LaunchedEffect(request.id) {
        delay(ARM_DELAY_MS)
        armed = true
    }

    BackHandler(enabled = pickingApps) { pickingApps = false }
    if (pickingApps) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Apps ${PairingPresentation.tokenName(name, request)} may use",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    TextButton(onClick = { pickingApps = false }) { Text("Done") }
                }
                GrantAppPicker(
                    apps = apps,
                    selected = grant.apps.packages,
                    neverAllow = neverAllow,
                    query = appQuery,
                    onQueryChange = { appQuery = it },
                    onToggle = { pkg -> grant = grant.toggleApp(pkg) },
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        return
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

                SectionTitle(GrantPresentation.title(PairingPresentation.tokenName(name, request)))
                GrantEditor(
                    grant = grant,
                    original = null,
                    appLabels = GrantApps.labels(apps),
                    neverAllowCount = neverAllow.size,
                    enabled = !approving,
                    onChange = { grant = it },
                    onChooseApps = { pickingApps = true },
                    onChangeNeverAllow = onChangeNeverAllow,
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(PairingRules.MAX_CLIENT_NAME) },
                    label = { Text("Name in PhoneBridge") },
                    supportingText = { Text("Shown in your agents list and the activity log.") },
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
                    onClick = { onAllow(PairingChoice(name, grant)) },
                    enabled = armed && secondsLeft > 0 && !approving && grant.isValid,
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
