package dev.phonebridge.android.tools.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.TypedValue
import dev.phonebridge.android.tools.system.AppEntry
import dev.phonebridge.android.tools.system.InstalledApps
import dev.phonebridge.android.tools.system.LaunchableApp
import dev.phonebridge.android.tools.system.queryIntentActivitiesCompat
import dev.phonebridge.core.plugins.PluginAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** An app's install version: both change with every update. */
data class VersionKey(val versionCode: Long, val lastUpdateTime: Long)

/** One scan of the launchable apps and the standard intents they handle (docs/PLUGINS.md §8). Immutable. */
internal class AppScan(
    /** Sorted by label; PhoneBridge itself included (callers leave it out). */
    val apps: List<LaunchableApp>,
    val handlers: Map<String, StandardHandlers>,
    /** [PackageManager.getChangedPackages] sequence number at scan time. */
    val sequence: Int,
    /** [SystemClock.elapsedRealtime] at scan time. */
    val takenAt: Long,
) {
    private val byPackage = apps.associateBy { it.entry.packageName }

    val entries: List<AppEntry> get() = apps.map { it.entry }

    fun app(packageName: String): LaunchableApp? = byPackage[packageName]
}

/**
 * Discovers what installed apps declare (docs/PLUGINS.md §8) and caches it:
 *
 * * one [AppScan] of the launchable apps and of the handlers of each standard intent (each queried once across
 *   all packages), rebuilt when Android reports installed, updated or removed packages
 *   ([PackageManager.getChangedPackages], checked on use, at most every [RECHECK_MS]) and at least every
 *   [MAX_SCAN_AGE_MS];
 * * per package, its `shortcuts.xml` (static shortcuts, App Actions capabilities) and the actions built from it,
 *   kept while the package's [VersionKey] and handlers are unchanged.
 *
 * Thread-safe. Blocking PackageManager and resource I/O runs on Dispatchers.IO.
 */
internal class AppScanner(context: Context) {
    private val context = context.applicationContext
    private val pm: PackageManager get() = context.packageManager
    private val mutex = Mutex()

    @Volatile private var current: AppScan? = null

    private class Discovered(val key: Any, val actions: List<PluginAction>)

    private val declared = ConcurrentHashMap<String, Pair<VersionKey, ShortcutsDeclaration?>>()
    private val manifests = ConcurrentHashMap<String, Pair<VersionKey, DeclaredManifest?>>()
    private val discovered = ConcurrentHashMap<String, Discovered>()

    /** The current scan, rebuilt first if packages changed or it is too old. */
    suspend fun scan(): AppScan = withContext(Dispatchers.IO) {
        current?.takeIf(::isFresh)?.let { return@withContext it }
        mutex.withLock {
            current?.takeIf(::isFresh) ?: build().also { current = it }
        }
    }

    /** [packageName] in the last scan, without scanning (null before the first scan or if it isn't launchable). */
    fun cachedApp(packageName: String): LaunchableApp? = current?.app(packageName)

    /** The discovered actions of [packageName] (empty if it is not a launchable app). */
    suspend fun actions(packageName: String): List<PluginAction> {
        val scan = scan()
        val app = scan.app(packageName) ?: return emptyList()
        val handlers = scan.handlers[packageName] ?: StandardHandlers()
        val version = VersionKey(app.versionCode, app.lastUpdateTime)
        val key = listOf(version, handlers, app.launcher.name, app.entry.label)
        discovered[packageName]?.takeIf { it.key == key }?.let { return it.actions }
        val actions = withContext(Dispatchers.IO) {
            val shortcuts = shortcuts(app, version)
            val manifest = manifest(app, version)
            DiscoveredActions.build(app.entry.label, packageName, app.launcher.name, handlers, shortcuts, manifest) { isExported(packageName, it) }
        }
        discovered[packageName] = Discovered(key, actions)
        return actions
    }

