package dev.phonebridge.android.ui.deeplink

import android.content.Intent
import androidx.annotation.MainThread
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds a `phonebridge://configure` link until the user confirms or dismisses it. Kept in a
 * ViewModel (survives rotation) rather than saved instance state, because the link carries the
 * relay access key. Has no dependency on the bridge graph, so the activity can hand it the
 * launching intent before the graph is warm.
 */
class ConfigureLinkViewModel : ViewModel() {
    private val _pending = MutableStateFlow<ConfigureLinkResult?>(null)

    /** The link awaiting a decision (valid or not), or null. */
    val pending: StateFlow<ConfigureLinkResult?> = _pending.asStateFlow()

    /**
     * Offers [intent]'s link if it is an ACTION_VIEW setup link. Intents relaunched from Recents
     * are ignored: the user already decided about that link.
     * @return true if the intent was a setup link.
     */
    @MainThread
    fun offer(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        val uri = intent.dataString ?: return false
        if (!ConfigureLinks.isConfigureLink(uri)) return false
        _pending.value = ConfigureLinks.parse(uri)
        return true
    }

    /**
     * Offers a scanned QR code, or a setup link pasted into the relay form, read by [SetupQrPayload]. Like a
     * link, it is only applied once the user confirms; an unusable one shows why.
     */
    @MainThread
    fun offerScanned(payload: String?) {
        _pending.value = SetupQrPayload.parse(payload)
    }

    @MainThread
    fun dismiss() {
        _pending.value = null
    }
}
