package com.droidhands.android.plugins

import com.droidhands.core.plugins.AppPlugin
import com.droidhands.core.plugins.PluginCatalog
import com.droidhands.core.plugins.PluginLoader
import com.droidhands.core.plugins.PluginRules
import com.droidhands.core.plugins.PluginSource
import com.droidhands.core.plugins.PluginYaml
import com.droidhands.core.tools.ToolNames
import java.io.File
import java.io.IOException

/** One file of the local plugins folder: its [plugin] when it is valid, else why not ([errors]). */
data class LocalPluginEntry(val id: String, val plugin: AppPlugin?, val errors: List<String>) {
    val isValid: Boolean get() = plugin != null
}

/** The outcome of [LocalPlugins.import]. */
sealed interface ImportResult {
    /** Saved as `<id>.yaml`; [replaced]: a local plugin with the same id was there before. */
    data class Imported(val plugin: AppPlugin, val replaced: Boolean) : ImportResult

    /** Not saved; [errors] say why (the validator's messages, or a clash with another plugin). */
    data class Rejected(val errors: List<String>) : ImportResult
}

/**
 * Plugins imported on the phone for testing (docs/PLUGINS.md §10): one `<id>.yaml` per plugin in [dir]
 * (app-private `files/local-plugins`). Files get there through Settings > Developer > Import plugin
 * ([import], which validates them first) or, on debug builds, `scripts/plugin-push.sh` (checked when
 * loaded, see [entries]). Every file is validated and its examples run, like a pull request's would be;
 * a local plugin's actions always need approval ([PluginCatalog.merge] marks them [PluginSource.LOCAL]).
 *
 * Pure JVM (java.io only), so it is unit-tested; blocking file I/O: call off the main thread.
 */
class LocalPlugins(val dir: File, private val toolNames: Set<String> = ToolNames.ALL) {
    /**
     * Changes whenever a plugin file is added, removed or rewritten (a re-push rewrites a file in place,
     * which does not change the folder's own modification time), so callers can reload only when needed.
     */
    fun signature(): String {
        val files = yamlFiles()
        if (files.isEmpty()) return if (dir.isDirectory) "empty" else "none"
        return files.joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
    }

    /** Every `*.yaml` file in [dir], by id, each validated (with its examples). */
    fun entries(): List<LocalPluginEntry> = yamlFiles().map(::load)

    /**
     * Validates [text] (a plugin.yaml; [sourceName] names it in errors) and, if it is valid and fits with
     * [catalog] (the bundled plugins: a local plugin may replace one with its id, but not reuse another
     * plugin's action ids for the same package) and the other local plugins, saves it as `<id>.yaml`.
     */
    fun import(text: String, sourceName: String?, catalog: PluginCatalog): ImportResult {
        val label = sourceName?.takeIf { it.isNotBlank() } ?: "plugin"
        val result = PluginLoader.load(text, PluginSource.LOCAL, folder = null, label = label, toolNames = toolNames)
        val plugin = result.plugin ?: return ImportResult.Rejected(result.errors)
        val others = entries().mapNotNull { it.plugin }.filter { it.id != plugin.id }
        val withOthers = catalog.merge(others).catalog
        val clashes = withOthers.conflicts(plugin.copy(source = PluginSource.LOCAL))
        if (clashes.isNotEmpty()) return ImportResult.Rejected(clashes)
        val target = fileFor(plugin.id)
        val replaced = target.exists()
        try {
            write(target, text)
        } catch (e: IOException) {
            return ImportResult.Rejected(listOf("${plugin.id}: could not save the plugin on the phone (${e.message ?: e.javaClass.simpleName})"))
        }
        return ImportResult.Imported(plugin.copy(source = PluginSource.LOCAL), replaced)
    }

    /** Deletes the local plugin [id]. True if it existed. */
    fun remove(id: String): Boolean {
        if (!PluginRules.PLUGIN_ID.matches(id)) return false
        val file = fileFor(id)
        return file.exists() && file.delete()
    }

    private fun load(file: File): LocalPluginEntry {
        val id = file.name.removeSuffix(SUFFIX)
        val text = try {
            readLimited(file)
        } catch (e: IOException) {
            return LocalPluginEntry(id, null, listOf("${file.name}: could not be read (${e.message ?: e.javaClass.simpleName})"))
        }
        val result = PluginLoader.load(text, PluginSource.LOCAL, folder = null, label = file.name, toolNames = toolNames)
        val plugin = result.plugin ?: return LocalPluginEntry(id, null, result.errors)
        if (plugin.id != id) {
            return LocalPluginEntry(id, null, listOf("${file.name}: the file must be named after the plugin id: ${plugin.id}$SUFFIX"))
        }
        return LocalPluginEntry(id, plugin.copy(source = PluginSource.LOCAL), emptyList())
    }

    private fun yamlFiles(): List<File> =
        dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(SUFFIX) && !it.name.startsWith(".") }.sortedBy { it.name }

    private fun fileFor(id: String) = File(dir, "$id$SUFFIX")

    /** Writes through a temporary file, so a reader never sees half a plugin. */
    private fun write(target: File, text: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.name}")
        val tmp = File(dir, ".${target.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("could not replace ${target.name}")
        }
    }

    private fun readLimited(file: File): String {
        if (file.length() > MAX_FILE_BYTES) throw IOException("larger than ${MAX_FILE_BYTES / 1024} KB")
        return file.readText(Charsets.UTF_8)
    }

    companion object {
        /** The folder under the app's files dir (scripts/plugin-push.sh writes there too). */
        const val DIR = "local-plugins"
        const val SUFFIX = ".yaml"

        /** UTF-8 bytes; the YAML loader's own character limit applies after reading. */
        const val MAX_FILE_BYTES = PluginYaml.MAX_CHARS * 4L
    }
}
