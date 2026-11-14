package dev.phonebridge.android.ui.tokens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.Destination
import dev.phonebridge.android.ui.MainActivity
import dev.phonebridge.android.ui.apps.AppListing
import dev.phonebridge.android.ui.components.BottomActionBar
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.padded
import dev.phonebridge.android.ui.grant.GrantAppPicker
import dev.phonebridge.android.ui.grant.GrantApps
import dev.phonebridge.android.ui.grant.GrantEditor
import dev.phonebridge.android.ui.grant.GrantPresentation

/**
 * "New token" form: name, the same grant as pairing ("Give <name> access": Everything, or chosen apps and
 * abilities, and whether it asks first) and expiry. The app picker opens in place of the form.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TokenEditorScreen(
    draft: TokenDraft,
    creating: Boolean,
    apps: List<AppListing>?,
    neverAllow: Set<String>,
    onChange: ((TokenDraft) -> TokenDraft) -> Unit,
    onCancel: () -> Unit,
    onCreate: () -> Unit,
) {
    var pickingApps by rememberSaveable { mutableStateOf(false) }
    var appQuery by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    BackHandler { if (pickingApps) pickingApps = false else onCancel() }

    if (pickingApps) {
        ScreenScaffold(
            title = "Apps ${draft.trimmedName.ifEmpty { "it" }} may use",
            navigationIcon = { IconButton(onClick = { pickingApps = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = { TextButton(onClick = { pickingApps = false }) { Text("Done") } },
        ) { padding ->
            GrantAppPicker(
                apps = apps,
                selected = draft.grant.apps.packages,
                neverAllow = neverAllow,
                query = appQuery,
                onQueryChange = { appQuery = it },
                onToggle = { pkg -> onChange { it.copy(grant = it.grant.toggleApp(pkg)) } },
                contentPadding = padding.padded(horizontal = 0.dp),
            )
        }
        return
    }

    ScreenScaffold(
        title = "New token",
        navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") } },
        bottomBar = {
            BottomActionBar {
                Button(onClick = onCreate, enabled = draft.canCreate && !creating) {
                    if (creating) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Create token")
                }
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "name") {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { value -> onChange { it.copy(name = value) } },
                    label = { Text("Name") },
                    supportingText = { Text(draft.nameError ?: "Shown in your agents list and the activity log.") },
                    isError = draft.nameError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item(key = "grant") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(GrantPresentation.title(draft.trimmedName))
                    GrantEditor(
                        grant = draft.grant,
                        original = null,
                        appLabels = GrantApps.labels(apps),
                        neverAllowCount = neverAllow.size,
                        enabled = !creating,
                        onChange = { grant -> onChange { it.copy(grant = grant) } },
                        onChooseApps = { pickingApps = true },
                        onChangeNeverAllow = { context.startActivity(MainActivity.intent(context, Destination.NEVER_ALLOW_ROUTE)) },
                    )
                }
            }

            item(key = "expiry") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Expires")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExpiryOption.entries.forEach { option ->
                            FilterChip(
                                selected = draft.expiry == option,
                                onClick = { onChange { it.copy(expiry = option) } },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
            }
        }
    }
}
