package dev.phonebridge.android.access

/**
 * Keeps agents from approving their own actions or minting tokens through UI automation (pure).
 *
 * * PhoneBridge's own windows (approval screen, tokens, settings) are never shown to or operated
 *   by agents.
 * * In System UI (notification shade, heads-up), elements labelled like an action button of one of
 *   PhoneBridge's own notifications (e.g. "Allow" on an approval prompt) cannot be tapped.
 */
object SelfProtection {
    const val OWN_SCREEN =
        "PhoneBridge does not let agents read or operate its own screens (approvals, tokens, settings). " +
            "Ask the user to do this on the phone."

    const val APPROVAL_BUTTON =
        "Refusing to tap this element: it looks like a button of a PhoneBridge notification (for example an approval " +
            "prompt). Agents cannot approve their own actions; the user must decide on the phone."

    /** Whether [pkg] is PhoneBridge itself. */
    fun isOwnPackage(pkg: String?, ownPackage: String): Boolean = pkg != null && pkg == ownPackage

    /**
     * True if [node] lives in System UI and it, or one of its close ancestors, shows one of
     * [protectedLabels] (titles of PhoneBridge's own notification actions).
     */
    fun isProtectedSystemUiNode(node: UiNode, protectedLabels: Collection<String>): Boolean {
        if (protectedLabels.isEmpty() || node.packageName != ForegroundTracker.SYSTEM_UI) return false
        val keys = protectedLabels.map(TextNorm::key).filter { it.isNotEmpty() }.toSet()
        if (keys.isEmpty()) return false
        return (sequenceOf(node) + node.ancestors().take(3)).any { n ->
            TextNorm.key(n.text) in keys || TextNorm.key(n.description) in keys
        }
    }
}
