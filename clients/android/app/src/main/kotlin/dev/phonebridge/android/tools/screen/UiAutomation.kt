package dev.phonebridge.android.tools.screen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.SelfProtection
import dev.phonebridge.android.access.WindowRoot
import dev.phonebridge.android.tools.support.Accessibility
import dev.phonebridge.android.tools.support.DeviceState
import dev.phonebridge.android.tools.support.UnlockPrompt
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * State shared by every tool that drives the screen (ui_*, whatsapp_*, email_send): a single [Mutex] so only
 * one gesture/action sequence runs at a time, plus common preconditions (unlock, app access, service health)
 * and result helpers.
 */
object UiAutomation {
    private const val LOCK_WAIT_MS = 20_000L

    /** How long tools wait for the user to unlock ([awaitUsable]); add it to the tool's timeout. */
    const val UNLOCK_WAIT_MS = 60_000L

    private val mutex = Mutex()

    /**
     * Runs [block] holding the UI mutex; fails clearly if another sequence holds it longer than [waitMs]. Once the mutex
     * is held, re-checks that the agent is still allowed to act ([ScreenAccess.ensureStillAllowed]).
     */
    suspend fun <T> exclusive(access: ScreenAccess, waitMs: Long = LOCK_WAIT_MS, block: suspend () -> T): T {
        val acquired = withTimeoutOrNull(waitMs) { mutex.lock() } != null
        if (!acquired) throw ToolException("Another screen action is still running on the phone. Wait a moment and retry.")
        try {
            access.ensureStillAllowed()
            return block()
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Call right before every gesture or node action: throws if access was paused or the agent was revoked since the
     * call began, or while a PhoneBridge approval is pending or shown ([dev.phonebridge.android.tools.support.ApprovalGuard]),
     * so an agent can never tap an approval prompt, even one Android hides from the accessibility service. The service
     * and [NodeActions] re-check the approval synchronously right before dispatching.
     */
    suspend fun beforeAction(access: ScreenAccess) {
        access.ensureStillAllowed()
        SelfProtection.requireNoPendingApproval()
    }

    /** The connected accessibility service, or a ToolException telling the agent how to enable it. */
    fun service(): BridgeAccessibilityService = Accessibility.require()

    /** Reason for read tools (bridge_capabilities): accessibility off or stuck. Cheap. */
    fun unavailableReason(): String? = Accessibility.unavailableReason() ?: BridgeAccessibilityService.healthCached()

    /**
     * Reason for action tools (bridge_capabilities): accessibility off or stuck. A locked phone is not a reason:
     * the tools ask the user to unlock ([awaitUsable]).
     */
    @Suppress("UNUSED_PARAMETER")
    fun actionUnavailableReason(context: Context): String? = unavailableReason()

    /**
     * "Unlock to continue": returns at once when the screen is on and unlocked; otherwise asks the user to unlock
     * (a notification naming the calling agent and [action], e.g. "tap on the screen") and waits up to [UNLOCK_WAIT_MS].
     * Throws if the phone stayed locked ([nothingDone] is appended, e.g. "Nothing was sent."), or if access was paused or
     * the agent revoked during the wait.
     */
    suspend fun awaitUsable(context: Context, ctx: CallContext, action: String, nothingDone: String? = null) {
        if (isUsable(context)) return
        if (!UnlockPrompt.awaitUsable(context, ctx.tokenName, action, UNLOCK_WAIT_MS)) {
            throw ToolException(listOfNotNull(STAYED_LOCKED, nothingDone).joinToString(" "))
        }
        ctx.ensureStillAllowed()
    }

    /** Screen on and keyguard dismissed. */
    fun isUsable(context: Context): Boolean = DeviceState.isScreenOn(context) && !DeviceState.isLocked(context)

    /**
     * Interacting with a locked phone is refused: it would only reach the keyguard (and could be used to guess
     * the PIN). A cheap re-check once the UI mutex is held; [awaitUsable] does the waiting.
     */
    fun requireUnlocked(context: Context) = DeviceState.requireUnlocked(context, "Controlling the screen")

    /**
     * Precondition of every ui_* action: the service is not stuck (else its explanation is thrown), and no app
     * blocked for this agent is in the foreground (else the user's app-access refusal is thrown).
     */
    suspend fun requireOperable(service: BridgeAccessibilityService, access: ScreenAccess) = withContext(Dispatchers.IO) {
        val roots = service.rootNodesOrStuck()
        if (access.restricted) blockedForeground(roots, access)?.let { access.refuse(it) }
    }

    /** Lets the UI react to an action before reporting back. */
    suspend fun settle() = BridgeAccessibilityService.awaitIdle()

    /** Package of the primary window now (falls back to the tracked foreground app). */
    suspend fun foregroundPackage(service: BridgeAccessibilityService): String? = withContext(Dispatchers.IO) {
        service.rootNodes().firstOrNull()?.packageName ?: BridgeAccessibilityService.foregroundPackage
    }

    /**
     * Settles (unless [settleFirst] is false), then appends the current foreground app to [message]. If the action
     * brought an app blocked for this agent to the foreground, presses Home and throws instead, so the agent can
     * neither see nor operate it.
     */
    suspend fun report(service: BridgeAccessibilityService, access: ScreenAccess, message: String, settleFirst: Boolean = true): String {
        if (settleFirst) settle()
        if (access.restricted) {
            val blocked = withContext(Dispatchers.IO) { blockedForeground(service.rootNodes(), access) }
            if (blocked != null) {
                val wentHome = service.performGlobal(AccessibilityService.GLOBAL_ACTION_HOME)
                val label = withContext(Dispatchers.IO) { access.label(blocked) }
                throw ToolException(blockedAfterAction(message, label, wentHome))
            }
        }
        return "$message Foreground app: ${foregroundPackage(service) ?: "unknown"}."
    }

    private fun blockedForeground(roots: List<WindowRoot>, access: ScreenAccess): String? =
        access.visibility.blockedForeground(
            roots.firstOrNull()?.packageName,
            BridgeAccessibilityService.foregroundPackage,
            roots.map { it.packageName },
        )

    /** The error after an action opened a blocked app (pure). */
    fun blockedAfterAction(done: String, label: String, wentHome: Boolean): String = buildString {
        append(done).append(' ')
        append("This brought $label to the foreground, but the user blocked $label for this agent, so ")
        append(if (wentHome) "PhoneBridge went back to the home screen." else "PhoneBridge tried to go back to the home screen.")
        append(" Don't try to open $label another way; ask the user if it is needed.")
    }

    /** Sentence for the descriptions of the screen-control tools. */
    const val APPROVAL_PAUSE =
        "Screen control is paused (refused) while a PhoneBridge approval is waiting for the user; PhoneBridge's own " +
            "screens and notifications can never be operated."

    const val LOCKED_REASON = "The phone is locked. Ask the user to unlock it; screen control does not work on the lock screen."

    const val STAYED_LOCKED = "The phone stayed locked; the user didn't unlock within 60 s. Ask the user to unlock the phone, then retry."
}
