package dev.phonebridge.android.tools.screen

import android.content.Context
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.tools.support.Accessibility
import dev.phonebridge.android.tools.support.DeviceState
import dev.phonebridge.core.tools.ToolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * State shared by every tool that drives the screen (ui_*, whatsapp_*): a single [Mutex] so only
 * one gesture/action sequence runs at a time, plus common preconditions and result helpers.
 */
object UiAutomation {
    private const val LOCK_WAIT_MS = 20_000L

    private val mutex = Mutex()

    /** Runs [block] holding the UI mutex; fails clearly if another sequence holds it longer than [waitMs]. */
    suspend fun <T> exclusive(waitMs: Long = LOCK_WAIT_MS, block: suspend () -> T): T {
        val acquired = withTimeoutOrNull(waitMs) { mutex.lock() } != null
        if (!acquired) throw ToolException("Another screen action is still running on the phone. Wait a moment and retry.")
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    /** The connected accessibility service, or a ToolException telling the agent how to enable it. */
    fun service(): BridgeAccessibilityService = Accessibility.require()

    /** Reason for action tools (bridge_capabilities): accessibility off, or phone locked. */
    fun actionUnavailableReason(context: Context): String? =
        Accessibility.unavailableReason() ?: if (DeviceState.isLocked(context)) LOCKED_REASON else null

    /**
     * Interacting with a locked phone is refused: it would only reach the keyguard (and could be
     * used to guess the PIN).
     */
    fun requireUnlocked(context: Context) = DeviceState.requireUnlocked(context, "Controlling the screen")

    /** Lets the UI react to an action before reporting back. */
    suspend fun settle() = BridgeAccessibilityService.awaitIdle()

    /** Package of the primary window now (falls back to the tracked foreground app). */
    suspend fun foregroundPackage(service: BridgeAccessibilityService): String? = withContext(Dispatchers.IO) {
        service.rootNodes().firstOrNull()?.packageName ?: BridgeAccessibilityService.foregroundPackage
    }

    /** Settles (unless [settleFirst] is false), then appends the current foreground app to [message]. */
    suspend fun report(service: BridgeAccessibilityService, message: String, settleFirst: Boolean = true): String {
        if (settleFirst) settle()
        return "$message Foreground app: ${foregroundPackage(service) ?: "unknown"}."
    }

    const val LOCKED_REASON = "The phone is locked. Ask the user to unlock it; screen control does not work on the lock screen."
}
