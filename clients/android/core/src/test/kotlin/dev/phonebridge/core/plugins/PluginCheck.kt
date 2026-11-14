package dev.phonebridge.core.plugins

import java.io.File
import kotlin.system.exitProcess

/**
 * Checks plugin folders on disk (docs/PLUGINS.md §1, §10): `./gradlew :core:checkPlugins [-Ppath=plugins/<id>]`,
 * wrapped by `scripts/plugin-check.sh`. Also used by [BundledPluginsTest], so `:core:test` fails on a bad plugin.
 */
object PluginDirectoryCheck {
    class Report(val lines: List<String>, val checked: Int, val failed: Int) {
        val ok: Boolean get() = failed == 0 && checked > 0
        override fun toString(): String = lines.joinToString("\n")
    }

    const val SCHEMA_COMMENT = "# yaml-language-server: \$schema=../plugin.schema.json"

    /**
     * Checks [target]: a `plugins/` directory (every folder in it, plus clashes between plugins), one plugin
     * folder, or one `plugin.yaml`. Paths are shown relative to [base].
     */
    fun check(target: File, base: File? = null): Report {
        fun shown(f: File): String {
            val file = f.absoluteFile.normalize()
            val relative = base?.let { file.relativeToOrNull(it.absoluteFile.normalize())?.path }
            return relative?.takeIf { !it.startsWith("..") } ?: file.path
        }
        val lines = ArrayList<String>()
        val files: List<File>
        var failed = 0
        when {
            target.isFile -> files = listOf(target)
            File(target, "plugin.yaml").isFile -> files = listOf(File(target, "plugin.yaml"))
            target.isDirectory -> {
                val folders = target.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
                files = folders.mapNotNull { folder ->
                    File(folder, "plugin.yaml").takeIf { it.isFile } ?: run {
                        lines += "${shown(folder)}: missing plugin.yaml"
                        failed++
                        null
                    }
                }
            }
            else -> return Report(listOf("${shown(target)}: no such file or folder"), checked = 0, failed = 1)
        }
        val loaded = ArrayList<PluginFile>()
        for (file in files) {
            val pluginFile = PluginFile(shown(file), file.readText())
            val result = PluginLoader.load(pluginFile)
            val plugin = result.plugin
            if (plugin == null) {
                failed++
                lines += "${pluginFile.path}: FAILED"
                result.errors.forEach { lines += "  $it" }
                continue
            }
            loaded += pluginFile
            val examples = plugin.actions.sumOf { it.examples.size }
            val note = if (pluginFile.text.lineSequence().firstOrNull()?.trim() != SCHEMA_COMMENT) {
                " (tip: start the file with \"$SCHEMA_COMMENT\" for editor completion)"
            } else {
                ""
            }
            lines += "${pluginFile.path}: OK (${plugin.actions.size} actions, $examples examples)$note"
        }
        // Clashes between plugins (and duplicate ids) only show up when they are loaded together.
        if (loaded.size > 1) {
            val catalog = PluginCatalog.load(loaded)
            catalog.errors.forEach {
                failed++
                lines += "  $it"
            }
        }
        lines += "${files.size} plugin file${if (files.size == 1) "" else "s"} checked, " +
            if (failed == 0) "no problems." else "$failed with problems."
        return Report(lines, checked = files.size, failed = failed)
    }
}

/** `checkPlugins` entry point: args = [target] [repo root]. Exits 1 when a plugin has problems. */
fun main(args: Array<String>) {
    val target = File(args.getOrNull(0) ?: "plugins")
    val base = args.getOrNull(1)?.let(::File)
    val report = PluginDirectoryCheck.check(target, base)
    println(report)
    if (!report.ok) exitProcess(1)
}
