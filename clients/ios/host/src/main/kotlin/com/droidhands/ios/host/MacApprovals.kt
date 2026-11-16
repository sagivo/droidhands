package com.droidhands.ios.host

import com.droidhands.core.auth.GrantDraft
import com.droidhands.core.auth.TokenManager
import com.droidhands.core.confirm.ConfirmationProvider
import com.droidhands.core.confirm.ConfirmationRequest
import com.droidhands.core.confirm.ConfirmationResult
import com.droidhands.core.transport.PairingDecision
import com.droidhands.core.transport.PairingHandler
import com.droidhands.core.transport.PairingRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shows one dialog and returns the answer; [MacDialogs.ask] in production, a fake in tests. */
typealias Dialog = suspend (message: String, title: String, deny: String, allow: String, timeoutSeconds: Long) -> MacDialogs.Answer

/**
 * One-tap pairing on the Mac (docs/PROTOCOL.md §5, docs/IOS.md): a native dialog names the agent, the site it connects
 * from and the code its browser shows, with Deny and Allow. Allow issues a token for the whole grant at once, like the
 * Android grant's "Everything" ([GrantDraft.forNewAgent]: every scope and app, DroidHands's never-allow list
 * excepted, nothing asked again). A token no agent picked up is deleted.
 */
class MacPairingHandler(
    private val tokens: TokenManager,
    private val deviceName: () -> String,
    private val dialog: Dialog = MacDialogs::ask,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = System.err::println,
) : PairingHandler {
    override suspend fun onPairRequest(request: PairingRequest): PairingDecision {
        val name = MacDialogs.oneLine(request.clientName, 60)
        val host = MacDialogs.oneLine(request.redirectHost, 80)
        val code = MacDialogs.oneLine(request.displayCode, 12)
        val seconds = ((request.expiresAt - clock()) / 1000).coerceIn(1, 600)
        log("Pairing request from $name ($host), code $code: answer the dialog on this Mac.")
        val answer = dialog(pairingMessage(name, host, code, deviceName()), "DroidHands", "Deny", "Allow", seconds)
        if (answer != MacDialogs.Answer.ALLOW) {
            log("Pairing with $name: ${answer.name.lowercase().replace('_', ' ')}.")
            return PairingDecision.Denied(if (answer == MacDialogs.Answer.TIMED_OUT) "timeout" else "denied")
        }
        val grant = GrantDraft.forNewAgent().settings()
        val issued = withContext(Dispatchers.IO) {
            tokens.issue(
                name = request.clientName.trim().ifEmpty { "Agent" },
                scopes = grant.scopes,
                confirmPolicy = grant.confirmPolicy,
                ttlMillis = null,
                origin = "pairing",
                clientHost = request.redirectHost,
                appPolicy = grant.appPolicy,
                maxRisk = grant.maxRisk,
            )
        }
        log("Connected $name (agent ${issued.record.id}).")
        return PairingDecision.Approved(issued.plaintext, issued.record.id)
    }

    override fun onPairFinished(id: String, tokenId: String?, completed: Boolean) {
        if (completed || tokenId == null) return
        // No agent ever received it.
        runCatching { tokens.delete(tokenId) }.onFailure { log("Couldn't delete an unused pairing token: ${it.message}") }
    }

    companion object {
        /** The pairing dialog's text (pure). */
        fun pairingMessage(name: String, host: String, code: String, device: String): String =
            "Allow $name to use $device?\n\n" +
                "Agent: $name\nConnecting from: $host\nCode: $code (check that your browser shows the same code)\n\n" +
                "$name will be able to open and use every app on $device, see its screen and tap, type and swipe, without " +
                "asking again. Stop the DroidHands host (Ctrl-C) to cut it off."
    }
}

/**
 * Approvals on the Mac: when an agent's policy asks before an action (docs/SECURITY.md), a dialog shows what it wants
 * to do, with Deny and Allow. The server cancels the call at its timeout, which closes the dialog.
 */
class MacConfirmations(
    private val timeoutSeconds: Long,
    private val dialog: Dialog = MacDialogs::ask,
) : ConfirmationProvider {
    override suspend fun requestConfirmation(request: ConfirmationRequest): ConfirmationResult {
        val agent = MacDialogs.oneLine(request.tokenName, 60)
        val message = "$agent wants to: ${request.summary}\n\nTool: ${request.toolTitle} (${request.toolName})"
        return when (dialog(message, "DroidHands approval", "Deny", "Allow", timeoutSeconds)) {
            MacDialogs.Answer.ALLOW -> ConfirmationResult.APPROVED
            MacDialogs.Answer.TIMED_OUT -> ConfirmationResult.TIMED_OUT
            else -> ConfirmationResult.DENIED
        }
    }
}
