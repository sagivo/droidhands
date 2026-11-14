package dev.phonebridge.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.phonebridge.android.ui.activity.ActivityQuery
import dev.phonebridge.android.ui.activity.ActivityScreen
import dev.phonebridge.android.ui.agents.AgentAppsScreen
import dev.phonebridge.android.ui.agents.AgentDetailActions
import dev.phonebridge.android.ui.agents.AgentDetailScreen
import dev.phonebridge.android.ui.agents.AgentPresentation
import dev.phonebridge.android.ui.agents.AgentsScreen
import dev.phonebridge.android.ui.agents.AgentsViewModel
import dev.phonebridge.android.ui.agents.RevokeAllDialog
import dev.phonebridge.android.ui.components.LocalSnackbarHostState
import dev.phonebridge.android.ui.components.findActivity
import dev.phonebridge.android.ui.components.rememberNow
import dev.phonebridge.android.ui.connect.ConnectActions
import dev.phonebridge.android.ui.connect.ConnectAgentScreen
import dev.phonebridge.android.ui.deeplink.ConfigureLinkDialog
import dev.phonebridge.android.ui.deeplink.ConfigureLinkViewModel
import dev.phonebridge.android.ui.home.HomeActions
import dev.phonebridge.android.ui.home.HomeScreen
import dev.phonebridge.android.ui.nav.NavState
import dev.phonebridge.android.ui.nav.Screen
import dev.phonebridge.android.ui.onboarding.OnboardingActions
import dev.phonebridge.android.ui.onboarding.OnboardingScreen
import dev.phonebridge.android.ui.settings.NeverAllowScreen
import dev.phonebridge.android.ui.settings.SettingsActions
import dev.phonebridge.android.ui.settings.SettingsScreen
import dev.phonebridge.android.ui.settings.SettingsViewModel
import dev.phonebridge.android.ui.setup.SetupScreen
import dev.phonebridge.android.ui.setup.SetupUiState
import dev.phonebridge.android.ui.setup.SetupViewModel
import dev.phonebridge.android.ui.tokens.TokenFlow
import dev.phonebridge.android.ui.tokens.TokenScreen
import dev.phonebridge.android.ui.tokens.TokensViewModel
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

private const val TOKEN_FLOW_KEY = "token-flow"
private const val ONBOARDING_KEY = "onboarding"

/** What fills the window, top-most first. */
private enum class Layer { TOKEN_FLOW, LOADING, ONBOARDING, PAGE, TAB }

private val NavStateSaver = Saver<NavState, ArrayList<String>>(
    save = { ArrayList(it.encode()) },
    restore = { NavState.decode(it) },
)

/**
 * The app once the graph is warm: the manual token flow (when active) above the onboarding wizard
 * (first run) above pages above the four tabs. Hosts the snackbar, the navigation bar, back
 * handling and the setup-link confirmation.
 */
