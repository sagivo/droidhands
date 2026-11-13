package dev.phonebridge.core.tools

import dev.phonebridge.core.auth.AppAccessMode

/**
 * Effective app access for one call: the token's AppPolicy combined with the global never-allow list.
 * Package names are compared exactly.
 *
 * STUB bodies — implemented by the core agent (wave 2).
 */
class AppAccess(
    val mode: AppAccessMode,
    /** Allowlisted (ALLOWLIST) or blocked (BLOCKLIST) packages from the token. */
    val packages: Set<String>,
    /** Global never-allow list (always blocked, whatever [mode] says). Includes the bridge app itself. */
    val neverAllow: Set<String>,
) {
    /** True if the agent may see/operate [packageName]. */
    fun isAllowed(packageName: String): Boolean = TODO()

    /** True if any restriction applies (mode != ALL or a non-empty never-allow list). */
    val isRestricted: Boolean get() = TODO()

    /**
     * Throws [ToolException] if [packageName] is not allowed. The message names the app ([label] or the
     * package), says it was blocked by the user for this agent, and tells the agent not to work around it.
     */
    fun require(packageName: String, label: String? = null): Unit = TODO()

    companion object {
        val UNRESTRICTED = AppAccess(AppAccessMode.ALL, emptySet(), emptySet())
    }
}
