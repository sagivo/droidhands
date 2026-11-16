package com.droidhands.android.access

/**
 * Parses `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`: a colon-separated list of flattened
 * component names, each either `pkg/.Cls` (short) or `pkg/pkg.Cls` (full). Pure.
 */
object EnabledServices {
    /** (package, fully qualified class) pairs; malformed entries are skipped. */
    fun parse(setting: String?): List<Pair<String, String>> {
        if (setting.isNullOrBlank()) return emptyList()
        return setting.split(':').mapNotNull { raw ->
            val entry = raw.trim()
            val slash = entry.indexOf('/')
            if (slash <= 0 || slash == entry.length - 1) return@mapNotNull null
            val pkg = entry.substring(0, slash).trim()
            val cls = entry.substring(slash + 1).trim()
            if (pkg.isEmpty() || cls.isEmpty()) return@mapNotNull null
            pkg to if (cls.startsWith('.')) pkg + cls else cls
        }
    }

    /** True if the component [packageName]/[className] is in [setting] (case-insensitive). */
    fun contains(setting: String?, packageName: String, className: String): Boolean =
        parse(setting).any { (pkg, cls) -> pkg.equals(packageName, ignoreCase = true) && cls.equals(className, ignoreCase = true) }
}
