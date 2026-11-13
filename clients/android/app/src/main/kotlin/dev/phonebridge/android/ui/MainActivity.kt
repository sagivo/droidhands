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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.phonebridge.android.ui.theme.PhoneBridgeTheme

/**
 * The app's single activity: setup checklist, tokens, activity log, settings and the pause switch.
 * Other components can open a specific tab with [intent] (e.g. a notification opening Home to show
 * pending approvals).
 */
class MainActivity : ComponentActivity() {
    /** Tab requested by the launching intent; consumed by the UI once applied. */
    private var requestedDestination by mutableStateOf<Destination?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The manifest theme is a light framework theme; avoid a white flash behind Compose in dark mode.
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.setBackgroundDrawable(ColorDrawable(if (night) Color.BLACK else Color.WHITE))

        if (savedInstanceState == null) requestedDestination = destinationOf(intent)
        setContent {
            PhoneBridgeTheme {
                PhoneBridgeRoot(
                    requestedDestination = requestedDestination,
                    onDestinationHandled = { requestedDestination = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        destinationOf(intent)?.let { requestedDestination = it }
    }

    private fun destinationOf(intent: Intent?): Destination? = Destination.fromRoute(intent?.getStringExtra(EXTRA_DESTINATION))

    companion object {
        /**
         * Optional String extra selecting the initial tab: "home", "setup", "tokens", "activity" or
         * "settings". Unknown values are ignored (the activity is exported as the launcher entry).
         */
        const val EXTRA_DESTINATION = "dev.phonebridge.android.extra.DESTINATION"

        /** Explicit intent that opens (or brings to front) the app on [destination]. */
        fun intent(context: Context, destination: String? = null): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (destination != null) putExtra(EXTRA_DESTINATION, destination) }
    }
}
