package dev.phonebridge.android.tools.actions

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import java.util.zip.ZipFile

/**
 * Reads the intent filters an installed app declares from its own base APK (`AndroidManifest.xml`, compiled), since
 * Android has no public API that lists another app's filters. APKs are world-readable; nothing is executed. Returns
 * null when the APK can't be read or decoded: the app then just gets no manifest-based actions.
 */
internal object ManifestReader {
    /** Manifests larger than this are not decoded. */
    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024

    fun read(pm: PackageManager, info: ApplicationInfo): DeclaredManifest? {
        return try {
            val bytes = ZipFile(info.sourceDir).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: return null
                if (entry.size > MAX_MANIFEST_BYTES) return null
                zip.getInputStream(entry).use { it.readBytes() }
            }
            if (bytes.size > MAX_MANIFEST_BYTES) return null
            val resources = runCatching { pm.getResourcesForApplication(info) }.getOrNull()
            ManifestXml.parse(BinaryXml.decode(bytes)) { id -> resources?.let { label(it, id) } }
                .takeIf { it.packageName == info.packageName }
        } catch (e: Exception) {
            null
        }
    }

    private fun label(resources: Resources, id: Int): String? = try {
        resources.getText(id)?.toString()
    } catch (e: Resources.NotFoundException) {
        null
    }
}
