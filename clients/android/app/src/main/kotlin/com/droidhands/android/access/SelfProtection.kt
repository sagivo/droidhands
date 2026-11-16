package com.droidhands.android.access

import com.droidhands.android.tools.support.ApprovalGuard
import com.droidhands.core.tools.ToolException

/**
 * Keeps agents from approving their own actions or minting tokens through UI automation (pure).
 *
 * * While a DroidHands approval is pending or shown ([ApprovalGuard]), no gesture or node action runs at all,
 *   since Android may hide our approval screens from our own accessibility service.
 * * DroidHands's own windows (approval screen, tokens, settings) are never shown to or operated
 *   by agents, and neither is its notification content in System UI (shade, heads-up, lock screen).
 * * A touch whose window Android withholds from us is refused: it may be one of our approval screens.
 * * In System UI, elements labelled like an action button of one of DroidHands's own notifications
 *   (e.g. "Allow" on an approval prompt), or containing DroidHands content (a notification row), cannot be tapped.
 */
object SelfProtection {
    const val OWN_SCREEN =
        "DroidHands does not let agents read or operate its own screens (approvals, tokens, settings). " +
            "Ask the user to do this on the phone."

    const val APPROVAL_BUTTON =
        "Refusing to tap this element: it looks like a button of a DroidHands notification (for example an approval " +
            "prompt). Agents cannot approve their own actions; the user must decide on the phone."

    const val OWN_NOTIFICATION =
        "Refusing to operate this element: it is (part of) a DroidHands notification, for example an approval or pairing " +
            "prompt. Agents cannot open or answer DroidHands's own notifications; the user must decide on the phone."

    const val UNREADABLE_WINDOW =
        "Android hides the content of the window there from DroidHands (as it does for DroidHands's own approval " +
            "screens and some apps' sensitive screens), so it can't check what the touch would hit. Ask the user to do this on the phone."

    /** Whether [pkg] is DroidHands itself. */
    fun isOwnPackage(pkg: String?, ownPackage: String): Boolean = pkg != null && pkg == ownPackage

    /** Throws [ApprovalGuard.REFUSAL] while a DroidHands approval is pending or shown. Call right before acting. */
    fun requireNoPendingApproval(active: Boolean = ApprovalGuard.isActive) {
        if (active) throw ToolException(ApprovalGuard.REFUSAL)
    }

    /**
     * True if [node] lives in System UI and it, or one of its close ancestors, shows one of
     * [protectedLabels] (titles of DroidHands's own notification actions).
     */
    fun isProtectedSystemUiNode(node: UiNode, protectedLabels: Collection<String>): Boolean {
        if (protectedLabels.isEmpty() || node.packageName != ForegroundTracker.SYSTEM_UI) return false
        val keys = protectedLabels.map(TextNorm::key).filter { it.isNotEmpty() }.toSet()
        if (keys.isEmpty()) return false
        return (sequenceOf(node) + node.ancestors().take(3)).any { n ->
            TextNorm.key(n.text) in keys || TextNorm.key(n.description) in keys
        }
    }

    /** True if [node] or a descendant belongs to DroidHands (e.g. a System UI notification row showing our notification). */
    fun containsOwnContent(node: UiNode, ownPackage: String): Boolean = node.walk().any { isOwnPackage(it.packageName, ownPackage) }

    /**
     * True if a touch on [hit] (the deepest node at the point, in an unfiltered capture) reaches DroidHands content: [hit]
     * contains some, or the nearest clickable or long-clickable element that would handle the touch does (tapping a
     * notification row's padding opens that notification).
     */
    fun touchReachesOwnContent(hit: UiNode, ownPackage: String): Boolean {
        val chain = sequenceOf(hit) + hit.ancestors()
        val handlers = listOfNotNull(hit, chain.firstOrNull { it.clickable }, chain.firstOrNull { it.longClickable })
        return handlers.any { containsOwnContent(it, ownPackage) }
    }

