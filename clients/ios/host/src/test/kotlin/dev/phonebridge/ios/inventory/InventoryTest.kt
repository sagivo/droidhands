package dev.phonebridge.ios.inventory

import dev.phonebridge.ios.FakeInventory
import dev.phonebridge.ios.FakeRunner
import dev.phonebridge.ios.Fixtures
import dev.phonebridge.ios.app
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InventoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `simctl listing - names, system flag, hidden apps left out`() {
        val root = Plist.parseJson(Fixtures.text("simctl-listapps.json").replace("\$BUNDLE_ROOT", "/x").toByteArray()) as JsonObject
        val apps = SimctlInventory.parseListing(root)
        assertEquals(listOf("PhoneBridgeRunner-Runner", "Safari", "Spotify"), apps.map { it.name })
        val safari = apps.single { it.bundleId == "com.apple.mobilesafari" }
        assertTrue(safari.system)
        assertEquals("8619.1.26.10.5", safari.build)
        assertNull(safari.version)
        val spotify = apps.single { it.bundleId == "com.spotify.client" }
        assertEquals("8.9.0", spotify.version)
        assertEquals("/x/Spotify.app", spotify.bundlePath)
        assertTrue(!spotify.system)
    }

    @Test
    fun `simctl reads each bundle's Info plist and entitlements through plutil and codesign`() {
        val root = tmp.newFolder("apps")
        val bundle = File(root, "Spotify.app").apply { mkdirs() }
        File(bundle, "Info.plist").writeText("binary plist stand-in")
        val listing = Fixtures.text("simctl-listapps.json").replace("\$BUNDLE_ROOT", root.path)
        val runner = FakeRunner { cmd, stdin ->
            when {
                cmd.take(3) == listOf("xcrun", "simctl", "listapps") -> FakeRunner.ok("{\n  \"old-style\" = plist;\n}")
                cmd.first() == "plutil" && cmd.last() == "-" -> {
                    assertEquals("{\n  \"old-style\" = plist;\n}", stdin!!.toString(Charsets.UTF_8))
                    FakeRunner.ok(listing)
                }
                cmd.first() == "plutil" && cmd.last() == File(bundle, "Info.plist").path -> FakeRunner.ok(Fixtures.text("spotify-info.json"))
                cmd.first() == "codesign" && cmd.last() == bundle.path -> FakeRunner.ok(Fixtures.bytes("spotify-entitlements.xml"))
                else -> FakeRunner.fail("unexpected ${cmd.joinToString(" ")}")
            }
        }
        val inventory = SimctlInventory("UDID-1", runner)
        val spotify = inventory.apps().single { it.bundleId == "com.spotify.client" }
        assertTrue("CFBundleURLTypes" in spotify.infoPlist)
        assertEquals(
            listOf("applinks:open.spotify.com", "applinks:*.spotify.link?mode=developer", "webcredentials:accounts.spotify.com", "applinks:not a domain"),
            spotify.entitlements.strings("com.apple.developer.associated-domains"),
        )
        // The listing's own keys stay (the full plist is merged over them).
        assertEquals("Spotify", spotify.infoPlist.str("CFBundleExecutable"))

        // A second scan reuses the details of the same build.
        val before = runner.calls.count { it.first() == "codesign" }
        inventory.apps()
        assertEquals(before, runner.calls.count { it.first() == "codesign" })
    }

    @Test
    fun `simctl failure says what to check`() {
        val inventory = SimctlInventory("UDID-1", FakeRunner { _, _ -> FakeRunner.fail("Invalid device: UDID-1") })
        val e = assertFailsWith<InventoryException> { inventory.apps() }
        assertTrue("Invalid device: UDID-1" in e.message!!)
        assertTrue("booted" in e.message!!)
    }

    @Test
    fun `plutil falls back to XML when JSON can't hold the plist`() {
        val file = tmp.newFile("Info.plist")
        val runner = FakeRunner { cmd, _ ->
            when {
                "json" in cmd -> FakeRunner.fail("invalid object in plist for destination format")
                "xml1" in cmd -> FakeRunner.ok(Fixtures.bytes("info-with-date.xml"))
                else -> FakeRunner.fail("?")
            }
        }
        val info = PlistTool.readFile(runner, file)!!
        assertEquals("Notes & More", info.str("CFBundleName"))
        assertEquals("2026-01-02T03:04:05Z", info.str("BuildDate"))
        assertEquals("aGVsbG8=", info.str("Blob"))
        assertEquals(JsonPrimitive(42), info["Count"])
        assertEquals(listOf("notes"), (info.arr("CFBundleURLTypes").single() as JsonObject).strings("CFBundleURLSchemes"))
    }

    @Test
    fun `xml plists never load external entities`() {
        val xxe = """<?xml version="1.0"?>
            <!DOCTYPE plist [ <!ENTITY x SYSTEM "file:///etc/passwd"> ]>
            <plist version="1.0"><dict><key>a</key><string>&x;</string></dict></plist>""".trimIndent()
        val parsed = Plist.parseXml(xxe.toByteArray()) as? JsonObject
        assertTrue(parsed == null || "root:" !in parsed.str("a").orEmpty())
    }

    @Test
    fun `devicectl - basic fields, system from removable, hidden left out`() {
        val apps = DevicectlInventory.parse(Fixtures.json("devicectl-apps.json"))
        assertEquals(listOf("com.apple.mobilesafari", "com.spotify.client"), apps.map { it.bundleId })
        assertTrue(apps.first().system)
        assertEquals("8.9.0", apps.last().version)
        assertEquals("8.9.0.575", apps.last().build)
        assertTrue(apps.all { it.infoPlist.isEmpty() && it.bundlePath == null })
    }

    @Test
    fun `devicectl writes JSON to the output file, and drops an option older Xcodes don't know`() {
        var sawIncludeAll = 0
        val runner = FakeRunner { cmd, _ ->
            if ("--include-all-apps" in cmd) {
                sawIncludeAll++
                return@FakeRunner FakeRunner.fail("Error: Unknown option '--include-all-apps'", 64)
            }
            val out = File(cmd[cmd.indexOf("--json-output") + 1])
            out.writeText(Fixtures.text("devicectl-apps.json"))
            FakeRunner.ok("")
        }
        val inventory = DevicectlInventory("00008110-000A1B2C3D4E5F60", runner)
        assertEquals(2, inventory.apps().size)
        assertEquals(2, inventory.apps().size)
        assertEquals(1, sawIncludeAll)
        assertTrue(inventory.limits.infoPlist!!.contains("pymobiledevice3"))
    }

    @Test
    fun `pymobiledevice3 - Info plist keys and entitlements, Hidden apps left out`() {
        val apps = Pymobiledevice3Inventory.parse(Fixtures.json("pymobiledevice3-apps.json"))
        assertEquals(listOf("Safari", "Spotify"), apps.map { it.name })
        val spotify = apps.last()
        assertTrue("CFBundleURLTypes" in spotify.infoPlist)
        assertTrue("Entitlements" !in spotify.infoPlist)
        assertEquals(listOf("applinks:open.spotify.com"), spotify.entitlements.strings("com.apple.developer.associated-domains"))
        assertNull(spotify.bundlePath)
    }

    @Test
    fun `pymobiledevice3 command and errors`() {
        val runner = FakeRunner { _, _ -> FakeRunner.fail("ERROR Device is not connected") }
        val e = assertFailsWith<InventoryException> { Pymobiledevice3Inventory("abc", runner).apps() }
        assertTrue("Device is not connected" in e.message!!)
        assertEquals(listOf("pymobiledevice3", "apps", "list", "--udid", "abc"), runner.calls.single())
    }

    @Test
    fun `cache - reused within the TTL, stale list served when a refresh fails`() {
        var now = 0L
        var reads = 0
        var failing = false
        val delegate = object : AppInventory {
            override val source = "x"
            override val limits = InventoryLimits()
            override fun apps(): List<IosApp> {
                reads++
                if (failing) throw InventoryException("gone")
                return listOf(app("a.b", "A"))
            }
        }
        val cache = CachedInventory(delegate, ttlMs = 1_000, clock = { now })
        assertNull(cache.cached)
        cache.apps()
        cache.apps()
        assertEquals(1, reads)
        now = 2_000
        failing = true
        assertEquals("A", cache.apps().single().name)
        assertEquals(2, reads)
        cache.invalidate()
        cache.apps()
        assertEquals(3, reads)

        val empty = CachedInventory(FakeInventory(emptyList()).let { inv -> object : AppInventory by inv { override fun apps() = throw InventoryException("no") } })
        assertFailsWith<InventoryException> { empty.apps() }
    }
}
