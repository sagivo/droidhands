package com.droidhands.android.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import com.droidhands.android.ui.activity.ActivityQuery
import com.droidhands.android.ui.activity.ActivityScreen
import com.droidhands.android.ui.agents.AgentAppsScreen
import com.droidhands.android.ui.agents.AgentDetailActions
import com.droidhands.android.ui.agents.AgentDetailScreen
import com.droidhands.android.ui.agents.AgentPresentation
import com.droidhands.android.ui.agents.AgentsScreen
import com.droidhands.android.ui.agents.AgentsViewModel
import com.droidhands.android.ui.agents.RevokeAllDialog
import com.droidhands.android.ui.apps.AppDetailScreen
import com.droidhands.android.ui.apps.AppsScreen
import com.droidhands.android.ui.apps.AppsViewModel
import com.droidhands.android.ui.components.LocalSnackbarHostState
import com.droidhands.android.ui.components.findActivity
import com.droidhands.android.ui.components.rememberNow
import com.droidhands.android.ui.connect.ConnectActions
import com.droidhands.android.ui.connect.ConnectAgentScreen
import com.droidhands.android.ui.deeplink.ConfigureLinkDialog
import com.droidhands.android.ui.deeplink.ConfigureLinkViewModel
import com.droidhands.android.ui.home.HomeActions
import com.droidhands.android.ui.home.HomeScreen
import com.droidhands.android.ui.nav.NavState
import com.droidhands.android.ui.nav.Screen
import com.droidhands.android.ui.onboarding.OnboardingActions
import com.droidhands.android.ui.onboarding.OnboardingScreen
import com.droidhands.android.ui.settings.NeverAllowScreen
import com.droidhands.android.ui.settings.SettingsActions
import com.droidhands.android.ui.settings.SettingsScreen
import com.droidhands.android.ui.settings.SettingsViewModel
import com.droidhands.android.ui.setup.SetupScreen
import com.droidhands.android.ui.setup.SetupUiState
import com.droidhands.android.ui.setup.SetupViewModel
import com.droidhands.android.ui.theme.TINT_ALPHA
import com.droidhands.android.ui.tokens.TokenFlow
import com.droidhands.android.ui.tokens.TokenScreen
import com.droidhands.android.ui.tokens.TokensViewModel
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
 * (first run) above pages above the five tabs. Hosts the snackbar, the navigation bar, back
 * handling and the setup-link confirmation.
 */
@Composable
internal fun AppRoot(
    requestedNav: NavState?,
    onNavHandled: () -> Unit,
    requestedApproval: String?,
    onApprovalHandled: () -> Unit,
    links: ConfigureLinkViewModel,
) {
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
    val appsVm: AppsViewModel = viewModel()

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
    LaunchedEffect(requestedApproval) {
        if (requestedApproval != null) {
            activity?.let { main.openApproveLink(it, requestedApproval) }
            onApprovalHandled()
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
        merge(main.messages, tokens.messages, settings.messages, agents.messages, appsVm.messages).collect { message ->
            launch { snackbar.showSnackbar(message) }
        }
    }
    // First run: go online through the build's default relay (once per install; a no-op afterwards).
    // Not while a setup link waits for an answer: its relay is about to be chosen instead.
    LaunchedEffect(Unit) {
        if (links.pending.value == null) main.applyDefaultRelayIfNeeded()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { setup.refresh(activity) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { main.checkForUpdates() }
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
                    val scheme = MaterialTheme.colorScheme
                    val itemColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = scheme.primary,
                        selectedTextColor = scheme.primary,
                        indicatorColor = scheme.primary.copy(alpha = TINT_ALPHA),
                        unselectedIconColor = scheme.onSurfaceVariant,
                        unselectedTextColor = scheme.onSurfaceVariant,
                    )
                    Column {
                        HorizontalDivider(thickness = 0.5.dp, color = scheme.outlineVariant)
                        NavigationBar(containerColor = scheme.surfaceContainerLowest, tonalElevation = 0.dp) {
                            Destination.entries.forEach { destination ->
                                NavigationBarItem(
                                    selected = nav.tab == destination,
                                    onClick = { selectTab(destination) },
                                    colors = itemColors,
                                    icon = {
                                        NavIcon(
                                            destination,
                                            waitingCount = pending.size + pairings.count { it.expiresAt > now },
                                            setupMissing = setupState.loaded && !setupState.progress.essentialComplete,
                                        )
                                    },
                                    label = { Text(destination.label, style = MaterialTheme.typography.labelMedium) },
                                )
                            }
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
                            Page(screen, main, setup, settings, agents, appsVm, tokens, push, pop, onSelectTab = selectTab)
                        }
                    }
                    Layer.TAB -> holder.SaveableStateProvider(nav.tab.route) {
                        Tab(
                            nav.tab, main, setupState, settings, agents, appsVm, push,
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
    apps: AppsViewModel,
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
        Destination.APPS -> AppsScreen(apps, onOpenApp = { push(Screen.AppDetail(it)) })
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
    apps: AppsViewModel,
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
        is Screen.AppDetail -> AppDetailScreen(apps, screen.packageName, onBack = pop)
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
