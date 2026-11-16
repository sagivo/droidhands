package dev.phonebridge.core.policy

/** Device-wide security settings, read on every call (docs/SECURITY.md "Advanced controls"). */
data class GlobalSecurity(
    /** Packages no agent may touch. The platform adds its own package. */
    val neverAllowApps: Set<String> = emptySet(),
    /** Mask one-time codes in message/notification text returned to agents. */
    val redactOneTimeCodes: Boolean = true,
)
