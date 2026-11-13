package dev.phonebridge.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** Top-level destinations in the navigation bar. [route] is the value of [MainActivity.EXTRA_DESTINATION]. */
enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    SETUP("setup", "Setup", Icons.Filled.Build),
    TOKENS("tokens", "Tokens", Icons.Filled.Lock),
    ACTIVITY("activity", "Activity", Icons.AutoMirrored.Filled.List),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
    ;

    companion object {
        fun fromRoute(route: String?): Destination? = entries.firstOrNull { it.route.equals(route, ignoreCase = true) }
    }
}
