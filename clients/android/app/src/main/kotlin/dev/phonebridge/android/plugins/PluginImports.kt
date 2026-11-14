package dev.phonebridge.android.plugins

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Pure helpers of Settings > Developer > Import plugin (the system file picker). */
object PluginImports {
    /** MIME types offered to the file picker: document providers label YAML files inconsistently. */
    val MIME_TYPES: Array<String> = arrayOf(
        "application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml", "text/plain", "application/octet-stream",
    )

    /** Why a picked file is not a plugin file, judging by its [displayName]; null when it may be one (or has no name). */
    fun nameProblem(displayName: String?): String? {
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val lower = name.lowercase()
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return null
        return "$name: pick a plugin's plugin.yaml file (see docs/PLUGINS.md)"
    }

    /** Reads [input] as UTF-8. @throws IOException if it is longer than [maxBytes]. */
    fun readText(input: InputStream, maxBytes: Long): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("the file is larger than ${maxBytes / 1024} KB")
            out.write(buffer, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** The snackbar text after [result] was saved. */
    fun importedMessage(result: ImportResult.Imported): String {
        val name = result.plugin.name
        return if (result.replaced) {
            "Updated the local plugin $name."
        } else {
            "Imported $name. Agents can use its actions now; each one asks for your approval."
        }
    }
}
