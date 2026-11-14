package dev.phonebridge.android.ui.nav

import dev.phonebridge.android.ui.Destination

/** A full-screen page opened on top of a tab (back returns to what was below). */
sealed interface Screen {
    /** Stable string form, used to save the back stack across configuration changes. */
    val route: String

    data class AgentDetail(val tokenId: String) : Screen {
        override val route get() = "agent/$tokenId"
    }

    /** App picker for one agent's app access ([dev.phonebridge.core.auth.AppPolicy]). */
    data class AgentApps(val tokenId: String) : Screen {
        override val route get() = "agent-apps/$tokenId"
    }

    /** "Connect an AI agent": connector URL + per-client instructions. */
    data object ConnectAgent : Screen {
        override val route = "connect"
    }

    /** Settings > Permissions & access (the setup checklist). */
    data object Permissions : Screen {
        override val route = "permissions"
    }

    /** Apps > one app: its actions, "Block for all agents", and ways to improve its integration. */
    data class AppDetail(val packageName: String) : Screen {
        override val route get() = "app/$packageName"
    }

    /** Settings > Security > Never-allow apps. */
    data object NeverAllowApps : Screen {
        override val route = "never-allow"
    }

    companion object {
        fun fromRoute(route: String): Screen? = when {
            route == ConnectAgent.route -> ConnectAgent
            route == Permissions.route -> Permissions
            route == NeverAllowApps.route -> NeverAllowApps
            route.startsWith("agent/") -> route.removePrefix("agent/").takeIf { it.isNotEmpty() }?.let(::AgentDetail)
            route.startsWith("agent-apps/") -> route.removePrefix("agent-apps/").takeIf { it.isNotEmpty() }?.let(::AgentApps)
            route.startsWith("app/") -> route.removePrefix("app/").takeIf { it.isNotEmpty() }?.let(::AppDetail)
            else -> null
        }
    }
}

/**
 * Where the user is: the selected bottom-bar [tab] and a stack of pages opened above it (top last).
 * Immutable and pure (unit-tested); the root keeps it in saveable Compose state via [encode]/[decode].
 */
data class NavState(val tab: Destination = Destination.HOME, val stack: List<Screen> = emptyList()) {
    val top: Screen? get() = stack.lastOrNull()

    /** Opens [screen] on top; a page already on top is not stacked twice. */
    fun push(screen: Screen): NavState = if (top == screen) this else copy(stack = stack + screen)

    /** Closes the top page; on a tab other than Home, back goes to Home; null = leave the app. */
    fun back(): NavState? = when {
        stack.isNotEmpty() -> copy(stack = stack.dropLast(1))
        tab != Destination.HOME -> NavState(Destination.HOME)
        else -> null
    }

    /** Switches tab and closes every page. */
    fun select(tab: Destination): NavState = NavState(tab)

    /** Drops pages that no longer make sense (e.g. the detail page of a deleted agent). */
    fun prune(keep: (Screen) -> Boolean): NavState = if (stack.all(keep)) this else copy(stack = stack.filter(keep))

    fun encode(): List<String> = listOf(tab.route) + stack.map { it.route }

    companion object {
        fun decode(saved: List<String>?): NavState {
            if (saved.isNullOrEmpty()) return NavState()
            val tab = Destination.fromRoute(saved.first()) ?: Destination.HOME
            return NavState(tab, saved.drop(1).mapNotNull(Screen::fromRoute))
        }
    }
}
