package dev.phonebridge.ios.inventory

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * A real iPhone's apps from `pymobiledevice3 apps list --udid <udid>` (the device's installation service): JSON keyed
 * by bundle id, each value holding the app's Info.plist keys, `ApplicationType` (`User`, `System`, `Hidden`) and, for
 * most apps, `Entitlements`. iOS doesn't let a Mac read files inside another app's bundle on a real device, so App
 * Intents metadata is unreadable here.
 */
class Pymobiledevice3Inventory(
    private val udid: String,
    private val runner: CommandRunner = ProcessCommandRunner,
) : AppInventory {
    override val source: String get() = "pymobiledevice3"
    override val limits: InventoryLimits = InventoryLimits(bundleFiles = BUNDLE_FILES_ON_DEVICE)

    override fun apps(): List<IosApp> {
        val r = runner.run("pymobiledevice3", "apps", "list", "--udid", udid, timeoutMs = LIST_TIMEOUT_MS)
        if (!r.ok) {
            throw InventoryException(
                "Couldn't list the iPhone's apps (pymobiledevice3 apps list: ${r.firstErrorLine()}). Check that the iPhone is " +
                    "connected, unlocked and trusts this Mac.",
            )
        }
        val root = Plist.parseJson(r.stdout) as? JsonObject
            ?: throw InventoryException("Couldn't read the iPhone's app list: pymobiledevice3 printed something that isn't JSON.")
        return parse(root)
    }

    companion object {
        private const val LIST_TIMEOUT_MS = 90_000L

        /** pymobiledevice3's listing (bundle id → Info.plist keys) as apps, sorted by name; `Hidden` apps are left out. */
        fun parse(root: JsonObject): List<IosApp> = root.entries.mapNotNull { (key, value) ->
            val o = value as? JsonObject ?: return@mapNotNull null
            val type = o.str("ApplicationType")
            if (type.equals("Hidden", ignoreCase = true)) return@mapNotNull null
            if (o.strings("SBAppTags").any { it.equals("hidden", ignoreCase = true) }) return@mapNotNull null
            val id = o.str("CFBundleIdentifier") ?: key.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            IosApp(
                bundleId = id,
                name = AppNames.name(o, id),
                version = o.str("CFBundleShortVersionString"),
                build = o.str("CFBundleVersion"),
                system = type.equals("System", ignoreCase = true),
                bundlePath = null,
                infoPlist = JsonObject(o - "Entitlements"),
                entitlements = o.obj("Entitlements") ?: JsonObject(emptyMap()),
            )
        }.sortedBy { it.name.lowercase() }
    }
}

/**
 * A real iPhone's apps from Xcode's `xcrun devicectl device info apps --device <udid> --json-output <file>`: names,
 * versions and whether each app is removable, but no Info.plist or entitlements. Used when pymobiledevice3 isn't
 * installed; what that leaves out is said once to the agent ([limits]).
 */
class DevicectlInventory(
    private val udid: String,
    private val runner: CommandRunner = ProcessCommandRunner,
) : AppInventory {
    override val source: String get() = "devicectl"
    override val limits: InventoryLimits = InventoryLimits(
        infoPlist = DEVICECTL_ONLY,
        entitlements = DEVICECTL_ONLY,
        bundleFiles = BUNDLE_FILES_ON_DEVICE,
    )

    /** Whether this Xcode's devicectl takes `--include-all-apps` (system apps too); learned on the first call. */
    @Volatile
    private var includeAll: Boolean? = null

    override fun apps(): List<IosApp> {
        val out = File.createTempFile("phonebridge-apps-", ".json")
        try {
            val result = list(out)
            if (!result.ok) {
                throw InventoryException(
                    "Couldn't list the iPhone's apps (xcrun devicectl: ${result.firstErrorLine()}). Check that the iPhone is " +
                        "connected, unlocked, paired with this Mac and in Developer Mode.",
                )
            }
            val root = Plist.parseJson(out.readBytes()) as? JsonObject
                ?: throw InventoryException("Couldn't read the iPhone's app list: devicectl wrote no JSON.")
            return parse(root)
        } finally {
            out.delete()
        }
    }

    private fun list(out: File): CommandResult {
        val base = listOf("xcrun", "devicectl", "device", "info", "apps", "--device", udid, "--json-output", out.path)
        if (includeAll != false) {
            val all = runner.run(base + INCLUDE_ALL, null, LIST_TIMEOUT_MS)
            // Older devicectl versions don't know the option: list without it from then on.
            if (all.ok || INCLUDE_ALL !in all.stderr) return all.also { if (all.ok) includeAll = true }
            includeAll = false
        }
        return runner.run(base, null, LIST_TIMEOUT_MS)
    }

    companion object {
        private const val LIST_TIMEOUT_MS = 90_000L
        private const val INCLUDE_ALL = "--include-all-apps"

        /** devicectl's JSON (`{result: {apps: [{bundleIdentifier, name, version, bundleVersion, removable, hidden, url}]}}`). */
        fun parse(root: JsonObject): List<IosApp> {
            val apps = (root.obj("result")?.get("apps") as? JsonArray).orEmpty()
            return apps.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                if (o.bool("hidden") == true) return@mapNotNull null
                val id = o.str("bundleIdentifier") ?: return@mapNotNull null
                IosApp(
                    bundleId = id,
                    name = AppNames.name(o, id),
                    version = o.str("version"),
                    build = o.str("bundleVersion"),
                    system = o.bool("removable") == false || o.bool("defaultApp") == true,
                )
            }.distinctBy { it.bundleId }.sortedBy { it.name.lowercase() }
        }
    }
}

private const val DEVICECTL_ONLY =
    "Xcode's devicectl reports only app names and versions. Install pymobiledevice3 (pipx install pymobiledevice3) and " +
        "restart scripts/ios-host.sh so PhoneBridge can read what each app declares (its links and quick actions)."

private const val BUNDLE_FILES_ON_DEVICE =
    "App Intents are listed in files inside each app, which iOS doesn't let a Mac read on a real iPhone (only on a simulator)."
