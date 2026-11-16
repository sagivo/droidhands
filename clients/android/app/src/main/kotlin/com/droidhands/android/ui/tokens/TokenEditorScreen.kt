package com.droidhands.android.ui.tokens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.droidhands.android.ui.Destination
import com.droidhands.android.ui.MainActivity
import com.droidhands.android.ui.apps.AppListing
import com.droidhands.android.ui.components.AppCard
import com.droidhands.android.ui.components.BottomActionBar
import com.droidhands.android.ui.components.ButtonSize
import com.droidhands.android.ui.components.ChoiceChip
import com.droidhands.android.ui.components.PrimaryButton
import com.droidhands.android.ui.components.ScreenScaffold
import com.droidhands.android.ui.components.SectionHeader
import com.droidhands.android.ui.components.TertiaryButton
import com.droidhands.android.ui.components.padded
import com.droidhands.android.ui.grant.GrantAppPicker
import com.droidhands.android.ui.grant.GrantApps
import com.droidhands.android.ui.grant.GrantEditor
import com.droidhands.android.ui.grant.GrantPresentation

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
            onBack = { pickingApps = false },
            actions = { TertiaryButton("Done", onClick = { pickingApps = false }) },
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
                TertiaryButton("Cancel", onClick = onCancel)
                PrimaryButton(
                    "Create token",
                    onClick = onCreate,
                    enabled = draft.canCreate,
                    loading = creating,
                    size = ButtonSize.LARGE,
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding.padded(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "name") {
                AppCard {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { value -> onChange { it.copy(name = value) } },
                        label = { Text("Name") },
                        supportingText = { Text(draft.nameError ?: "Shown in your agents list and the activity log.") },
                        isError = draft.nameError != null,
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            item(key = "grant") {
                GrantEditor(
                    grant = draft.grant,
                    original = null,
                    appLabels = GrantApps.labels(apps),
                    neverAllowCount = neverAllow.size,
                    enabled = !creating,
                    onChange = { grant -> onChange { it.copy(grant = grant) } },
                    onChooseApps = { pickingApps = true },
                    onChangeNeverAllow = { context.startActivity(MainActivity.intent(context, Destination.NEVER_ALLOW_ROUTE)) },
                    title = GrantPresentation.title(draft.trimmedName),
                )
            }

            item(key = "expiry") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Expires")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.selectableGroup(),
                    ) {
                        ExpiryOption.entries.forEach { option ->
                            ChoiceChip(option.label, selected = draft.expiry == option, onClick = { onChange { it.copy(expiry = option) } })
                        }
                    }
                }
            }
        }
    }
}
