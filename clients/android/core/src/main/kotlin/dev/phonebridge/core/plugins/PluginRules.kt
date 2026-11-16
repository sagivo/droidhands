package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.Scopes

/** The limits and allowed values of the plugin format (docs/PLUGINS.md §3-§6), shared by parser, validator and catalog. */
object PluginRules {
    /** Plugin ids (§3); also the folder name. */
    val PLUGIN_ID = Regex("^[a-z][a-z0-9_]{1,39}$")

    /** Action ids (§4). */
    val ACTION_ID = Regex("^[a-z][a-z0-9_]{0,39}$")

    /** Parameter names (§5). */
    val PARAM_NAME = Regex("^[a-z][a-z0-9_]{0,39}$")

    /** Java package names (Android application ids). */
    val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /** iOS bundle identifiers: reverse-DNS, letters, digits and `-` between dots (e.g. `com.spotify.client`). */
    val BUNDLE_ID = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+$")

    /** GitHub handles (an optional leading `@` is allowed). */
    val GITHUB_HANDLE = Regex("^@?[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$")

    /** Tool argument and intent extra keys (literal). */
    val ARG_NAME = Regex("^[a-z][a-z0-9_]{0,63}$")

    val CATEGORIES: List<String> = listOf(
        "messaging", "email", "social", "music", "video", "maps", "travel", "rides", "food", "shopping",
        "productivity", "browser", "news", "photos", "health", "system", "other",
    )

    /** Scopes an action may declare (§4). Screen control, notification actions and raw intents are not offered. */
    val ACTION_SCOPES: List<String> = listOf(
        Scopes.APPS, Scopes.DEVICE, Scopes.CONTACTS_READ, Scopes.MESSAGES_READ, Scopes.MESSAGES_SEND, Scopes.CALLS,
        Scopes.NOTIFICATIONS_READ, Scopes.CALENDAR_READ, Scopes.CALENDAR_WRITE, Scopes.LOCATION, Scopes.SCREEN_READ,
    )

    /** Packages that are PhoneBridge itself (any build variant); plugins may not target them (§9). */
    const val BRIDGE_PACKAGE_PREFIX = "dev.phonebridge"

    /**
     * The emulator-only E2E fixture apps (`clients/android/fixtures`, e.g. `dev.phonebridge.fixture.mail`):
     * not PhoneBridge, so test plugins may target them.
     */
    const val FIXTURE_PACKAGE_PREFIX = "dev.phonebridge.fixture."

    /** True if [packageName] (an Android package or iOS bundle id) is PhoneBridge (or one of its variants); the E2E fixture apps are not. */
    fun isBridgePackage(packageName: String): Boolean =
        (packageName == BRIDGE_PACKAGE_PREFIX || packageName.startsWith("$BRIDGE_PACKAGE_PREFIX.")) &&
            !packageName.startsWith(FIXTURE_PACKAGE_PREFIX)

    /** Intent actions that place calls; intent recipes may never use them (§6.2). */
    val CALL_ACTIONS: Set<String> = setOf("CALL", "CALL_PRIVILEGED", "CALL_EMERGENCY")

    /** True if [action] places a call, whatever its prefix (`android.intent.action.CALL`, `CALL`, …). */
    fun isCallAction(action: String): Boolean = action.trim().substringAfterLast('.').uppercase() in CALL_ACTIONS

    const val MAX_NAME = 60
    const val MAX_PLUGIN_DESCRIPTION = 300
    const val MIN_ACTIONS = 1
    const val MAX_ACTIONS = 30
    const val MAX_TITLE = 60
    const val MAX_ACTION_DESCRIPTION = 600
    const val MAX_PARAMS = 10
    const val MAX_KEYWORDS = 20
    const val MAX_KEYWORD = 40
    const val MAX_CONFIRM = 300
    const val MAX_PARAM_DESCRIPTION = 300
    const val MAX_PATTERN = 300
    const val MAX_EXAMPLES = 20
    const val MAX_STEPS = 40
    const val MAX_FAIL_MESSAGE = 300

    /** Whole action budget for `steps` recipes (§6.4). */
    const val STEPS_TIMEOUT_MS = 90_000L

    /** Budget for `intent` and `url` recipes. */
    const val LAUNCH_TIMEOUT_MS = 30_000L
}
