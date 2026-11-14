package dev.phonebridge.android.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.SectionHeader
import dev.phonebridge.android.ui.components.padded

/** Settings > Permissions & access: runtime permissions and special accesses, each with a fix-it action. */
@Composable
fun SetupScreen(vm: SetupViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    WithSetupActions(vm) { actions ->
        ScreenScaffold(
            title = "Permissions & access",
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        ) { padding ->
            LazyColumn(
                contentPadding = padding.padded(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                item(key = "header") { SetupHeader(state) }
                if (state.loaded) {
                    val (essential, optional) = state.items.partition { it.essential }
                    item(key = "essential") { SectionHeader("Essential") }
                    items(essential, key = { it.id }) { SetupRow(it, actions.onAction, onOpenAppInfo = actions.openAppInfo) }
                    item(key = "optional") { SectionHeader("Optional") }
                    items(optional, key = { it.id }) { SetupRow(it, actions.onAction, onOpenAppInfo = actions.openAppInfo) }
                }
            }
        }
    }
}

@Composable
private fun SetupHeader(state: SetupUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Grant what your agents need. Each item says which capabilities it unlocks; agents are told exactly " +
                "what's missing when a tool can't run.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.loaded) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else {
            val p = state.progress
            val summary = "${p.done} of ${p.total} done"
            Text(summary, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { p.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = summary },
            )
        }
    }
}
