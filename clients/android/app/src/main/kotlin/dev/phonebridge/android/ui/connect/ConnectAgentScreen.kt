package dev.phonebridge.android.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.MainViewModel
import dev.phonebridge.android.ui.components.ScreenScaffold
import dev.phonebridge.android.ui.components.padded

/** Navigation callbacks of the Connect page. */
class ConnectActions(
    val onBack: () -> Unit,
    val onUseToken: () -> Unit,
    val onOpenConnectionSettings: () -> Unit,
    val onOpenAgent: (String) -> Unit,
)

/** "Connect an AI agent": paste one URL into the agent, then tap Allow on the phone. */
@Composable
fun ConnectAgentScreen(main: MainViewModel, actions: ConnectActions) {
    ScreenScaffold(
        title = "Connect an AI agent",
        navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding.padded()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Two steps: paste your connection URL into the agent, then tap Allow when this phone asks. " +
                    "You choose what each agent may do, and can revoke it anytime.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConnectAgentContent(
                main = main,
                onUseToken = actions.onUseToken,
                onOpenConnectionSettings = actions.onOpenConnectionSettings,
                onConnected = "View" to { record -> actions.onOpenAgent(record.id) },
            )
        }
    }
}