    /**
     * The window a touch at ([x], [y]) reaches among [windows] (top-most first): the top-most non-overlay window whose
     * bounds contain the point, or null if none does. Fails closed: throws ([UNREADABLE_WINDOW]) when that window's
     * content is withheld from DroidHands ([readable] false), because Android hides DroidHands's own approval and
     * pairing screens from its accessibility service on Android 14+.
     */
    fun <W> touchedWindow(
        windows: List<W>,
        x: Int,
        y: Int,
        kind: (W) -> WindowKind,
        bounds: (W) -> Bounds,
        readable: (W) -> Boolean,
    ): W? {
        val target = windows.firstOrNull { kind(it) != WindowKind.OVERLAY && bounds(it).contains(x, y) } ?: return null
        if (!readable(target)) throw ToolException("Refusing to touch ($x,$y): $UNREADABLE_WINDOW")
        return target
    }

    /**
     * True if a touch confined to [confineTo] (a screen recipe's app) must not land on the touched window: one of
     * [kind] whose root belongs to [pkg], or none at all (null [kind]). Only that app's own application windows
     * qualify; a keyboard, the notification shade or another app's window covering the element does not.
     */
    fun coversConfinedApp(kind: WindowKind?, pkg: String?, confineTo: String): Boolean =
        kind != WindowKind.APPLICATION || pkg != confineTo

    /** Thrown by [coversConfinedApp] refusals; not a step failure, so an optional recipe step doesn't ignore it. */
    class CoveredTouchException(x: Int, y: Int) : ToolException(
        "Stopped before touching ($x,$y): another window (keyboard, notification or another app) covers the element. " +
            "Nothing was done there.",
    )

    /**
     * [root] without DroidHands's own content, whatever window it is in: every subtree whose node belongs to
     * [ownPackage], and the element hosting it (its nearest clickable or long-clickable ancestor within
     * [NodeQueries.MAX_CLICK_ANCESTORS] levels, e.g. the System UI notification row), so agents can neither read our
     * notifications nor get refs to open them. Null when nothing is left; [root] itself (same instance) when there is
     * nothing to hide.
     */
    fun hideOwnContent(root: UiNode, ownPackage: String, ownTexts: Set<String> = emptySet()): UiNode? {
        // The kept node (null if removed), and how many levels it is above own content whose host is still to be
        // removed (-1: none).
        fun visit(node: UiNode): Pair<UiNode?, Int> {
            if (isOwnPackage(node.packageName, ownPackage) || isOwnText(node, ownTexts)) return null to 0
            var pending = -1
            var changed = false
            val kept = ArrayList<UiNode>(node.children.size)
            for (child in node.children) {
                val (result, below) = visit(child)
                if (result !== child) changed = true
                if (result != null) kept += result
                if (below >= 0 && (pending < 0 || below + 1 < pending)) pending = below + 1
            }
            if (pending > NodeQueries.MAX_CLICK_ANCESTORS) pending = -1
            if (pending > 0 && (node.clickable || node.longClickable)) return null to -1
            return (if (changed) node.withChildren(kept) else node) to pending
        }
        return visit(root).first
    }

    /**
     * Heads-up notifications are rendered by System UI and report its package, so DroidHands's own prompts are
     * also recognised by text: a node whose text exactly equals the title or body of one of DroidHands's currently
     * posted notifications (e.g. "E2E auto wants to: Send SMS"), or whose content description contains one: the
     * shade's notification icons are described as "DroidHands notification: <title>", and a pairing title carries
     * its code. Its notification row is then removed with it.
     */
    internal fun isOwnText(node: UiNode, ownTexts: Set<String>): Boolean {
        if (ownTexts.isEmpty()) return false
        node.text?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it in ownTexts) return true }
        val desc = node.description?.takeIf { it.isNotBlank() } ?: return false
        return ownTexts.any { desc.contains(it) }
    }
}
