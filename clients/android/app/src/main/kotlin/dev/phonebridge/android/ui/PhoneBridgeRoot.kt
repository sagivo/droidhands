package dev.phonebridge.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.phonebridge.android.ui.activity.ActivityScreen
import dev.phonebridge.android.ui.components.LocalSnackbarHostState
import dev.phonebridge.android.ui.components.findActivity
import dev.phonebridge.android.ui.home.HomeScreen
import dev.phonebridge.android.ui.settings.SettingsScreen
import dev.phonebridge.android.ui.settings.SettingsViewModel
import dev.phonebridge.android.ui.setup.SetupScreen
import dev.phonebridge.android.ui.setup.SetupViewModel
import dev.phonebridge.android.ui.tokens.TokenScreen
import dev.phonebridge.android.ui.tokens.TokensRoute
import dev.phonebridge.android.ui.tokens.TokensViewModel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** Root composable: waits for the bridge graph (initialized off the main thread), then shows the app. */
@Composable
fun PhoneBridgeRoot(requestedDestination: Destination?, onDestinationHandled: () -> Unit) {
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
            WarmupState.Ready -> AppScaffold(requestedDestination, onDestinationHandled)
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

@Composable
private fun AppScaffold(requestedDestination: Destination?, onDestinationHandled: () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val snackbar = remember { SnackbarHostState() }
    var current by rememberSaveable { mutableStateOf(Destination.HOME) }
    val tabState = rememberSaveableStateHolder()

    val main: MainViewModel = viewModel()
    val setup: SetupViewModel = viewModel()
    val tokens: TokensViewModel = viewModel()
    val settings: SettingsViewModel = viewModel()

    val pending by main.pendingApprovals.collectAsStateWithLifecycle()
    val setupState by setup.state.collectAsStateWithLifecycle()
    val tokenScreen by tokens.screen.collectAsStateWithLifecycle()

    LaunchedEffect(requestedDestination) {
        if (requestedDestination != null) {
            current = requestedDestination
            onDestinationHandled()
        }
    }
    LaunchedEffect(Unit) {
        merge(main.messages, tokens.messages, settings.messages).collect { message ->
            launch { snackbar.showSnackbar(message) }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { setup.refresh(activity) }
    FirstLaunchNotificationPrompt(setup)

    // The token editor and the one-time token screen are full-screen steps without the nav bar.
    val immersive = current == Destination.TOKENS && tokenScreen !is TokenScreen.List
    BackHandler(enabled = current != Destination.HOME && !immersive) { current = Destination.HOME }

    CompositionLocalProvider(LocalSnackbarHostState provides snackbar) {
        Scaffold(
            snackbarHost = {
                // Without the nav bar, keep snackbars above the system bar and the screen's sticky action bar.
                SnackbarHost(
                    snackbar,
                    modifier = if (immersive) {
                        Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                            .padding(bottom = 72.dp)
                    } else {
                        Modifier
                    },
                )
            },
            bottomBar = {
                if (!immersive) {
                    NavigationBar {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = current == destination,
                                onClick = { current = destination },
                                icon = {
                                    NavIcon(
                                        destination,
                                        pendingCount = pending.size,
                                        setupMissing = setupState.loaded && !setupState.progress.essentialComplete,
                                    )
                                },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
            },
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
            ) {
                tabState.SaveableStateProvider(current.route) {
                    when (current) {
                        Destination.HOME -> HomeScreen(main, setupState, onNavigate = { current = it })
                        Destination.SETUP -> SetupScreen(setup)
                        Destination.TOKENS -> TokensRoute(tokens, main)
                        Destination.ACTIVITY -> ActivityScreen(main)
                        Destination.SETTINGS -> SettingsScreen(settings)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavIcon(destination: Destination, pendingCount: Int, setupMissing: Boolean) {
    when {
        destination == Destination.HOME && pendingCount > 0 -> BadgedBox(
            badge = { Badge { Text("$pendingCount") } },
            modifier = Modifier.semantics { contentDescription = "$pendingCount pending approvals" },
        ) { Icon(destination.icon, contentDescription = null) }
        destination == Destination.SETUP && setupMissing -> BadgedBox(
            badge = { Badge() },
            modifier = Modifier.semantics { contentDescription = "Setup incomplete" },
        ) { Icon(destination.icon, contentDescription = null) }
        else -> Icon(destination.icon, contentDescription = null)
    }
}

/** Asks for POST_NOTIFICATIONS once, on the first launch (Android 13+): approvals arrive as notifications. */
@Composable
private fun FirstLaunchNotificationPrompt(setup: SetupViewModel) {
    val activity = LocalContext.current.findActivity()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setup.refresh(activity) }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && setup.shouldPromptNotificationsOnFirstLaunch()) {
            setup.onPermissionsRequested(listOf(Manifest.permission.POST_NOTIFICATIONS))
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
