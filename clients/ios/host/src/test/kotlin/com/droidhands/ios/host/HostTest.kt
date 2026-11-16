package com.droidhands.ios.host

import com.droidhands.core.auth.ConfirmPolicy
import com.droidhands.core.auth.DeviceIdentity
import com.droidhands.core.auth.InMemoryTokenStore
import com.droidhands.core.auth.TokenManager
import com.droidhands.core.confirm.ConfirmationRequest
import com.droidhands.core.confirm.ConfirmationResult
import com.droidhands.core.tools.Risk
import com.droidhands.core.tools.Scopes
import com.droidhands.core.transport.PairingDecision
import com.droidhands.core.transport.PairingRequest
import com.droidhands.ios.HostOptions
import com.droidhands.ios.apps.AppMatcher
import com.droidhands.core.plugins.AppResolution
import com.droidhands.core.plugins.InstalledApp
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `dialog values are argv, never part of the script`() {
        val evil = "\" & (do shell script \"rm -rf ~\") & \""
        val cmd = MacDialogs.command("Allow $evil?", "--title", "Deny", "Allow", 30)
        val scriptLines = cmd.withIndex().filter { (i, _) -> i > 0 && cmd[i - 1] == "-e" }.map { it.value }
        assertEquals(MacDialogs.SCRIPT, scriptLines)
        assertTrue(scriptLines.none { "rm -rf" in it })
        val argv = cmd.drop(1 + 2 * MacDialogs.SCRIPT.size)
        assertEquals(listOf("Allow $evil?", "title", "Deny", "Allow", "30"), argv)
        assertEquals("a b", MacDialogs.oneLine("a\n\u0007b"))
    }

    private fun request(expiresIn: Long = 60_000) = PairingRequest(
        id = "p_1",
        clientName = "Claude",
        redirectHost = "claude.ai",
        displayCode = "AB12",
        remoteAddress = null,
        userAgent = null,
        expiresAt = System.currentTimeMillis() + expiresIn,
    )

    private fun tokens() = TokenManager(InMemoryTokenStore(), DeviceIdentity.generate().deviceId)

    @Test
    fun `Allow issues one token for the whole grant`() = runBlocking {
        val tokens = tokens()
        var shown = ""
        val handler = MacPairingHandler(tokens, { "Dana's iPhone" }, dialog = { message, _, deny, allow, seconds ->
            shown = message
            assertEquals("Deny", deny)
            assertEquals("Allow", allow)
            assertTrue(seconds in 1..60)
            MacDialogs.Answer.ALLOW
        }, log = {})
        val decision = handler.onPairRequest(request())
        assertIs<PairingDecision.Approved>(decision)
        val record = tokens.tokens.value.single()
        assertEquals(Scopes.ALL, record.scopes)
        assertEquals(ConfirmPolicy.NEVER, record.confirmPolicy)
        assertNull(record.maxRisk)
        assertEquals("pairing", record.origin)
        assertEquals("claude.ai", record.clientHost)
        assertTrue("Claude" in shown && "claude.ai" in shown && "AB12" in shown && "Dana's iPhone" in shown, shown)

        handler.onPairFinished("p_1", record.id, completed = false)
        assertTrue(tokens.tokens.value.isEmpty(), "a token no agent picked up is deleted")
    }

    @Test
    fun `Deny and timeouts issue nothing`() = runBlocking {
        val tokens = tokens()
        for ((answer, reason) in listOf(MacDialogs.Answer.DENY to "denied", MacDialogs.Answer.TIMED_OUT to "timeout", MacDialogs.Answer.FAILED to "denied")) {
            val handler = MacPairingHandler(tokens, { "iPhone" }, dialog = { _, _, _, _, _ -> answer }, log = {})
            assertEquals(PairingDecision.Denied(reason), handler.onPairRequest(request()))
        }
        assertTrue(tokens.tokens.value.isEmpty())
    }

    @Test
    fun `approvals map dialog answers`() = runBlocking {
        val req = ConfirmationRequest("c1", "Claude", "sms_send", "Send SMS", "Send 'hi' to Dana", Risk.SENSITIVE, "relay")
        assertEquals(ConfirmationResult.APPROVED, MacConfirmations(60) { m, _, _, _, _ -> assertTrue("Send 'hi' to Dana" in m); MacDialogs.Answer.ALLOW }.requestConfirmation(req))
        assertEquals(ConfirmationResult.DENIED, MacConfirmations(60) { _, _, _, _, _ -> MacDialogs.Answer.DENY }.requestConfirmation(req))
        assertEquals(ConfirmationResult.TIMED_OUT, MacConfirmations(60) { _, _, _, _, _ -> MacDialogs.Answer.TIMED_OUT }.requestConfirmation(req))
    }

    @Test
    fun `state keeps the device secret owner-only and stable`() {
        val state = HostState.forDevice("ABCD-1234", home = tmp.root)
        val a = state.identity()
        val b = state.identity()
        assertEquals(a.deviceId, b.deviceId)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(state.secretFile.toPath())))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(state.dir.toPath())))
        assertEquals(File(tmp.root, ".droidhands/ios/ABCD-1234"), state.dir)
        assertFailsWith<IllegalArgumentException> { HostState.forDevice("../../etc", home = tmp.root) }
    }

    @Test
    fun `options`() {
        val o = HostOptions.parse(listOf("--simulator", "ABC-1", "--local-port", "8765", "--relay", "https://relay.example"))
        assertTrue(o.simulator)
        assertEquals("ABC-1", o.udid)
        assertEquals(8765, o.localPort)
        assertEquals("https://relay.example", o.relayUrl)
        assertEquals("http://127.0.0.1:8101", o.runnerUrl)
        assertFailsWith<IllegalArgumentException> { HostOptions.parse(listOf("--device", "X")) }
        assertFailsWith<IllegalArgumentException> { HostOptions.parse(listOf("--simulator", "A", "--device", "B", "--local-port", "1")) }
        assertFailsWith<IllegalArgumentException> { HostOptions.parse(listOf("--local-port", "1")) }
    }

    @Test
    fun `bundled plugins are on the classpath`() {
        val files = BundledPlugins.files()
        assertTrue(files.isNotEmpty())
        assertTrue(files.all { it.path.startsWith("plugins/") && it.path.endsWith("/plugin.yaml") && it.folder != null })
        assertTrue(BundledPlugins.load {}.plugins.isNotEmpty())
    }

    @Test
    fun `app names resolve like on Android, with access applied`() {
        val apps = listOf(
            InstalledApp("com.spotify.client", "Spotify"),
            InstalledApp("com.google.Maps", "Google Maps"),
            InstalledApp("com.apple.Maps", "Maps"),
        )
        assertEquals("com.spotify.client", (AppMatcher.resolve("spotfy", apps) { true } as AppResolution.Found).app.packageName)
        assertEquals("com.apple.Maps", (AppMatcher.resolve("maps", apps) { true } as AppResolution.Found).app.packageName)
        assertIs<AppResolution.Blocked>(AppMatcher.resolve("Spotify", apps) { it != "com.spotify.client" })
        assertEquals("com.google.Maps", (AppMatcher.resolve("google maps", apps) { true } as AppResolution.Found).app.packageName)
    }
}
