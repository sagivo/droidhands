package dev.phonebridge.android.plugins

import dev.phonebridge.core.plugins.PluginCatalog
import dev.phonebridge.core.plugins.PluginFile
import dev.phonebridge.core.plugins.PluginSource

/**
 * The app plugins this phone uses (docs/PLUGINS.md): the bundled ones ([bundledFiles], loaded once, lazily)
 * plus, while [localEnabled] says so, the valid local plugins of [local]. A local plugin with a bundled
 * plugin's id replaces it; one that clashes with another plugin's action ids is left out.
 *
 * [catalog] is called on every app-action use, so it is cheap when nothing changed: local plugins are
 * reloaded only when their files change ([LocalPlugins.signature]), which lets `scripts/plugin-push.sh`
 * take effect on the next call. Files that can't be loaded are skipped and reported through [log] (and,
 * for local plugins, in Settings > Developer). Thread-safe; the first call (and a reload) does blocking
 * file I/O, so call off the main thread.
 */
class PluginRepository(
    private val bundledFiles: () -> List<PluginFile>,
    val local: LocalPlugins,
    private val localEnabled: () -> Boolean,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()

    @Volatile private var bundled: PluginCatalog? = null

    @Volatile private var merged: Merged? = null

    private class Merged(val signature: String, val catalog: PluginCatalog, val entries: List<LocalPluginEntry>, val errors: List<String>)

    /** The reviewed plugins shipped in the app. Examples are not run again (`:core:test` ran them at build time). */
    fun bundled(): PluginCatalog = bundled ?: synchronized(lock) {
        bundled ?: loadBundled().also { bundled = it }
    }

    /** What app actions use now: [bundled], plus the local plugins when they are turned on. */
    fun catalog(): PluginCatalog {
        val base = bundled()
        if (!localEnabled()) return base
        return current(base).catalog
    }

    /** The local plugin files and their problems (also files left out because they clash), for Settings. */
    fun localEntries(): List<LocalPluginEntry> {
        val state = current(bundled())
        // A valid file is still left out of the catalog when its actions clash with another plugin's.
        return state.entries.map { e ->
            if (e.plugin == null || state.catalog.plugin(e.id)?.source == PluginSource.LOCAL) {
                e
            } else {
                val why = state.errors.filter { it.startsWith("${e.id}:") }
                e.copy(plugin = null, errors = why.ifEmpty { listOf("${e.id}: left out because it clashes with another plugin") })
            }
        }
    }

    private fun current(base: PluginCatalog): Merged {
        val signature = local.signature()
        merged?.takeIf { it.signature == signature }?.let { return it }
        synchronized(lock) {
            merged?.takeIf { it.signature == signature }?.let { return it }
            val entries = local.entries()
            val load = base.merge(entries.mapNotNull { it.plugin })
            val problems = entries.flatMap { it.errors } + load.errors
            problems.forEach { log("Local plugin skipped: $it") }
            return Merged(signature, load.catalog, entries, load.errors).also { merged = it }
        }
    }

    private fun loadBundled(): PluginCatalog {
        val files = try {
            bundledFiles()
        } catch (e: Exception) {
            log("Could not read the bundled plugins: ${e.message ?: e.javaClass.simpleName}")
            return PluginCatalog.EMPTY
        }
        val load = PluginCatalog.load(files, PluginSource.BUNDLED, runExamples = false)
        load.errors.forEach { log("Bundled plugin skipped: $it") }
        return load.catalog
    }
}
