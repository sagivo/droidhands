package dev.phonebridge.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import dev.phonebridge.android.ui.components.AppsIcon
import dev.phonebridge.android.ui.nav.NavState
import dev.phonebridge.android.ui.nav.Screen

/** Top-level destinations in the navigation bar. [route] is a value of [MainActivity.EXTRA_DESTINATION]. */
enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    AGENTS("agents", "Agents", Icons.Filled.Face),
    APPS("apps", "Apps", AppsIcon),
    ACTIVITY("activity", "Activity", Icons.AutoMirrored.Filled.List),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
    ;

    companion object {
        /** Tab for [route]; the wave-1 names "tokens" and "setup" map to their new homes. */
        fun fromRoute(route: String?): Destination? = when (route?.lowercase()) {
            null -> null
            "tokens" -> AGENTS
            "setup", "permissions" -> SETTINGS
            else -> entries.firstOrNull { it.route.equals(route, ignoreCase = true) }
        }

        /**
         * Where an external request for [route] lands: a tab, or a page on top of one ("setup" and
         * "permissions" open the checklist, "connect" the connect-an-agent page). Null if unknown.
         */
        fun navStateFor(route: String?): NavState? = when (route?.lowercase()) {
            "setup", "permissions" -> NavState(SETTINGS, listOf(Screen.Permissions))
            "connect" -> NavState(HOME, listOf(Screen.ConnectAgent))
            else -> fromRoute(route)?.let { NavState(it) }
        }
    }
}
