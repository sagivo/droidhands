package dev.phonebridge.android.tools.screen

import android.app.NotificationManager
import android.content.Context
import dev.phonebridge.android.access.BridgeAccessibilityService
import dev.phonebridge.android.access.ForegroundTracker
import dev.phonebridge.android.access.NodeCapture
import dev.phonebridge.android.access.NodeQueries
import dev.phonebridge.android.access.SelfProtection
import dev.phonebridge.android.access.UiNode
import dev.phonebridge.android.access.WindowKind
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
     * Throws if a touch at ([x], [y]) would land on a PhoneBridge window or one of its notification buttons, or
     * on a window or element of an app blocked for this agent.
     */
    fun checkPoint(context: Context, service: BridgeAccessibilityService, x: Int, y: Int, access: ScreenAccess) {
        val target = service.rootNodes()
            .sortedBy { it.zIndex }
            .firstOrNull { it.kind != WindowKind.OVERLAY && it.bounds.contains(x, y) } ?: return
        if (SelfProtection.isOwnPackage(target.packageName, context.packageName)) throw ToolException(SelfProtection.OWN_SCREEN)
        target.packageName?.let { if (!access.allows(it)) access.refuse(it, "Refusing to touch ($x,$y): it is on a window of a blocked app.") }
        if (target.packageName == ForegroundTracker.SYSTEM_UI) {
            val labels = ownNotificationActionTitles(context)
            if (labels.isEmpty() && !access.restricted) return
            val hit = NodeQueries.deepestAt(NodeCapture.capture(target.root), x, y) ?: return
            // Notification content in the shade reports the posting app's package.
            (sequenceOf(hit) + hit.ancestors()).firstNotNullOfOrNull { n -> n.packageName?.takeIf { !access.allows(it) } }
                ?.let { access.refuse(it, "Refusing to touch ($x,$y): it is on a notification of a blocked app.") }
            if (SelfProtection.isOwnPackage(hit.packageName, context.packageName)) throw ToolException(SelfProtection.APPROVAL_BUTTON)
            if (SelfProtection.isProtectedSystemUiNode(hit, labels)) throw ToolException(SelfProtection.APPROVAL_BUTTON)
        }
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
