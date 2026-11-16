package com.droidhands.android.access

import com.droidhands.core.auth.AppAccessMode

/**
 * Which apps one agent may see and operate through the screen tools (docs/SECURITY.md "App access"). Pure.
 *
 * When not [restricted], everything is visible. Otherwise a package is visible if [isAllowed] says so, or if it is
 * a *system surface* ([exempt]: System UI, the home screen, keyboards, framework and permission dialogs, DroidHands
 * itself, which has its own [SelfProtection]) that the user did not block explicitly ([explicitlyBlocked]). System
 * surfaces are not apps the user can pick, so an allowlist must not lock the agent out of the home screen or out of
 * a permission dialog shown by an allowed app.
 *
 * Nodes are filtered by their own package as well as by their window's: notification content in the shade lives in
 * System UI's window but reports the posting app's package.
 */
class AppVisibility(
    val restricted: Boolean,
    private val isAllowed: (String) -> Boolean,
    private val exempt: Set<String> = emptySet(),
    private val explicitlyBlocked: Set<String> = emptySet(),
) {
    /** True if the agent may see/operate [pkg]. Unknown (null/empty) packages are allowed. */
    fun allows(pkg: String?): Boolean {
        if (!restricted || pkg.isNullOrEmpty()) return true
        if (pkg in exempt && pkg !in explicitlyBlocked) return true
        return isAllowed(pkg)
    }

    /** Result of [filter]: the visible windows and how many were left out entirely. */
    data class Filtered(val windows: List<WindowSnapshot>, val omitted: Int)

    /**
     * Drops windows of blocked apps and prunes nodes of blocked apps from the rest. Hidden windows
     * (DroidHands's own) are kept as they are; [SelfProtection] already withholds their content.
     */
    fun filter(windows: List<WindowSnapshot>): Filtered {
        if (!restricted) return Filtered(windows, 0)
        val kept = ArrayList<WindowSnapshot>(windows.size)
        for (w in windows) {
            val visible = when {
                w.hidden || w.root == null -> w.takeIf { w.hidden || allows(w.packageName) }
                !allows(w.packageName) -> null
                else -> pruneNode(w.root)?.let { pruned -> if (pruned === w.root) w else w.copy(root = pruned) }
            }
            if (visible != null) kept += visible
        }
        return Filtered(kept, windows.size - kept.size)
    }

    /**
     * [root] without the subtrees whose node belongs to a blocked app; null if [root] itself is blocked.
     * Returns [root] unchanged (same instance) when nothing is blocked.
     */
    fun prune(root: UiNode): UiNode? {
        if (!restricted) return root
        return pruneNode(root)
    }

    private fun pruneNode(node: UiNode): UiNode? {
        if (!allows(node.packageName)) return null
        if (node.children.isEmpty()) return node
        var changed = false
        val kept = ArrayList<UiNode>(node.children.size)
        for (child in node.children) {
            val pruned = pruneNode(child)
            if (pruned !== child) changed = true
            if (pruned != null) kept += pruned
        }
        return if (changed) node.withChildren(kept) else node
    }

    /** The first blocked package owning one of [windows] or any node in them (screenshots), or null. */
    fun firstBlocked(windows: List<WindowSnapshot>): String? {
        if (!restricted) return null
        for (w in windows) {
            if (w.hidden) continue
            if (!allows(w.packageName)) return w.packageName
            val root = w.root ?: continue
            root.walk().firstOrNull { !allows(it.packageName) }?.let { return it.packageName }
        }
        return null
    }

    /**
     * The blocked app the user is in, or null: the primary window's app, or the tracked foreground app
     * ([trackedPackage], which ignores System UI and keyboards) while one of its windows is still on screen
     * ([windowPackages]), e.g. under the notification shade or a dialog of another app.
     */
    fun blockedForeground(primaryPackage: String?, trackedPackage: String?, windowPackages: Collection<String?>): String? {
        if (!restricted) return null
        if (primaryPackage != null && !allows(primaryPackage)) return primaryPackage
        if (trackedPackage != null && !allows(trackedPackage) && trackedPackage in windowPackages) return trackedPackage
        return null
    }

    companion object {
        /** No app access restrictions (and no refusals). */
        val UNRESTRICTED = AppVisibility(restricted = false, isAllowed = { true })

        /**
         * Whether a token's app access hides anything besides DroidHands itself ([ownPackage] is always on the
         * never-allow list and is protected separately, so on its own it doesn't count as a restriction).
         */
        fun isRestricted(mode: AppAccessMode, packages: Set<String>, neverAllow: Set<String>, ownPackage: String): Boolean {
            val othersNeverAllowed = neverAllow.any { it != ownPackage }
            return when (mode) {
                AppAccessMode.ALL -> othersNeverAllowed
                AppAccessMode.ALLOWLIST -> true
                AppAccessMode.BLOCKLIST -> othersNeverAllowed || packages.any { it != ownPackage }
            }
        }

        /** Packages the user named as blocked (never-allow list, plus the token's blocklist); never [ownPackage]. */
        fun explicitlyBlocked(mode: AppAccessMode, packages: Set<String>, neverAllow: Set<String>, ownPackage: String): Set<String> =
            buildSet {
                addAll(neverAllow)
                if (mode == AppAccessMode.BLOCKLIST) addAll(packages)
                remove(ownPackage)
            }
    }
}