    /** Scans every app once in the background, so the first agent request finds the cache warm. */
    suspend fun warmUp(log: (String, Throwable) -> Unit) {
        for (app in scan().apps) {
            if (app.entry.packageName == context.packageName) continue
            try {
                actions(app.entry.packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Scanning ${app.entry.packageName} failed", e)
            }
        }
    }

    /** When packages were last found unchanged; a search over every app checks at most once per [RECHECK_MS]. */
    @Volatile private var checkedAt = 0L

    private fun isFresh(scan: AppScan): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - scan.takenAt > MAX_SCAN_AGE_MS) return false
        if (now - checkedAt < RECHECK_MS) return true
        if (changedSince(scan.sequence) != null) return false
        checkedAt = now
        return true
    }

    /** The new sequence number if packages changed since [sequence], else null. */
    private fun changedSince(sequence: Int): Int? = try {
        pm.getChangedPackages(sequence)?.sequenceNumber
    } catch (e: RuntimeException) {
        sequence + 1 // Can't tell: rescan.
    }

    private suspend fun build(): AppScan {
        val sequence = try {
            pm.getChangedPackages(0)?.sequenceNumber ?: 0
        } catch (e: RuntimeException) {
            0
        }
        val apps = InstalledApps.launchableWithActivities(context, PackageManager.GET_META_DATA)
        val share = handlers(Intent(Intent.ACTION_SEND).setType(DiscoveredActions.TEXT_PLAIN))
        val processText = handlers(Intent(Intent.ACTION_PROCESS_TEXT).setType(DiscoveredActions.TEXT_PLAIN), withLabels = true)
        val play = handlers(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH))
        val web = handlers(Intent(Intent.ACTION_WEB_SEARCH))
        val geo = handlers(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=x")))
        val search = handlers(Intent(Intent.ACTION_SEARCH))
        val handlers = apps.associate { app ->
            val pkg = app.entry.packageName
            pkg to StandardHandlers(share[pkg], processText[pkg], play[pkg], web[pkg], geo[pkg], search[pkg])
        }
        return AppScan(apps, handlers, sequence, SystemClock.elapsedRealtime())
    }

    /** The first exported handler of [intent] per package (Android's priority order). */
    private fun handlers(intent: Intent, withLabels: Boolean = false): Map<String, IntentHandler> = try {
        pm.queryIntentActivitiesCompat(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo?.exported == true }
            .groupBy { it.activityInfo.packageName }
            .mapValues { (_, infos) ->
                val first = infos.first()
                val label = if (withLabels) runCatching { first.loadLabel(pm)?.toString()?.trim() }.getOrNull() else null
                IntentHandler(first.activityInfo.name, label?.takeIf { it.isNotEmpty() })
            }
    } catch (e: RuntimeException) {
        emptyMap()
    }

    /** [app]'s parsed `shortcuts.xml` (cached by version), or null if it has none or it can't be read. */
    private fun shortcuts(app: LaunchableApp, version: VersionKey): ShortcutsDeclaration? {
        val pkg = app.entry.packageName
        declared[pkg]?.takeIf { it.first == version }?.let { return it.second }
        val parsed = readShortcuts(app)
        declared[pkg] = version to parsed
        return parsed
    }

    private fun readShortcuts(app: LaunchableApp): ShortcutsDeclaration? {
        val activity = app.launcher
        if (activity.metaData?.containsKey(ShortcutsXml.META_DATA) != true) return null
        return try {
            val resources = pm.getResourcesForApplication(activity.applicationInfo)
            activity.loadXmlMetaData(pm, ShortcutsXml.META_DATA)?.use { parser ->
                ShortcutsXml.parse(parser) { ref -> resolve(resources, ref) }
            }
        } catch (e: Exception) {
            null // Unreadable or malformed: the app just gets no shortcut actions.
        }
    }

    /** [app]'s manifest intent filters (cached by version), or null if its APK can't be read. */
    private fun manifest(app: LaunchableApp, version: VersionKey): DeclaredManifest? {
        val pkg = app.entry.packageName
        manifests[pkg]?.takeIf { it.first == version }?.let { return it.second }
        val parsed = ManifestReader.read(pm, app.launcher.applicationInfo)
        manifests[pkg] = version to parsed
        return parsed
    }

    /** `@2131755203` (a compiled-XML resource reference) → its text, or null. */
    private fun resolve(resources: Resources, ref: String): String? {
        val id = ref.removePrefix("@").toIntOrNull()?.takeIf { it != 0 } ?: return null
        return try {
            val value = TypedValue()
            resources.getValue(id, value, true)
            value.coerceToString()?.toString()
        } catch (e: Resources.NotFoundException) {
            null
        }
    }

    private fun isExported(packageName: String, className: String): Boolean = try {
        val component = ComponentName(packageName, className)
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getActivityInfo(component, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getActivityInfo(component, 0)
        }
        info.exported && info.packageName == packageName && info.permission.let { it == null || hasPermission(it) } &&
            when (pm.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> info.isEnabled
                else -> false
            }
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    private fun hasPermission(permission: String): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** A safety net for changes [PackageManager.getChangedPackages] doesn't report (e.g. components turned off). */
        const val MAX_SCAN_AGE_MS = 10 * 60_000L

        /** How long "no package changed" is trusted before asking Android again. */
        const val RECHECK_MS = 2_000L
    }
}
