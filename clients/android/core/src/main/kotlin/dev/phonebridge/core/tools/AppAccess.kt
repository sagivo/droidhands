package dev.phonebridge.core.tools

import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.auth.AppPolicy
import dev.phonebridge.core.auth.GrantText

/**
 * Effective app access for one call: the token's AppPolicy combined with the global never-allow list
 * (docs/SECURITY.md "Advanced controls"). Package names are compared exactly. Immutable and thread-safe.
 *
 * Rules, in order:
 * 1. A package in [neverAllow] is always blocked, whatever [mode] says.
 * 2. [AppAccessMode.ALL]: every other package is allowed ([packages] is ignored).
 * 3. [AppAccessMode.ALLOWLIST]: only [packages] are allowed (an empty allowlist allows nothing).
 * 4. [AppAccessMode.BLOCKLIST]: every package except [packages] is allowed.
 */
class AppAccess(
    val mode: AppAccessMode,
    /** Allowlisted (ALLOWLIST) or blocked (BLOCKLIST) packages from the token. */
    packages: Set<String>,
    /** Global never-allow list (always blocked, whatever [mode] says). Includes the bridge app itself. */
    neverAllow: Set<String>,
    /** The agent's name, for refusals ("Claude isn't allowed to use WhatsApp…"); blank = "this agent". */
    val agentName: String = "",
) {
    /** Allowlisted (ALLOWLIST) or blocked (BLOCKLIST) packages from the token (a defensive copy). */
    val packages: Set<String> = packages.toSet()

    /** Global never-allow list (a defensive copy). */
    val neverAllow: Set<String> = neverAllow.toSet()

    /** True if the agent may see/operate [packageName]. */
    fun isAllowed(packageName: String): Boolean {
        if (packageName in neverAllow) return false
        return when (mode) {
            AppAccessMode.ALL -> true
            AppAccessMode.ALLOWLIST -> packageName in packages
            AppAccessMode.BLOCKLIST -> packageName !in packages
        }
    }

    /** True if any restriction applies (mode != ALL or a non-empty never-allow list). */
    val isRestricted: Boolean get() = mode != AppAccessMode.ALL || neverAllow.isNotEmpty()

    /**
     * Throws [AppBlockedException] (a [ToolException]) if [packageName] is not allowed. The message names
     * the app ([label] or the package), says it is outside this agent's access (and where the user can
     * allow it) or on the never-allow list, and tells the agent not to work around it.
     */
    fun require(packageName: String, label: String? = null) {
        if (isAllowed(packageName)) return
        throw AppBlockedException(packageName, blockedMessage(packageName, label, globally = packageName in neverAllow, agentName = agentName))
    }

    override fun toString(): String = "AppAccess(mode=$mode, packages=${packages.size}, neverAllow=${neverAllow.size})"

    companion object {
        val UNRESTRICTED = AppAccess(AppAccessMode.ALL, emptySet(), emptySet())

        /** Combines a token's [policy] with the global [neverAllow] list; [agentName] names the agent in refusals. */
        fun of(policy: AppPolicy, neverAllow: Set<String>, agentName: String = ""): AppAccess =
            AppAccess(policy.mode, policy.packages, neverAllow, agentName)

        /** Agent-facing refusal text (pure; exposed for tests and tools that filter silently). See [GrantText.appNotAllowed]. */
        fun blockedMessage(packageName: String, label: String?, globally: Boolean, agentName: String = ""): String =
            GrantText.appNotAllowed(agentName, packageName, label, globally)
    }
}

/**
 * Thrown by [AppAccess.require]: the call touched an app the user blocked for this agent. The server
 * audits it as `DENIED_APP` instead of a tool error. [packageName] is for the local audit log.
 */
class AppBlockedException(val packageName: String, message: String) : ToolException(message)
