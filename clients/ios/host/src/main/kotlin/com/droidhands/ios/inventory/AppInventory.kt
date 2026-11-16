package com.droidhands.ios.inventory

import kotlinx.serialization.json.JsonObject
import java.io.File

/** One app installed on the iPhone, as Apple's tools report it. Everything DroidHands knows about an app is read here. */
data class IosApp(
    val bundleId: String,
    /** The name under the icon (CFBundleDisplayName, else CFBundleName, else the bundle id). */
    val name: String,
    /** CFBundleShortVersionString, e.g. "8.9.0". */
    val version: String? = null,
    /** CFBundleVersion, e.g. "8.9.42.575". */
    val build: String? = null,
    /** Shipped with iOS (Safari, Settings, …). */
    val system: Boolean = false,
    /** The `.app` folder when this Mac can read it (simulators), else null. */
    val bundlePath: String? = null,
    /** The app's Info.plist as JSON; empty when the inventory can't read it ([InventoryLimits.infoPlist]). */
    val infoPlist: JsonObject = EMPTY,
    /** The app's code-signing entitlements; empty when unreadable or none. */
    val entitlements: JsonObject = EMPTY,
) {
    /** The bundle folder, when readable. */
    val bundleDir: File? get() = bundlePath?.let(::File)?.takeIf { it.isDirectory }

    private companion object {
        val EMPTY = JsonObject(emptyMap())
    }
}

/**
 * What an inventory can't read, said once to the agent (in app_actions_list's `declares.unreadable`) instead of
 * silently showing fewer actions. Each field is null when that part is readable, else the reason.
 */
data class InventoryLimits(
    /** Info.plist (URL schemes, quick actions, document types). */
    val infoPlist: String? = null,
    /** Entitlements (associated domains for `https` links). */
    val entitlements: String? = null,
    /** Files inside the app bundle (App Intents metadata). */
    val bundleFiles: String? = null,
)

/**
 * The apps installed on one iPhone or simulator (docs/IOS.md). Implementations shell out to Apple's tools; [apps] is
 * blocking (call it off request threads) and throws [InventoryException] with an agent-facing reason when the list
 * can't be read at all. Thread-safe.
 */
interface AppInventory {
    /** Where the list comes from, e.g. `simctl`, for status and logs. */
    val source: String

    val limits: InventoryLimits

    /** Every app the user can open (hidden system services left out). */
    fun apps(): List<IosApp>

    /** The app's `InfoPlist.strings` (English, else Base), which localizes Info.plist titles; empty when unreadable. */
    fun infoPlistStrings(app: IosApp): Map<String, String> = emptyMap()
}

/** The app list couldn't be read; [message] is shown to the agent. */
class InventoryException(message: String) : Exception(message)

/**
 * [delegate]'s list, reused for [ttlMs] so one agent request (apps_list, app_actions_list, url_open checks) costs one
 * scan. A failed refresh keeps serving the previous list (and tries again after [ttlMs]); with no previous list it
 * throws. [cached] never blocks.
 */
class CachedInventory(
    private val delegate: AppInventory,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : AppInventory {
    private val lock = Any()

    @Volatile
    private var last: List<IosApp>? = null
    private var loadedAt: Long? = null

    override val source: String get() = delegate.source
    override val limits: InventoryLimits get() = delegate.limits

    override fun infoPlistStrings(app: IosApp): Map<String, String> = delegate.infoPlistStrings(app)

    override fun apps(): List<IosApp> = synchronized(lock) {
        val now = clock()
        val at = loadedAt
        if (at != null) last?.let { if (now - at < ttlMs) return it }
        return try {
            delegate.apps().also {
                last = it
                loadedAt = now
            }
        } catch (e: InventoryException) {
            val stale = last ?: throw e
            loadedAt = now
            stale
        }
    }

    /** The last list read, without reading again (null before the first read). */
    val cached: List<IosApp>? get() = last

    /** Forgets the list, so the next [apps] reads again (after an app was installed, say). */
    fun invalidate() = synchronized(lock) { loadedAt = null }

    companion object {
        const val DEFAULT_TTL_MS = 15_000L
    }
}
