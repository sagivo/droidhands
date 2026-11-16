package dev.phonebridge.android.tools.actions

/** What an app's manifest declares about its activities: the intent filters other apps can use. */
data class DeclaredManifest(val packageName: String, val activities: List<DeclaredActivity>)

/** An `<activity>` or `<activity-alias>`, with its class name made absolute. */
data class DeclaredActivity(
    val className: String,
    val label: String? = null,
    /** As declared; null when the attribute is absent (Android then exports activities that have filters). */
    val exported: Boolean? = null,
    val enabled: Boolean = true,
    /** A permission callers need; PhoneBridge can't start such activities. */
    val permission: String? = null,
    val filters: List<DeclaredFilter> = emptyList(),
) {
    /** Exported as Android decides it: the attribute, or "has a filter" when absent. */
    val effectivelyExported: Boolean get() = exported ?: filters.isNotEmpty()
}

/** An `<intent-filter>`. Paths keep their kind: `/x` (path), `/x*` (prefix), or the pattern as declared. */
data class DeclaredFilter(
    val actions: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val schemes: List<String> = emptyList(),
    /** `host` or `host:port`; a leading `*` is a wildcard subdomain. */
    val hosts: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    val mimeTypes: List<String> = emptyList(),
)

/**
 * Turns a decoded manifest ([BinaryXml]) into [DeclaredManifest] (pure). Attributes are matched by the framework's
 * public attribute ids (stable on every Android version), with their names as a fallback. Resource references (labels)
 * go through [resolve].
 */
object ManifestXml {
    /** At most this many activities and filters per activity are kept. */
    const val MAX_ACTIVITIES = 500
    const val MAX_FILTERS = 30

    fun parse(root: XmlElement, resolve: (Int) -> String? = { null }): DeclaredManifest {
        require(root.name == "manifest") { "not a manifest" }
        val pkg = root.attributes.firstOrNull { it.name == "package" }?.value?.trim().orEmpty()
        require(pkg.isNotEmpty()) { "manifest without a package" }
        fun text(e: XmlElement, id: Int, name: String): String? {
            val a = e.attr(id, name) ?: return null
            val v = a.value ?: a.reference?.let(resolve)
            return v?.trim()?.takeIf { it.isNotEmpty() }
        }
        fun bool(e: XmlElement, id: Int, name: String): Boolean? {
            val a = e.attr(id, name) ?: return null
            return a.bool ?: a.value?.trim()?.lowercase()?.let { if (it == "true") true else if (it == "false") false else null }
        }
        val application = root.children("application").firstOrNull() ?: return DeclaredManifest(pkg, emptyList())
        val appEnabled = bool(application, android.R.attr.enabled, "enabled") ?: true
        val activities = application.children.asSequence()
            .filter { it.name == "activity" || it.name == "activity-alias" }
            .mapNotNull { e ->
                val raw = text(e, android.R.attr.name, "name") ?: return@mapNotNull null
                val className = absolute(pkg, raw)
                val filters = e.children("intent-filter").take(MAX_FILTERS).map { f -> filter(f, ::text) }
                DeclaredActivity(
                    className = className,
                    label = text(e, android.R.attr.label, "label"),
                    exported = bool(e, android.R.attr.exported, "exported"),
                    enabled = appEnabled && (bool(e, android.R.attr.enabled, "enabled") ?: true),
                    permission = text(e, android.R.attr.permission, "permission"),
                    filters = filters,
                )
            }
            .take(MAX_ACTIVITIES)
            .toList()
        return DeclaredManifest(pkg, activities)
    }

    private fun filter(f: XmlElement, text: (XmlElement, Int, String) -> String?): DeclaredFilter {
        val actions = f.children("action").mapNotNull { text(it, android.R.attr.name, "name") }
        val categories = f.children("category").mapNotNull { text(it, android.R.attr.name, "name") }
        val schemes = ArrayList<String>()
        val hosts = ArrayList<String>()
        val paths = ArrayList<String>()
        val types = ArrayList<String>()
        for (d in f.children("data")) {
            text(d, android.R.attr.scheme, "scheme")?.let { schemes += it.lowercase() }
            text(d, android.R.attr.host, "host")?.let { host ->
                val port = text(d, android.R.attr.port, "port")
                hosts += if (port != null) "${host.lowercase()}:$port" else host.lowercase()
            }
            text(d, android.R.attr.path, "path")?.let { paths += it }
            text(d, android.R.attr.pathPrefix, "pathPrefix")?.let { paths += "$it*" }
            text(d, android.R.attr.pathPattern, "pathPattern")?.let { paths += it }
            text(d, android.R.attr.pathSuffix, "pathSuffix")?.let { paths += "*$it" }
            text(d, android.R.attr.pathAdvancedPattern, "pathAdvancedPattern")?.let { paths += it }
            text(d, android.R.attr.mimeType, "mimeType")?.let { types += it }
        }
        return DeclaredFilter(actions.distinct(), categories.distinct(), schemes.distinct(), hosts.distinct(), paths.distinct(), types.distinct())
    }

    /** `.Main` or `Main` → `<package>.Main`; full names are kept. */
    fun absolute(pkg: String, name: String): String = when {
        name.startsWith(".") -> pkg + name
        !name.contains('.') -> "$pkg.$name"
        else -> name
    }
}
