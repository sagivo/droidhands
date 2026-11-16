package dev.phonebridge.core.mcp

import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.audit.InMemoryAuditLog
import dev.phonebridge.core.auth.Ability
import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.auth.GrantDraft
import dev.phonebridge.core.auth.GrantMode
import dev.phonebridge.core.auth.InMemoryTokenStore
import dev.phonebridge.core.auth.IssuedToken
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.confirm.ConfirmationProvider
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.confirm.ConfirmationResult
import dev.phonebridge.core.policy.GlobalSecurity
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.reqString
import dev.phonebridge.core.tools.tool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The grant chosen at pairing through the MCP pipeline: no prompts inside it, immediate refusals outside it. */
class McpGrantTest {
    private val identity = DeviceIdentity(ByteArray(32) { it.toByte() })
    private val tokens = TokenManager(InMemoryTokenStore(), identity.deviceId)
    private val audit = InMemoryAuditLog()
    private val registry = ToolRegistry()
    private val prompts: MutableList<ConfirmationRequest> = Collections.synchronizedList(mutableListOf())
    private val sideEffects = AtomicInteger()
    private val labels = mapOf("com.whatsapp" to "WhatsApp", "com.google.android.gm" to "Gmail")

    init {
        registry.register(capabilitiesTool(registry))
        registry.register(tool("device_status", "Status", "d", Scopes.DEVICE, Risk.READ) { _, _ -> ToolResult.text("fine") })
        registry.register(
            tool("sms_send", "Send SMS", "d", Scopes.MESSAGES_SEND, Risk.SENSITIVE, confirmsInternally = true) { _, ctx ->
                ctx.requireConfirmation("Send SMS to Tania")
                sideEffects.incrementAndGet()
                ToolResult.text("sent")
            },
        )
        registry.register(tool("notification_action", "Tap notification action", "d", Scopes.NOTIFICATIONS_ACT, Risk.SENSITIVE) { _, _ ->
            sideEffects.incrementAndGet()
            ToolResult.text("tapped")
        })
        registry.register(tool("phone_call", "Call", "d", Scopes.CALLS, Risk.SENSITIVE) { _, _ ->
            sideEffects.incrementAndGet()
            ToolResult.text("calling")
        })
        registry.register(tool("app_open", "Open app", "d", Scopes.APPS, Risk.ACTION) { args, ctx ->
            val pkg = args.reqString("package")
            ctx.appAccess.require(pkg, labels[pkg])
            sideEffects.incrementAndGet()
            ToolResult.text("opened $pkg")
        })
        registry.register(tool("ui_tap", "Tap", "d", Scopes.SCREEN_CONTROL, Risk.ACTION) { _, _ ->
            sideEffects.incrementAndGet()
            ToolResult.text("tapped")
        })
    }

    private fun server() = McpServer(
        registry = registry,
        tokens = tokens,
        policy = PolicyEngine(),
        confirmations = ConfirmationProvider { req ->
            prompts += req
            ConfirmationResult.APPROVED
        },
        audit = audit,
        serverInfo = ServerInfo("phonebridge", "0.1.0"),
        instructions = "How to work.",
        globalSecurity = { GlobalSecurity(neverAllowApps = setOf("com.bank", "dev.phonebridge")) },
        appLabel = { labels[it] },
    )

    private fun issue(grant: GrantDraft): IssuedToken {
        val s = grant.settings()
        return tokens.issue("Claude", s.scopes, s.confirmPolicy, null, origin = "pairing", appPolicy = s.appPolicy, maxRisk = s.maxRisk)
    }

    /** Send messages + use apps, on WhatsApp only. */
    private fun chosen(ask: Boolean = false) = GrantDraft.forNewAgent().withMode(GrantMode.CHOSEN).copy(scopes = emptySet())
        .toggle(Ability.SEND_MESSAGES).toggle(Ability.USE_APPS)
        .withAppMode(AppAccessMode.ALLOWLIST).toggleApp("com.whatsapp")
        .withAsk(ask)

    private suspend fun McpServer.rpc(token: IssuedToken, method: String, params: String): JsonObject {
        val body = """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}"""
        return Json.parseToJsonElement(assertNotNull(handle(TransportRequest("Bearer ${token.plaintext}", body, "relay")).body)).jsonObject
    }

    private suspend fun McpServer.call(token: IssuedToken, name: String, args: String = "{}"): JsonObject =
        rpc(token, "tools/call", """{"name":"$name","arguments":$args}""")

