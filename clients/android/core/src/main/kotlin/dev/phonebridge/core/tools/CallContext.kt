package dev.phonebridge.core.tools

/** Per-call information handed to [Tool.call]. */
class CallContext(
    val tokenId: String,
    val tokenName: String,
    val scopes: Set<String>,
    /** "relay" or "local". */
    val transport: String,
    /** Unique id for this call (logs/audit). */
    val requestId: String,
    /** Which apps this call may see/operate. Tools touching an app MUST check it (docs/SECURITY.md). */
    val appAccess: AppAccess = AppAccess.UNRESTRICTED,
    /** When true, tools returning message/notification text MUST pass it through [dev.phonebridge.core.util.OneTimeCodes.redact]. */
    val redactOneTimeCodes: Boolean = false,
    // Last so callers can pass it as a trailing lambda.
    private val confirmer: suspend (summary: String) -> Unit,
) {
    /**
     * For tools with [Tool.confirmsInternally] = true. Call after resolving targets and BEFORE any
     * side effect. Returns normally if no confirmation is needed under the token's policy or the
     * user approved; throws [ToolException] if the user denied or did not answer in time.
     */
    suspend fun requireConfirmation(summary: String) = confirmer(summary)
}
