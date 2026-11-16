package dev.phonebridge.ios.host

import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.plugins.PluginCatalog
import dev.phonebridge.core.plugins.PluginFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * What the host keeps per iPhone in `~/.phonebridge/ios/<udid>/` (owner-only): the device identity (`device.secret`,
 * mode 0600: it is the phone's id on the relay), agent tokens, the audit log and app action health.
 */
class HostState(val dir: File) {
    val secretFile = File(dir, "device.secret")
    val tokensFile = File(dir, "tokens.json")
    val auditFile = File(dir, "audit.jsonl")
    val healthFile = File(dir, "action-health.json")

    init {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create $dir")
        ownerOnly(dir, "rwx------")
    }

    /** The saved identity, or a new one saved now. A damaged secret file is an error, never silently replaced. */
    fun identity(): DeviceIdentity {
        if (secretFile.isFile) {
            val text = secretFile.readText().trim()
            return try {
                DeviceIdentity.fromEncoded(text)
            } catch (e: IllegalArgumentException) {
                throw IOException("$secretFile is damaged (${e.message}). Move it away to start as a new device (agents must pair again).")
            }
        }
        val identity = DeviceIdentity.generate()
        val tmp = File.createTempFile(".device.secret.", ".tmp", dir)
        try {
            ownerOnly(tmp, "rw-------")
            tmp.writeText(identity.secretEncoded + "\n")
            Files.move(tmp.toPath(), secretFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            tmp.delete()
        }
        ownerOnly(secretFile, "rw-------")
        return identity
    }

    companion object {
        private val UDID = Regex("^[A-Za-z0-9-]{1,64}$")

        /** The state folder of [udid] under [home]. @throws IllegalArgumentException for a udid that isn't one. */
        fun forDevice(udid: String, home: File = File(System.getProperty("user.home"))): HostState {
            require(UDID.matches(udid)) { "'$udid' doesn't look like a device or simulator UDID" }
            return HostState(File(home, ".phonebridge/ios/$udid"))
        }

        private fun ownerOnly(file: File, perms: String) {
            try {
                Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(perms))
            } catch (e: UnsupportedOperationException) {
                // Not a POSIX file system: nothing to tighten.
            }
        }
    }
}

/** The reviewed plugins bundled into the host's resources by the build (`phonebridge/plugins/<id>.yaml` + `index.txt`). */
object BundledPlugins {
    private const val DIR = "phonebridge/plugins"

    fun files(loader: ClassLoader = BundledPlugins::class.java.classLoader): List<PluginFile> {
        val index = loader.getResourceAsStream("$DIR/index.txt")?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return emptyList()
        return index.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { id ->
            loader.getResourceAsStream("$DIR/$id.yaml")?.use { PluginFile("plugins/$id/plugin.yaml", it.readBytes().toString(Charsets.UTF_8)) }
        }
    }

    /** The catalog, and what couldn't be loaded (logged; :core:test already checks every bundled plugin). */
    fun load(log: (String) -> Unit): PluginCatalog {
        val loaded = PluginCatalog.load(files(), runExamples = false)
        loaded.errors.forEach { log("Plugin not loaded: $it") }
        return loaded.catalog
    }
}
