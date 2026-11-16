package dev.phonebridge.ios.inventory

import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The apps of a simulator, from `xcrun simctl listapps <udid>` (an old-style plist, converted with `plutil`). A
 * simulator's app bundles are folders on this Mac, so each app's full `Info.plist` (`plutil`), entitlements
 * (`codesign -d --entitlements - --xml`, best effort) and bundle files are read too, once per installed build.
 */
class SimctlInventory(
    private val udid: String,
    private val runner: CommandRunner = ProcessCommandRunner,
) : AppInventory {
    override val source: String get() = "simctl"
    override val limits: InventoryLimits = InventoryLimits()

    private val details = ConcurrentHashMap<String, Pair<JsonObject, JsonObject>>()
    private val pool = Executors.newFixedThreadPool(DETAIL_THREADS) { r -> Thread(r, "simctl-details").apply { isDaemon = true } }

    override fun apps(): List<IosApp> {
        val listed = runner.run("xcrun", "simctl", "listapps", udid, timeoutMs = LIST_TIMEOUT_MS)
        if (!listed.ok) {
            throw InventoryException(
                "Couldn't list the simulator's apps (xcrun simctl listapps: ${listed.firstErrorLine()}). Check that simulator $udid is booted.",
            )
        }
        val root = PlistTool.toJson(runner, listed.stdout) as? JsonObject
            ?: throw InventoryException("Couldn't read the simulator's app list: plutil could not convert simctl's output.")
        val apps = parseListing(root)
        val futures = apps.map { app -> pool.submit<IosApp> { withDetails(app) } }
        return futures.mapIndexed { i, f ->
            try {
                f.get(DETAIL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                apps[i] // Its listing is still right; declarations are read again on the next scan.
            }
        }
    }

    private fun withDetails(app: IosApp): IosApp {
        val dir = app.bundleDir ?: return app
        val key = "${dir.path}|${app.build}|${app.version}"
        val (info, entitlements) = details[key] ?: run {
            val read = (PlistTool.readFile(runner, File(dir, "Info.plist")) ?: JsonObject(emptyMap())) to readEntitlements(dir)
            // Only a complete read is kept, so a transient failure is retried.
            if (read.first.isNotEmpty()) details[key] = read
            read
        }
        return app.copy(infoPlist = JsonObject(app.infoPlist + info), entitlements = entitlements)
    }

    private val strings = ConcurrentHashMap<String, Map<String, String>>()

    override fun infoPlistStrings(app: IosApp): Map<String, String> {
        val dir = app.bundleDir ?: return emptyMap()
        return strings.getOrPut("${dir.path}|${app.build}|${app.version}") {
            val file = listOf("en.lproj", "Base.lproj", "English.lproj").map { File(dir, "$it/InfoPlist.strings") }.firstOrNull { it.isFile }
            val parsed = file?.let { PlistTool.readFile(runner, it) } ?: JsonObject(emptyMap())
            parsed.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }.toMap()
        }
    }

    private fun readEntitlements(dir: File): JsonObject {
        val r = runner.run("codesign", "-d", "--entitlements", "-", "--xml", dir.path, timeoutMs = 10_000)
        if (!r.ok) return JsonObject(emptyMap())
        return Plist.parseXml(r.stdout) as? JsonObject ?: JsonObject(emptyMap())
    }

    companion object {
        private const val LIST_TIMEOUT_MS = 60_000L
        private const val DETAIL_TIMEOUT_MS = 20_000L
        private const val DETAIL_THREADS = 6

        /**
         * The apps in simctl's listing ([root]: bundle id → `{ApplicationType, CFBundleIdentifier, CFBundleDisplayName,
         * CFBundleName, CFBundleShortVersionString?, CFBundleVersion, Path, SBAppTags?, …}`), sorted by name. Apps tagged
         * `hidden` (system services without an icon) are left out; [IosApp.infoPlist] holds the listing's own keys.
         */
        fun parseListing(root: JsonObject): List<IosApp> = root.entries.mapNotNull { (key, value) ->
            val o = value as? JsonObject ?: return@mapNotNull null
            if (o.strings("SBAppTags").any { it.equals("hidden", ignoreCase = true) }) return@mapNotNull null
            val id = o.str("CFBundleIdentifier") ?: key.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            IosApp(
                bundleId = id,
                name = AppNames.name(o, id),
                version = o.str("CFBundleShortVersionString"),
                build = o.str("CFBundleVersion"),
                system = o.str("ApplicationType")?.equals("System", ignoreCase = true) == true,
                bundlePath = o.str("Path") ?: o.str("Bundle")?.let(AppNames::fileUrlPath),
                infoPlist = o,
            )
        }.sortedBy { it.name.lowercase() }
    }
}

/** `plutil` conversions: JSON first (fast to parse), XML when the plist holds dates or data that JSON can't. */
internal object PlistTool {
    fun toJson(runner: CommandRunner, plist: ByteArray): kotlinx.serialization.json.JsonElement? {
        Plist.parse(plist)?.let { return it }
        val json = runner.run("plutil", "-convert", "json", "-o", "-", "-", stdin = plist, timeoutMs = 20_000)
        if (json.ok) Plist.parseJson(json.stdout)?.let { return it }
        val xml = runner.run("plutil", "-convert", "xml1", "-o", "-", "-", stdin = plist, timeoutMs = 20_000)
        return if (xml.ok) Plist.parseXml(xml.stdout) else null
    }

    fun readFile(runner: CommandRunner, file: File): JsonObject? {
        if (!file.isFile) return null
        val json = runner.run("plutil", "-convert", "json", "-o", "-", file.path, timeoutMs = 10_000)
        if (json.ok) (Plist.parseJson(json.stdout) as? JsonObject)?.let { return it }
        val xml = runner.run("plutil", "-convert", "xml1", "-o", "-", file.path, timeoutMs = 10_000)
        return if (xml.ok) Plist.parseXml(xml.stdout) as? JsonObject else null
    }
}

/** Names and paths as Apple's tools give them (pure). */
internal object AppNames {
    /** CFBundleDisplayName, else CFBundleName, else [id]. */
    fun name(o: JsonObject, id: String): String =
        o.str("CFBundleDisplayName") ?: o.str("CFBundleName") ?: o.str("name") ?: id

    /** `file:///path/App.app/` → `/path/App.app`, or null for other URLs. */
    fun fileUrlPath(url: String): String? {
        if (!url.startsWith("file://")) return null
        return try {
            java.net.URI(url).path?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
