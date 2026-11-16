package dev.phonebridge.ios.apps

import dev.phonebridge.core.plugins.ActionRequest
import dev.phonebridge.core.plugins.ActionResult
import dev.phonebridge.core.plugins.AppActionCatalog
import dev.phonebridge.core.plugins.IntegrationException
import dev.phonebridge.core.plugins.PluginCatalog
import dev.phonebridge.core.plugins.RenderedUrl
import dev.phonebridge.core.plugins.UrlRecipe
import dev.phonebridge.core.tools.AppBlockedException
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolContent
import dev.phonebridge.core.tools.ToolException
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.ios.FakeDevice
import dev.phonebridge.ios.FakeInventory
import dev.phonebridge.ios.access
import dev.phonebridge.ios.app
import dev.phonebridge.ios.ctx
import dev.phonebridge.ios.inventory.CachedInventory
import dev.phonebridge.ios.tools.iosTools
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinksTest {
    private val spotify = app("com.spotify.client", "Spotify", schemes = listOf("spotify"), domains = listOf("open.spotify.com", "*.spotify.link"))
    private val maps = app("com.apple.Maps", "Maps", schemes = listOf("maps"), system = true)
    private val bank = app("com.example.bank", "Bank", schemes = listOf("bankapp"))
    private val installed = listOf(spotify, maps, bank)

    @Test
    fun `dangerous schemes are refused, bare hosts become https`() {
        for (bad in listOf("javascript:alert(1)", "JavaScript:alert(1)", "file:///etc/hosts", "data:text/html,hi", "intent://x#Intent;end")) {
            assertFailsWith<ToolException>(bad) { IosLinks.check(bad) }
        }
        assertEquals(CheckedUrl("https://example.com/a", "https"), IosLinks.check("example.com/a"))
        assertEquals("spotify", IosLinks.check("Spotify:album:1").scheme)
        assertFailsWith<ToolException> { IosLinks.check("https:///nohost") }
        assertFailsWith<ToolException> { IosLinks.check("no scheme here") }
        assertFailsWith<ToolException> { IosLinks.check("https://a.com/\nx") }
    }

    @Test
    fun `an app declares its schemes and https on its domains, never http`() {
        assertTrue(IosLinks.declares(spotify, IosLinks.check("spotify:album:1")))
        assertTrue(IosLinks.declares(spotify, IosLinks.check("https://open.spotify.com/track/1")))
        assertTrue(IosLinks.declares(spotify, IosLinks.check("https://a.spotify.link/x")))
        assertFalse(IosLinks.declares(spotify, IosLinks.check("https://spotify.link/x")), "*. means subdomains only")
        assertFalse(IosLinks.declares(spotify, IosLinks.check("http://open.spotify.com/track/1")))
        assertFalse(IosLinks.declares(spotify, IosLinks.check("https://evil-open.spotify.com.attacker.net/")))
        assertFalse(IosLinks.declares(spotify, IosLinks.check("maps://?q=x")))
        val why = IosLinks.notDeclared(spotify, IosLinks.check("maps://?q=x"))
        assertTrue("'maps:' links" in why && "spotify:*" in why, why)
    }

    @Test
    fun `pinned link the app doesn't declare is refused before anything opens`() = runTest {
        val device = FakeDevice()
        val e = assertFailsWith<ToolException> { LinkOpener(device, pollMs = 1, settleMs = 5).open(IosLinks.check("maps://?q=x"), spotify, installed, access(), ctx()) }
        assertTrue("doesn't open 'maps:' links" in e.message!!, e.message)
        assertTrue(device.calls.none { it.startsWith("open:") })
    }

    @Test
    fun `pinned to a blocked app is refused before anything opens`() = runTest {
        val device = FakeDevice()
        val blocked = access(setOf("com.spotify.client"))
        assertFailsWith<AppBlockedException> {
            LinkOpener(device, pollMs = 1, settleMs = 5).open(IosLinks.check("spotify:x"), spotify, installed, blocked, ctx(blocked))
        }
        assertTrue(device.calls.isEmpty())
    }

    @Test
    fun `unpinned link whose every handler is blocked is refused before opening`() = runTest {
        val device = FakeDevice()
        val blocked = access(setOf("com.example.bank"))
        val e = assertFailsWith<AppBlockedException> {
            LinkOpener(device, pollMs = 1, settleMs = 5).open(IosLinks.check("bankapp://pay"), null, installed, blocked, ctx(blocked))
        }
        assertEquals("com.example.bank", e.packageName)
        assertTrue(device.calls.isEmpty())
    }

    @Test
    fun `link that lands in a blocked app goes Home and is refused`() = runTest {
        val device = FakeDevice()
        device.onOpen = { device.front = "com.example.bank" }
        val blocked = access(setOf("com.example.bank"))
        val e = assertFailsWith<AppBlockedException> {
            // iOS picked the bank for an https link PhoneBridge couldn't predict.
            LinkOpener(device, pollMs = 1, settleMs = 50).open(IosLinks.check("https://bank.example.com/x"), null, installed, blocked, ctx(blocked))
        }
        assertTrue("Home Screen" in e.message!!)
        assertEquals(listOf("open:https://bank.example.com/x", "global:home"), device.calls)
    }

    @Test
    fun `url_open reports which app came to the front`() = runTest {
        val (tools, device) = tools()
        device.onOpen = { device.front = "com.spotify.client" }
        val result = call(tools, "url_open", buildJsonObject {
            put("url", "https://open.spotify.com/track/1")
            put("package", "com.spotify.client")
        })
        assertEquals("com.spotify.client", result["app"]!!.jsonPrimitive.content)
        assertEquals("Spotify", result["app_label"]!!.jsonPrimitive.content)
        assertNull(result["note"])
    }

    @Test
    fun `url_open with a package that doesn't declare the link says what it does declare`() = runTest {
        val (tools, _) = tools()
        val e = assertFailsWith<ToolException> {
            call(tools, "url_open", buildJsonObject {
                put("url", "https://example.com/")
                put("package", "com.spotify.client")
            })
        }
        assertTrue("https links on example.com" in e.message!!, e.message)
        assertTrue("open.spotify.com" in e.message!!, e.message)
    }

    @Test
    fun `url_open notes when the pinned app didn't take the link`() = runTest {
        val (tools, device) = tools()
        device.onOpen = { device.front = "com.apple.mobilesafari" }
        val result = call(tools, "url_open", buildJsonObject {
            put("url", "https://open.spotify.com/track/1")
            put("package", "com.spotify.client")
        })
        assertEquals("com.apple.mobilesafari", result["app"]!!.jsonPrimitive.content)
        assertTrue("not in Spotify" in result["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun `url recipe actions check the installed app's schemes and report a different app as a failure`() = runTest {
        val device = FakeDevice()
        val actions = actions(device)
        actions.apps() // fills the cache unavailableReason reads
        val action = dev.phonebridge.core.plugins.PluginAction(
            id = "play",
            title = "Play",
            description = "Plays",
            risk = dev.phonebridge.core.tools.Risk.ACTION,
            ios = UrlRecipe("maps://?q=x"),
        )
        assertTrue("doesn't open 'maps:' links" in actions.unavailableReason("com.spotify.client", action)!!)
        assertNull(actions.unavailableReason("com.apple.Maps", action))

        device.onOpen = { device.front = "com.apple.mobilesafari" }
        val request = ActionRequest(
            IosAppActions.installed(spotify),
            action,
            null,
            RenderedUrl("spotify:album:1", "com.spotify.client"),
            JsonObject(emptyMap()),
        )
        assertFailsWith<IntegrationException> { actions.execute(request, ctx()) }
        device.onOpen = { device.front = "com.spotify.client" }
        val ok: ActionResult = actions.execute(request, ctx())
        assertEquals("com.spotify.client", ok.fields["handled_by"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the runner's own bundles are never listed`() = runTest {
        val actions = actions(FakeDevice(), extra = listOf(app("dev.phonebridge.runner.xctrunner", "Runner"), app("com.foo.UITests.xctrunner", "UITests")))
        assertEquals(setOf("com.spotify.client", "com.apple.Maps", "com.example.bank"), actions.installedApps().map { it.packageName }.toSet())
        assertEquals(setOf("dev.phonebridge.runner.xctrunner", "com.foo.UITests.xctrunner"), actions.ownBundlesSeen())
    }

    private fun actions(device: FakeDevice, extra: List<dev.phonebridge.ios.inventory.IosApp> = emptyList()) = IosAppActions(
        CachedInventory(FakeInventory(installed + extra)),
        LinkOpener(device, pollMs = 1, settleMs = 50),
        runnerProblem = { null },
        runSteps = { _, _, _ -> ActionResult() },
    )

    private fun tools(): Pair<Map<String, Tool>, FakeDevice> {
        val device = FakeDevice()
        val apps = actions(device)
        val registry = ToolRegistry()
        val catalog = AppActionCatalog({ PluginCatalog.EMPTY }, apps, registry::get)
        val list = iosTools(apps, device, LinkOpener(device, pollMs = 1, settleMs = 50), catalog, "fake")
        registry.registerAll(list)
        return list.associateBy { it.name } to device
    }

    private suspend fun call(tools: Map<String, Tool>, name: String, args: JsonObject): JsonObject {
        val result = tools.getValue(name).call(args, ctx())
        assertFalse(result.isError)
        return Json.parseToJsonElement((result.content.first() as ToolContent.Text).text) as JsonObject
    }
}
