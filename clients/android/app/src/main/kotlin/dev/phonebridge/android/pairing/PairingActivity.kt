package dev.phonebridge.android.pairing

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.phonebridge.android.confirm.ApprovalScreens
import dev.phonebridge.android.confirm.applySecureWindow
import dev.phonebridge.android.graph.BridgeGraph
import dev.phonebridge.android.ui.theme.PhoneBridgeTheme
import dev.phonebridge.core.transport.PairingRequest
import kotlinx.coroutines.launch

/**
 * Approve or deny a one-tap agent pairing (docs/PROTOCOL.md §5.3 step 7): client name, redirect
 * host, the code to compare with the browser, access level, confirmation policy and name.
 *
 * Like the action-approval screen it is not shown over the lock screen, Allow additionally asks
 * the keyguard to be dismissed first, and the window is hardened with [applySecureWindow] (no
 * screenshots, overlays hidden, obscured touches filtered). While resumed, [ApprovalScreens] keeps
 * PhoneBridge's own screen control paused. Finishes as soon as the request is no longer pending
 * (decided elsewhere, cancelled by the relay, or expired).
 */
class PairingActivity : ComponentActivity() {
    private val coordinator by lazy { BridgeGraph.get(this).pairing }

    private var requestId by mutableStateOf<String?>(null)
    private var approving by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    /**
     * The last request shown, kept while an approval completes (it leaves the pending list before
     * [PairingCoordinator.approve] returns). Plain field: written during composition, never observed.
     */
    private var shown: PairingRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestId = savedInstanceState?.getString(STATE_ID) ?: idOf(intent)
        if (requestId?.let(coordinator::pending) == null) {
            Toast.makeText(applicationContext, PairingPresentation.EXPIRED_MESSAGE, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        applySecureWindow()
        setContent {
            PhoneBridgeTheme {
                val pending by coordinator.pendingPairings.collectAsStateWithLifecycle()
                val current = requestId?.let { id -> pending.firstOrNull { it.id == id } }
                if (current != null) shown = current
                val request = current ?: shown?.takeIf { it.id == requestId }
                if (request == null || (current == null && !approving)) {
                    LaunchedEffect(Unit) { finish() }
                } else {
                    PairingScreen(
                        request = request,
                        approving = approving,
                        error = error,
                        onAllow = { choice -> allow(request.id, choice) },
                        onDeny = {
                            coordinator.deny(request.id)
                            finish()
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ApprovalScreens.resumed(this)
    }

    override fun onPause() {
        ApprovalScreens.paused(this)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Switch to a newer request only when idle; the current one stays reachable from its notification.
        val id = idOf(intent) ?: return
        if (!approving && id != requestId && coordinator.pending(id) != null) {
            requestId = id
            shown = null
            error = null
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        requestId?.let { outState.putString(STATE_ID, it) }
    }

    private fun allow(id: String, choice: PairingChoice) {
        if (approving) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() = approveNow(id, choice)
                },
            )
        } else {
            approveNow(id, choice)
        }
    }

    private fun approveNow(id: String, choice: PairingChoice) {
        if (approving) return
        approving = true
        error = null
        lifecycleScope.launch {
            when (val result = coordinator.approve(id, choice)) {
                is PairingApproval.Connected -> {
                    Toast.makeText(applicationContext, PairingPresentation.connectedMessage(result.token.name), Toast.LENGTH_LONG).show()
                    finish()
                }
                PairingApproval.NoLongerPending -> {
                    Toast.makeText(applicationContext, PairingPresentation.EXPIRED_MESSAGE, Toast.LENGTH_LONG).show()
                    finish()
                }
                is PairingApproval.Failed -> {
                    approving = false
                    error = result.message
                }
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "dev.phonebridge.android.pairing.extra.REQUEST_ID"
        private const val STATE_ID = "request_id"

        /** Explicit intent showing pairing request [id] (adds FLAG_ACTIVITY_NEW_TASK for non-activity callers). */
        fun intent(context: Context, id: String): Intent =
            Intent(context, PairingActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun idOf(intent: Intent?): String? = intent?.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() }
    }
}
