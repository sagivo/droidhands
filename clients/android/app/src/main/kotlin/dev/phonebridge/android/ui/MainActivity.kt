package dev.phonebridge.android.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.phonebridge.android.ui.deeplink.ConfigureLinkViewModel
import dev.phonebridge.android.ui.nav.NavState
import dev.phonebridge.android.ui.theme.PhoneBridgeTheme

/**
 * The app's single activity: onboarding, Home, Agents, Activity and Settings. Other components can
 * open a specific place with [intent] (e.g. a notification opening Home to show pending approvals).
 * It also handles `phonebridge://configure?relay=…&key=…` setup links (docs/PROTOCOL.md §5.5),
 * which are only ever applied after the user confirms.
 */
class MainActivity : ComponentActivity() {
    /** Place requested by the launching intent; consumed by the UI once applied. */
    private var requestedNav by mutableStateOf<NavState?>(null)

    private val links: ConfigureLinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The manifest theme is a light framework theme; avoid a white flash behind Compose in dark mode.
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawable(ColorDrawable(if (night) Color.BLACK else Color.WHITE))

        // After a configuration change or process restore the intent was already handled.
        if (savedInstanceState == null) handle(intent)
        setContent {
            PhoneBridgeTheme {
                PhoneBridgeRoot(
                    requestedNav = requestedNav,
                    onNavHandled = { requestedNav = null },
                    links = links,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (links.offer(intent)) return
        Destination.navStateFor(intent?.getStringExtra(EXTRA_DESTINATION))?.let { requestedNav = it }
    }

    companion object {
        /**
         * Optional String extra selecting where to open: "home", "agents", "activity", "settings",
         * "permissions" (or the wave-1 names "tokens" and "setup") and "connect". Unknown values are
         * ignored (the activity is exported as the launcher entry).
         */
        const val EXTRA_DESTINATION = "dev.phonebridge.android.extra.DESTINATION"

        /** Explicit intent that opens (or brings to front) the app on [destination]. */
        fun intent(context: Context, destination: String? = null): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (destination != null) putExtra(EXTRA_DESTINATION, destination) }
    }
}