@Composable
internal fun AppRoot(requestedNav: NavState?, onNavHandled: () -> Unit, links: ConfigureLinkViewModel) {
    val activity = LocalContext.current.findActivity()
    val snackbar = remember { SnackbarHostState() }
    var nav by rememberSaveable(stateSaver = NavStateSaver) { mutableStateOf(NavState()) }
    val holder = rememberSaveableStateHolder()
    var confirmRevokeAll by rememberSaveable { mutableStateOf(false) }

    val main: MainViewModel = viewModel()
    val setup: SetupViewModel = viewModel()
    val tokens: TokensViewModel = viewModel()
    val settings: SettingsViewModel = viewModel()
    val agents: AgentsViewModel = viewModel()

    val pending by main.pendingApprovals.collectAsStateWithLifecycle()
    val pairings by main.pendingPairings.collectAsStateWithLifecycle()
    val setupState by setup.state.collectAsStateWithLifecycle()
    val tokenScreen by tokens.screen.collectAsStateWithLifecycle()
    val onboarding by main.showOnboarding.collectAsStateWithLifecycle()
    val link by links.pending.collectAsStateWithLifecycle()
    val bridgeSettings by main.settings.collectAsStateWithLifecycle()
    val tokenList by main.tokens.collectAsStateWithLifecycle()
    val now by rememberNow()

    // Closed pages forget their UI state, so opening them again starts fresh (e.g. a new
    // "waiting for your agent" baseline on the Connect page).
    val navigate: (NavState) -> Unit = { next ->
        val open = next.stack.mapTo(HashSet()) { it.route }
        nav.stack.forEach { if (it.route !in open) holder.removeState(it.route) }
        nav = next
    }
    LaunchedEffect(requestedNav) {
        if (requestedNav != null) {
            navigate(requestedNav)
            onNavHandled()
        }
    }
    // The token flow and the wizard must start from scratch next time (the token screen's
    // "copied it?" guard must never be restored).
    LaunchedEffect(tokenScreen is TokenScreen.List) {
        if (tokenScreen is TokenScreen.List) holder.removeState(TOKEN_FLOW_KEY)
    }
    // Pages of agents that were deleted (here or elsewhere) close by themselves.
    LaunchedEffect(tokenList) {
        val ids = tokenList.mapTo(HashSet()) { it.id }
        navigate(nav.prune { screen ->
            when (screen) {
                is Screen.AgentDetail -> screen.tokenId in ids
                is Screen.AgentApps -> screen.tokenId in ids
                else -> true
            }
        })
    }
    LaunchedEffect(Unit) {
        merge(main.messages, tokens.messages, settings.messages, agents.messages).collect { message ->
            launch { snackbar.showSnackbar(message) }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { setup.refresh(activity) }
    // Only for people who never see the wizard this session (it asks for notifications itself).
    val skipsWizard = remember(onboarding != null) { onboarding == false }
    if (skipsWizard) FirstLaunchNotificationPrompt(setup)

    val layer = when {
        tokenScreen !is TokenScreen.List -> Layer.TOKEN_FLOW
        onboarding == null -> Layer.LOADING
        onboarding == true -> Layer.ONBOARDING
        nav.top != null -> Layer.PAGE
        else -> Layer.TAB
    }
    val push: (Screen) -> Unit = { navigate(nav.push(it)) }
    val pop: () -> Unit = { nav.back()?.let(navigate) }
    val selectTab: (Destination) -> Unit = { navigate(nav.select(it)) }
    val finishOnboarding: () -> Unit = {
        holder.removeState(ONBOARDING_KEY)
        main.finishOnboarding()
    }
    BackHandler(enabled = (layer == Layer.PAGE || layer == Layer.TAB) && nav.back() != null, onBack = pop)

    CompositionLocalProvider(LocalSnackbarHostState provides snackbar) {
        Scaffold(
            snackbarHost = {
                // Without the nav bar, keep snackbars above the system bar and the screen's sticky action bar.
                SnackbarHost(
                    snackbar,
                    modifier = if (layer != Layer.TAB) {
                        Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                            .padding(bottom = 72.dp)
                    } else {
                        Modifier
                    },
                )
            },
            bottomBar = {
                if (layer == Layer.TAB) {
                    NavigationBar {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = nav.tab == destination,
                                onClick = { selectTab(destination) },
                                icon = {
                                    NavIcon(
                                        destination,
                                        waitingCount = pending.size + pairings.count { it.expiresAt > now },
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
                when (layer) {
                    Layer.TOKEN_FLOW -> holder.SaveableStateProvider(TOKEN_FLOW_KEY) { TokenFlow(tokens, main) }
                    Layer.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading" })
                    }
                    Layer.ONBOARDING -> holder.SaveableStateProvider(ONBOARDING_KEY) {
                        OnboardingScreen(main, setup, OnboardingActions(onFinish = finishOnboarding, onUseToken = tokens::startCreate))
                    }
                    Layer.PAGE -> {
                        val screen = nav.top ?: return@Box
                        holder.SaveableStateProvider(screen.route) {
                            Page(screen, main, setup, settings, agents, tokens, push, pop, onSelectTab = selectTab)
                        }
                    }
                    Layer.TAB -> holder.SaveableStateProvider(nav.tab.route) {
                        Tab(
                            nav.tab, main, setupState, settings, agents, push,
                            onSelectTab = selectTab,
                            onRevokeAll = { confirmRevokeAll = true },
                        )
                    }
                }
            }
        }
    }

    if (layer != Layer.LOADING) {
        link?.let { result ->
            ConfigureLinkDialog(result, bridgeSettings, onConfirm = main::applyConfigureLink, onDismiss = links::dismiss)
        }
    }
    if (confirmRevokeAll) {
        RevokeAllDialog(
            activeCount = AgentPresentation.activeCount(tokenList, now),
            onConfirm = main::revokeAll,
            onDismiss = { confirmRevokeAll = false },
        )
    }
}

@Composable
private fun Tab(
    tab: Destination,
    main: MainViewModel,
    setupState: SetupUiState,
    settings: SettingsViewModel,
    agents: AgentsViewModel,
    push: (Screen) -> Unit,
    onSelectTab: (Destination) -> Unit,
    onRevokeAll: () -> Unit,
) {
    when (tab) {
        Destination.HOME -> HomeScreen(
            main,
            setupState,
            HomeActions(
                onConnectAgent = { push(Screen.ConnectAgent) },
                onOpenPermissions = { push(Screen.Permissions) },
                onOpenActivity = { onSelectTab(Destination.ACTIVITY) },
                onOpenAgents = { onSelectTab(Destination.AGENTS) },
                onRevokeAll = onRevokeAll,
            ),
        )
        Destination.AGENTS -> AgentsScreen(agents, onOpenAgent = { push(Screen.AgentDetail(it)) }, onConnect = { push(Screen.ConnectAgent) })
        Destination.ACTIVITY -> ActivityScreen(main)
        Destination.SETTINGS -> {
            val tokens by main.tokens.collectAsStateWithLifecycle()
            val now by rememberNow()
            SettingsScreen(
                settings,
                setupState,
                activeAgents = AgentPresentation.activeCount(tokens, now),
                actions = SettingsActions(
                    onOpenPermissions = { push(Screen.Permissions) },
                    onOpenNeverAllow = { push(Screen.NeverAllowApps) },
                    onRevokeAll = onRevokeAll,
                    onRunSetupAgain = main::reopenOnboarding,
                ),
            )
        }
    }
}

@Composable
private fun Page(
    screen: Screen,
    main: MainViewModel,
    setup: SetupViewModel,
    settings: SettingsViewModel,
    agents: AgentsViewModel,
    tokens: TokensViewModel,
    push: (Screen) -> Unit,
    pop: () -> Unit,
    onSelectTab: (Destination) -> Unit,
) {
    when (screen) {
        is Screen.AgentDetail -> AgentDetailScreen(
            agents,
            screen.tokenId,
            AgentDetailActions(
                onBack = pop,
                onChooseApps = { push(Screen.AgentApps(screen.tokenId)) },
                onSeeActivity = {
                    main.activityQuery = ActivityQuery(agent = screen.tokenId)
                    onSelectTab(Destination.ACTIVITY)
                },
            ),
        )
        is Screen.AgentApps -> AgentAppsScreen(agents, screen.tokenId, onBack = pop)
        Screen.ConnectAgent -> ConnectAgentScreen(
            main,
            ConnectActions(
                onBack = pop,
                onUseToken = tokens::startCreate,
                onOpenConnectionSettings = { onSelectTab(Destination.SETTINGS) },
                onOpenAgent = { id ->
                    pop()
                    push(Screen.AgentDetail(id))
                },
            ),
        )
        Screen.Permissions -> SetupScreen(setup, onBack = pop)
        Screen.NeverAllowApps -> NeverAllowScreen(settings, onBack = pop)
    }
}

@Composable
private fun NavIcon(destination: Destination, waitingCount: Int, setupMissing: Boolean) {
    when {
        destination == Destination.HOME && waitingCount > 0 -> BadgedBox(
            badge = { Badge { Text("$waitingCount") } },
            modifier = Modifier.semantics { contentDescription = "$waitingCount waiting for you" },
        ) { Icon(destination.icon, contentDescription = null) }
        destination == Destination.SETTINGS && setupMissing -> BadgedBox(
            badge = { Badge() },
            modifier = Modifier.semantics { contentDescription = "Setup incomplete" },
        ) { Icon(destination.icon, contentDescription = null) }
        else -> Icon(destination.icon, contentDescription = null)
    }
}

/** Asks for POST_NOTIFICATIONS once (Android 13+) for people who skip onboarding: approvals arrive as notifications. */
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