    private fun JsonObject.result() = this["result"]!!.jsonObject
    private fun JsonObject.isError() = result()["isError"]!!.jsonPrimitive.boolean
    private fun JsonObject.text() = result()["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    private fun lastAudit() = audit.entries.value.first()

    @Test
    fun everythingRunsWithoutAnyPrompt() = runTest {
        val token = issue(GrantDraft.forNewAgent())
        val s = server()
        for ((name, args) in listOf(
            "device_status" to "{}", "sms_send" to "{}", "notification_action" to "{}", "phone_call" to "{}",
            "app_open" to """{"package":"com.whatsapp"}""", "ui_tap" to "{}",
        )) {
            val result = s.call(token, name, args)
            assertFalse(result.isError(), "$name: ${result.text()}")
            assertEquals(AuditOutcome.OK, lastAudit().outcome)
        }
        assertEquals(0, prompts.size, "no approval prompt inside the grant")
        assertEquals(5, sideEffects.get())
    }

    @Test
    fun everythingStillHonoursTheNeverAllowList() = runTest {
        val token = issue(GrantDraft.forNewAgent())
        val result = server().call(token, "app_open", """{"package":"com.bank"}""")
        assertTrue(result.isError())
        assertTrue("never-allow list" in result.text(), result.text())
        assertEquals(AuditOutcome.DENIED_APP, lastAudit().outcome)
        assertEquals(0, prompts.size)
    }

    @Test
    fun chosenAppsAndAbilitiesRunWithoutPrompts() = runTest {
        val token = issue(chosen())
        val s = server()
        assertFalse(s.call(token, "sms_send").isError())
        assertFalse(s.call(token, "notification_action").isError())
        assertFalse(s.call(token, "app_open", """{"package":"com.whatsapp"}""").isError())
        assertFalse(s.call(token, "ui_tap").isError())
        assertEquals(0, prompts.size)
        assertEquals(4, sideEffects.get())
    }

    @Test
    fun appOutsideTheGrantIsRefusedNotPrompted() = runTest {
        val token = issue(chosen())
        val result = server().call(token, "app_open", """{"package":"com.google.android.gm"}""")
        assertTrue(result.isError())
        assertTrue(
            result.text().startsWith(
                "Claude isn't allowed to use Gmail (com.google.android.gm). The user can allow it in PhoneBridge > Agents > Claude > Access.",
            ),
            result.text(),
        )
        assertEquals(AuditOutcome.DENIED_APP, lastAudit().outcome)
        assertEquals(0, prompts.size)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun abilityOutsideTheGrantIsRefusedNotPrompted() = runTest {
        val token = issue(chosen())
        val response = server().call(token, "phone_call")
        val error = response["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertEquals(
            "Claude isn't allowed to make or answer calls ('phone_call' needs that). The user can allow it in " +
                "PhoneBridge > Agents > Claude > Access. Do not try to work around this (for example with other tools); " +
                "tell the user what to allow instead.",
            error,
        )
        assertEquals(AuditOutcome.DENIED_SCOPE, lastAudit().outcome)
        assertEquals(0, prompts.size)
        assertEquals(0, sideEffects.get())
    }

    @Test
    fun askSwitchPromptsOnlyForSensitiveActions() = runTest {
        val token = issue(chosen(ask = true))
        val s = server()
        assertFalse(s.call(token, "app_open", """{"package":"com.whatsapp"}""").isError())
        assertFalse(s.call(token, "ui_tap").isError())
        assertEquals(0, prompts.size)
        assertFalse(s.call(token, "sms_send").isError())
        assertFalse(s.call(token, "notification_action").isError())
        assertEquals(listOf("sms_send", "notification_action"), prompts.map { it.toolName })
    }

    @Test
    fun outOfGrantToolsAreNotListed() = runTest {
        val token = issue(chosen())
        val names = server().rpc(token, "tools/list", "{}").result()["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("bridge_capabilities", "sms_send", "notification_action", "app_open", "ui_tap"), names)
    }

    @Test
    fun initializeTellsTheAgentItsGrant() = runTest {
        val text = server().rpc(issue(chosen()), "initialize", "{}").result()["instructions"]!!.jsonPrimitive.content
        assertTrue(text.startsWith("How to work.\n\nYour access on this phone"), text)
        assertTrue("- Apps: only WhatsApp (com.whatsapp). Other apps are hidden from you and refused." in text, text)
        assertTrue("- You may: use apps (open them, run their actions, tap and type); send messages or act on notifications." in text, text)
        assertTrue("- You may not: read messages, call history and notifications;" in text, text)
        assertTrue("make or answer calls" in text, text)
        assertTrue("- Approvals: none. Actions inside this access run without asking the user" in text, text)
        assertTrue("tell the user what to allow in PhoneBridge > Agents > Claude > Access." in text, text)
        assertFalse("com.bank" in text, "never-allowed apps are not named")
    }

    @Test
    fun initializeForEverything() = runTest {
        val text = server().rpc(issue(GrantDraft.forNewAgent()), "initialize", "{}").result()["instructions"]!!.jsonPrimitive.content
        assertTrue("- Apps: every app, except any the user never allows for agents." in text, text)
        assertTrue("- You may: everything PhoneBridge offers" in text, text)
        assertFalse("You may not" in text, text)
        assertTrue("- Approvals: none." in text, text)
    }

    @Test
    fun initializeWithTheAskSwitch() = runTest {
        val text = server().rpc(issue(chosen(ask = true)), "initialize", "{}").result()["instructions"]!!.jsonPrimitive.content
        assertTrue("- Approvals: sending messages, calls, payments and other sensitive actions wait for the user" in text, text)
    }

    @Test
    fun capabilitiesDescribeTheGrant() = runTest {
        val payload = Json.parseToJsonElement(server().call(issue(chosen()), "bridge_capabilities").text()).jsonObject
        val access = payload["token"]!!.jsonObject["access"]!!.jsonObject
        assertEquals(
            listOf("use apps (open them, run their actions, tap and type)", "send messages or act on notifications"),
            access["abilities"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(access["not_allowed"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("make or answer calls"))
        assertEquals("nothing", access["asks_user_before"]!!.jsonPrimitive.content)
        assertEquals("PhoneBridge > Agents > Claude > Access", access["change_in"]!!.jsonPrimitive.content)
    }
}
