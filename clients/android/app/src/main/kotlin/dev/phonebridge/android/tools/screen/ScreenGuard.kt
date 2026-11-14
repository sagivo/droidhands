package dev.phonebridge.android.tools.screen

import android.app.NotificationManager
import android.content.Context
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.ForegroundTracker
import dev.phonebridge.android.access.NodeCapture
import dev.phonebridge.android.access.NodeQueries
import dev.phonebridge.android.access.SelfProtection
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.access.info
import dev.phonebridge.core.tools.ToolException

/**
 * Applies [SelfProtection] and the agent's app access ([ScreenAccess]) to real targets. All checks do
 * blocking IPC: call on Dispatchers.IO.
 */
object ScreenGuard {
    /**
     * Throws if [node] belongs to PhoneBridge, to an app blocked for this agent (e.g. a blocked app's
     * notification in the shade), or is an approval-like button of ours in System UI.
     */
    fun checkNode(context: Context, node: UiNode, access: ScreenAccess) {
        if (SelfProtection.isOwnPackage(node.packageName, context.packageName)) throw ToolException(SelfProtection.OWN_SCREEN)
        node.packageName?.let { if (!access.allows(it)) access.refuse(it, "Refusing to operate this element.") }
        if (node.packageName == ForegroundTracker.SYSTEM_UI &&
            SelfProtection.isProtectedSystemUiNode(node, ownNotificationActionTitles(context))
        ) {
            throw ToolException(SelfProtection.APPROVAL_BUTTON)
        }
    }

    /**
     * [checkNode] for the element a click or long-click goes to; also throws if it is a System UI element containing
     * PhoneBridge content (a notification row of ours: clicking it would open our approval or pairing screen). The
     * subtree is re-read from the live UI, since snapshots shown to agents leave our content out.
     */
    fun checkClick(context: Context, node: UiNode, access: ScreenAccess) {
        checkNode(context, node, access)
        if (node.packageName != ForegroundTracker.SYSTEM_UI) return
        val subtree = node.info?.let { info ->
            val budget = NodeCapture.Budget()
            NodeCapture.capture(info, budget).takeUnless { budget.exhausted }
                ?: throw ToolException(SelfProtection.OWN_NOTIFICATION) // Too large to check: fail closed.
        } ?: node
        if (SelfProtection.containsOwnContent(subtree, context.packageName)) throw ToolException(SelfProtection.OWN_NOTIFICATION)
    }

    /**
     * Throws if a touch at ([x], [y]) would land on a PhoneBridge window or notification, on a window whose content
     * Android hides from PhoneBridge (it may be our approval screen), or on a window or element of an app blocked for
     * this agent.
     */
    fun checkPoint(context: Context, service: BridgeAccessibilityService, x: Int, y: Int, access: ScreenAccess) {
        val target = SelfProtection.touchedWindow(service.touchWindows(), x, y, { it.kind }, { it.bounds }, { it.root != null }) ?: return
        val root = target.root ?: return // Unreachable: touchedWindow refuses unreadable windows.
        val pkg = root.packageName?.toString()
        if (SelfProtection.isOwnPackage(pkg, context.packageName)) throw ToolException(SelfProtection.OWN_SCREEN)
        pkg?.let { if (!access.allows(it)) access.refuse(it, "Refusing to touch ($x,$y): it is on a window of a blocked app.") }
        if (pkg == ForegroundTracker.SYSTEM_UI) {
            val hit = NodeQueries.deepestAt(NodeCapture.capture(root), x, y) ?: return
            // Notification content in the shade reports the posting app's package.
            (sequenceOf(hit) + hit.ancestors()).firstNotNullOfOrNull { n -> n.packageName?.takeIf { !access.allows(it) } }
                ?.let { access.refuse(it, "Refusing to touch ($x,$y): it is on a notification of a blocked app.") }
            if (SelfProtection.isOwnPackage(hit.packageName, context.packageName)) throw ToolException(SelfProtection.APPROVAL_BUTTON)
            if (SelfProtection.touchReachesOwnContent(hit, context.packageName)) throw ToolException(SelfProtection.OWN_NOTIFICATION)
            if (SelfProtection.isProtectedSystemUiNode(hit, ownNotificationActionTitles(context))) throw ToolException(SelfProtection.APPROVAL_BUTTON)
        }
    }

    /**
     * Throws [SelfProtection.CoveredTouchException] unless a touch at ([x], [y]) lands on an application window of
     * [confineTo] (a screen recipe's app). Call after [checkPoint].
     */
    fun checkConfined(service: BridgeAccessibilityService, x: Int, y: Int, confineTo: String) {
        val target = SelfProtection.touchedWindow(service.touchWindows(), x, y, { it.kind }, { it.bounds }, { it.root != null })
        val pkg = target?.root?.packageName?.toString()
        if (SelfProtection.coversConfinedApp(target?.kind, pkg, confineTo)) throw SelfProtection.CoveredTouchException(x, y)
    }

    /** Titles of the action buttons on PhoneBridge's currently posted notifications (no permission needed). */
    private fun ownNotificationActionTitles(context: Context): Set<String> = try {
        context.getSystemService(NotificationManager::class.java)?.activeNotifications.orEmpty()
            .flatMap { sbn -> sbn.notification.actions.orEmpty().mapNotNull { it.title?.toString() } }
            .filter { it.isNotBlank() }
            .toSet()
    } catch (e: RuntimeException) {
        emptySet()
    }
}
