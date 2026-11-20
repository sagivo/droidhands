package com.droidhands.android.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.droidhands.android.ui.MainViewModel
import com.droidhands.android.ui.components.CardPadding
import com.droidhands.android.ui.components.ScreenScaffold
import com.droidhands.android.ui.components.padded

/** Navigation callbacks of the Connect page. */
class ConnectActions(
    val onBack: () -> Unit,
    val onUseToken: () -> Unit,
    val onOpenConnectionSettings: () -> Unit,
    val onOpenMoreOptions: () -> Unit,
    val onOpenAgent: (String) -> Unit,
)

/** "Connect an AI agent": paste one URL into the agent, then tap Allow on the phone. */
@Composable
fun ConnectAgentScreen(main: MainViewModel, actions: ConnectActions) {
    ScreenScaffold(title = "Connect an AI agent", onBack = actions.onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding.padded()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "Add DroidHands to your agent, then tap Allow here. You choose what it may do, and can revoke it anytime.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = CardPadding),
            )
            ConnectAgentContent(
                main = main,
                onUseToken = actions.onUseToken,
                onOpenConnectionSettings = actions.onOpenConnectionSettings,
                onOpenMoreOptions = actions.onOpenMoreOptions,
                onConnected = "View" to { record -> actions.onOpenAgent(record.id) },
            )
        }
    }
}
