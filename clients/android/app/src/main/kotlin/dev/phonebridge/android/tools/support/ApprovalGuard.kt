package dev.phonebridge.android.tools.support

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * True while any approval UI exists: a pending confirmation or pairing prompt, or a PhoneBridge approval
 * screen is visible. Screen-control tools MUST check [isActive] immediately before every gesture or node
 * action (not only at tool start) and refuse while it is true, so an agent can never approve its own
 * requests — even when Android hides those windows from our accessibility service.
 *
 * Sources: "confirmations" (pending confirmations), "pairing" (pending pairings), "approval_screen"
 * (ConfirmationActivity/PairingActivity/configure dialog resumed). Thread-safe.
 */
object ApprovalGuard {
    private val sources = ConcurrentHashMap<String, Boolean>()
    private val state = MutableStateFlow(false)

    val active: StateFlow<Boolean> = state.asStateFlow()

    val isActive: Boolean get() = state.value

    fun set(source: String, active: Boolean) {
        sources[source] = active
        state.value = sources.values.any { it }
    }

    const val REFUSAL =
        "A PhoneBridge approval is waiting for the user, so screen control is paused until they answer it on the phone. " +
            "Do not try to interact with approval prompts; wait and retry after the user has decided."
}
