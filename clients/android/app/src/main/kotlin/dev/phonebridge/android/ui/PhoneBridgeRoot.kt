package dev.phonebridge.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.phonebridge.android.ui.deeplink.ConfigureLinkViewModel
import dev.phonebridge.android.ui.nav.NavState

/** Root composable: waits for the bridge graph (initialized off the main thread), then shows the app. */
@Composable
fun PhoneBridgeRoot(requestedNav: NavState?, onNavHandled: () -> Unit, links: ConfigureLinkViewModel) {
    val context = LocalContext.current
    var warmup by remember { mutableStateOf(if (GraphWarmup.isReady) WarmupState.Ready else WarmupState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        if (warmup != WarmupState.Ready) warmup = GraphWarmup.ensure(context.applicationContext)
    }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        when (val state = warmup) {
            WarmupState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading" })
            }
            is WarmupState.Failed -> StartupError(state.message, onRetry = {
                warmup = WarmupState.Loading
                attempt++
            })
            WarmupState.Ready -> AppRoot(requestedNav, onNavHandled, links)
        }
    }
}

@Composable
private fun StartupError(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PhoneBridge couldn't start", style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = onRetry) { Text("Try again") }
    }
}
