package dev.phonebridge.core.plugins

import dev.phonebridge.core.tools.ToolNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A plugin file's contents. [path] (e.g. `plugins/spotify/plugin.yaml`) names it in messages and gives its folder. */
data class PluginFile(val path: String, val text: String) {
    /** The folder the file is in (`spotify`), or null when [path] has none. */
    val folder: String?
        get() = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }.dropLast(1).lastOrNull()
}

/** The outcome of loading one plugin file: the [plugin] when valid, else null and the [errors]. */
class PluginLoadResult(val plugin: AppPlugin?, val errors: List<String>) {
    val isValid: Boolean get() = plugin != null
}

/**
 * Loads one plugin file: safe YAML ([PluginYaml]), then [PluginParser] and [PluginValidator] (whose
 * problems are reported together unless the file's structure is broken), then, if all is well, the
 * examples ([PluginExamples]).
 * Errors read `<plugin id>: <path>: <message>`, e.g. `spotify: actions[1].android.intent.extras.count: …`.
 * Pure and thread-safe.
 */
object PluginLoader {
    /**
     * [folder]: the plugin id must equal it, unless it starts with `_` or is null (a file imported on the
     * phone). [label] names the file when its id can't be read. [runExamples]: also run §7 examples.
     */
    fun load(
        text: String,
        source: PluginSource = PluginSource.BUNDLED,
        folder: String? = null,
        label: String? = null,
        toolNames: Set<String> = ToolNames.ALL,
        runExamples: Boolean = true,
    ): PluginLoadResult {
        val root = try {
            PluginYaml.load(text)
        } catch (e: PluginFormatException) {
            return PluginLoadResult(null, listOf("${label ?: "plugin"}: ${e.message}"))
        }
        val id = ((root as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        val prefix = id?.takeIf { PluginRules.PLUGIN_ID.matches(it) } ?: label ?: "plugin"
        val parser = PluginParser(source)
        val plugin = parser.parse(root)
        val problems = ArrayList(parser.problems)
        if (plugin != null) problems += PluginValidator.validate(plugin, folder, toolNames)
        if (plugin != null && problems.isEmpty() && runExamples) problems += PluginExamples.run(plugin)
        if (problems.isNotEmpty() || plugin == null) return PluginLoadResult(null, problems.map { "$prefix: $it" })
        return PluginLoadResult(plugin, emptyList())
    }

    /** Loads [file], checking its id against its folder. */
    fun load(
        file: PluginFile,
        source: PluginSource = PluginSource.BUNDLED,
        toolNames: Set<String> = ToolNames.ALL,
        runExamples: Boolean = true,
    ): PluginLoadResult = load(file.text, source, file.folder, file.path, toolNames, runExamples)
}

/** A catalog plus the files that could not be added to it. */
class PluginCatalogLoad(val catalog: PluginCatalog, val errors: List<String>)

/**
 * The plugins available on this device, by id and by Android package (docs/PLUGINS.md). Immutable and
 * thread-safe; [merge] and [with] return new catalogs.
 *
 * Invariant: plugins that share a package never share an action id, so every `<package>/<action>` has
 * one meaning. A local plugin with the id of a bundled one replaces it.
 */
class PluginCatalog private constructor(val plugins: List<AppPlugin>) {
    private val byId: Map<String, AppPlugin> = plugins.associateBy { it.id }
    private val byPackage: Map<String, List<AppPlugin>> = buildMap<String, MutableList<AppPlugin>> {
        for (p in plugins) for (pkg in p.androidPackages) getOrPut(pkg) { ArrayList() }.add(p)
    }

    fun plugin(id: String): AppPlugin? = byId[id]

    /** Plugins that apply to [packageName], in catalog order. */
    fun pluginsFor(packageName: String): List<AppPlugin> = byPackage[packageName].orEmpty()

    /** Every package some plugin applies to. */
    val packages: Set<String> get() = byPackage.keys

    /**
     * Why [plugin] can't join this catalog (empty if it can): it has an action id that another plugin
     * (with a different id) already uses for one of the same packages.
     */
    fun conflicts(plugin: AppPlugin): List<String> {
        val problems = ArrayList<String>()
        for (pkg in plugin.androidPackages) {
            for (other in pluginsFor(pkg)) {
                if (other.id == plugin.id) continue
                val clash = plugin.actions.map { it.id }.intersect(other.actions.map { it.id }.toSet())
                if (clash.isNotEmpty()) {
                    problems += "${plugin.id}: actions ${clash.joinToString(", ")} clash with plugin '${other.id}' for $pkg; " +
                        "rename them or merge the plugins"
                }
            }
        }
        return problems
    }

    /** This catalog with [plugin] added, replacing a plugin with the same id. @throws IllegalArgumentException on [conflicts]. */
    fun with(plugin: AppPlugin): PluginCatalog {
        val problems = conflicts(plugin)
        require(problems.isEmpty()) { problems.joinToString("; ") }
        val index = plugins.indexOfFirst { it.id == plugin.id }
        return PluginCatalog(if (index < 0) plugins + plugin else plugins.toMutableList().also { it[index] = plugin })
    }

    /**
     * This catalog with each of [local] added in order as a [PluginSource.LOCAL] plugin, whatever its
     * source says (so its actions always need approval), replacing a plugin with the same id (see [with]).
     * Plugins that conflict are left out and reported.
     */
    fun merge(local: List<AppPlugin>): PluginCatalogLoad {
        var catalog = this
        val errors = ArrayList<String>()
        for (plugin in local.map { it.copy(source = PluginSource.LOCAL) }) {
            val problems = catalog.conflicts(plugin)
            if (problems.isEmpty()) catalog = catalog.with(plugin) else errors += problems
        }
        return PluginCatalogLoad(catalog, errors)
    }

    companion object {
        val EMPTY = PluginCatalog(emptyList())

        /**
         * Loads [files] (each fully checked by [PluginLoader]) into a catalog. Files in folders starting with
         * `_` (such as `_template`) are never loaded. Invalid files, duplicate ids and conflicting plugins are
         * left out and reported in [PluginCatalogLoad.errors]. [runExamples]: also run each plugin's examples
         * (a device loading its bundled plugins can skip them: `:core:test` already ran them).
         */
        fun load(
            files: List<PluginFile>,
            source: PluginSource = PluginSource.BUNDLED,
            toolNames: Set<String> = ToolNames.ALL,
            runExamples: Boolean = true,
        ): PluginCatalogLoad {
            var catalog = EMPTY
            val errors = ArrayList<String>()
            for (file in files.sortedBy { it.path }) {
                if (file.folder?.startsWith("_") == true) continue
                val result = PluginLoader.load(file, source, toolNames, runExamples)
                val plugin = result.plugin
                when {
                    plugin == null -> errors += result.errors
                    catalog.plugin(plugin.id) != null -> errors += "${file.path}: a plugin with id '${plugin.id}' is already loaded"
                    else -> {
                        val problems = catalog.conflicts(plugin)
                        if (problems.isEmpty()) catalog = catalog.with(plugin) else errors += problems
                    }
                }
            }
            return PluginCatalogLoad(catalog, errors)
        }
    }
}
