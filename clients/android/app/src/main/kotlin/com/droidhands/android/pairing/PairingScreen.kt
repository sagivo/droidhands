package com.droidhands.android.pairing

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.droidhands.android.ui.apps.AppListing
import com.droidhands.android.ui.components.AppCard
import com.droidhands.android.ui.components.ButtonSize
import com.droidhands.android.ui.components.CardPadding
import com.droidhands.android.ui.components.IconBadge
import com.droidhands.android.ui.components.ListDivider
import com.droidhands.android.ui.components.ListRow
import com.droidhands.android.ui.components.ListSection
import com.droidhands.android.ui.components.Notice
import com.droidhands.android.ui.components.NoticeKind
import com.droidhands.android.ui.components.PrimaryButton
import com.droidhands.android.ui.components.RowValue
import com.droidhands.android.ui.components.SecondaryButton
import com.droidhands.android.ui.components.StatusPill
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.android.ui.grant.GrantAppPicker
import com.droidhands.android.ui.grant.GrantApps
import com.droidhands.android.ui.grant.GrantDraftSaver
import com.droidhands.android.ui.grant.GrantEditor
import com.droidhands.android.ui.grant.GrantPresentation
import com.droidhands.android.ui.theme.statusColors
import com.droidhands.core.auth.GrantDraft
import com.droidhands.core.transport.PairingRequest
import kotlinx.coroutines.delay

/** Allow stays disabled this long after the screen appears, so a tap meant for something else can't approve. */
private const val ARM_DELAY_MS = 700L

/**
 * Approve a new agent: who it is, the code to compare with the browser, the grant ("Give <agent> access":
 * Everything, or chosen apps and abilities, and whether it asks first) and its name. The app picker opens in
 * place of the grant, in this same window. [onAllow] gets the user's choice; [approving] shows progress.
 * [apps] are the installed apps for the picker (null while loading); [onChangeNeverAllow] opens the
 * never-allow list. Deny · Allow split the width at the bottom; Allow arms [ARM_DELAY_MS] after the screen
 * (or the grant, back from the app picker) appears.
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
    // Re-armed after the app picker closes too, so the tap on Done can't land on Allow.
    LaunchedEffect(request.id, pickingApps) {
        armed = false
        if (!pickingApps) {
            delay(ARM_DELAY_MS)
            armed = true
        }
    }

    BackHandler(enabled = pickingApps) { pickingApps = false }
    if (pickingApps) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Apps ${PairingPresentation.tokenName(name, request)} may use",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier
                            .weight(1f)
                            .semantics { heading() },
                    )
                    TertiaryButton("Done", onClick = { pickingApps = false })
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

                GrantEditor(
                    grant = grant,
                    original = null,
                    appLabels = GrantApps.labels(apps),
                    neverAllowCount = neverAllow.size,
                    enabled = !approving,
                    onChange = { grant = it },
                    onChooseApps = { pickingApps = true },
                    onChangeNeverAllow = onChangeNeverAllow,
                    title = GrantPresentation.title(PairingPresentation.tokenName(name, request)),
                )

                AppCard {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(PairingRules.MAX_CLIENT_NAME) },
                        label = { Text("Name in DroidHands") },
                        supportingText = { Text("Shown in your agents list and the activity log.") },
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
                        "Allow",
                        onClick = { onAllow(PairingChoice(name, grant)) },
                        enabled = armed && secondsLeft > 0 && grant.isValid,
                        loading = approving,
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
