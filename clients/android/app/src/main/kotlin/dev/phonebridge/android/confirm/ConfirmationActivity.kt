package dev.phonebridge.android.confirm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonebridge.android.graph.BridgeGraph
import dev.phonebridge.android.ui.theme.PhoneBridgeTheme

/**
 * Full details + Allow/Deny for a pending request.
 *
 * Deliberately NOT shown over the lock screen: approving requires an unlocked phone. If the
 * keyguard is up anyway when Allow is tapped, the approval only takes effect after the user
 * dismisses it. The window is hardened with [applySecureWindow]: no screenshots or recording
 * (FLAG_SECURE), other apps' overlays hidden on Android 12+, obscured touches filtered (tapjacking)
 * and, on Android 14+, accessibility-data-sensitive so non-tool accessibility services can't read or
 * press it. While resumed, [ApprovalScreens] keeps PhoneBridge's own screen control paused.
 * Finishes when none of the requests it was opened for is pending any more.
 */
class ConfirmationActivity : ComponentActivity() {
    private val provider by lazy { BridgeGraph.get(this).confirmations }

    /** Ids this screen was opened for, in arrival order; the first one still pending is shown. */
    private val requestedIds = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        addRequest(intent)
        if (requestedIds.none { provider.pending(it) != null }) {
            finish()
            return
        }
        applySecureWindow()
        setContent {
            PhoneBridgeTheme {
                val pending by provider.pendingRequests.collectAsStateWithLifecycle()
                val current = requestedIds.firstNotNullOfOrNull { id -> pending.firstOrNull { it.id == id } }
                if (current == null) {
                    LaunchedEffect(Unit) { finish() }
                } else {
                    ConfirmationScreen(
                        request = current,
                        expiresAt = provider.expiresAt(current.id) ?: 0L,
                        onAllow = { allow(current.id) },
                        onDeny = { provider.resolve(current.id, approved = false) },
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
        addRequest(intent)
    }

    private fun addRequest(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_ID)?.takeIf { it.isNotBlank() } ?: return
        if (id !in requestedIds) requestedIds += id
    }

    private fun allow(id: String) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        provider.resolve(id, approved = true)
                    }
                },
            )
        } else {
            provider.resolve(id, approved = true)
        }
    }

    companion object {
        private const val EXTRA_ID = "dev.phonebridge.android.confirm.extra.REQUEST_ID"

        /** Explicit intent showing request [id] (adds FLAG_ACTIVITY_NEW_TASK for non-activity callers). */
        fun intent(context: Context, id: String): Intent =
            Intent(context, ConfirmationActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
