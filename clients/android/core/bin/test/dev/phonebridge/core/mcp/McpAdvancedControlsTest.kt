package dev.phonebridge.core.mcp

import dev.phonebridge.core.audit.AuditOutcome
import dev.phonebridge.core.audit.InMemoryAuditLog
import dev.phonebridge.core.auth.ActiveHours
import dev.phonebridge.core.auth.AppAccessMode
import dev.phonebridge.core.auth.AppPolicy
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.auth.InMemoryTokenStore
import dev.phonebridge.core.auth.IssuedToken
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.auth.ToolRule
import dev.phonebridge.core.confirm.ConfirmationProvider
import dev.phonebridge.core.confirm.ConfirmationRequest
import dev.phonebridge.core.confirm.ConfirmationResult
import dev.phonebridge.core.policy.GlobalSecurity
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.tools.CallContext
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.reqString
import dev.phonebridge.core.tools.tool
import dev.phonebridge.core.util.OneTimeCodes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tool rules, active hours, app access and one-time-code settings through the MCP pipeline. */
class McpAdvancedControlsTest {
    private val identity = DeviceIdentity(ByteArray(32) { it.toByte() })
    private val tokens = TokenManager(InMemoryTokenStore(), identity.deviceId)
    private val audit = InMemoryAuditLog()
    private val registry = ToolRegistry()
    private val prompts: MutableList<ConfirmationRequest> = Collections.synchronizedList(mutableListOf())
    private val sideEffects = AtomicInteger()
    private val lastContext = AtomicReference<CallContext?>(null)
    private val zone = ZoneId.of("Asia/Jerusalem")
    private var now = ZonedDateTime.of(2026, 11, 14, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Volatile private var security: () -> GlobalSecurity = { GlobalSecurity(neverAllowApps = setOf("com.bank"), redactOneTimeCodes = true) }

    init {
        registry.register(capabilitiesTool(registry))
        registry.register(
            tool(name = "device_status", title = "Status", description = "d", scope = Scopes.DEVICE, risk = Risk.READ) { _, ctx ->
                lastContext.set(ctx)
                ToolResult.text("fine")
            },
        )
        registry.register(
            tool(name = "sms_send", title = "Send SMS", description = "d", scope = Scopes.MESSAGES_SEND, risk = Risk.SENSITIVE) { _, _ ->
                sideEffects.incrementAndGet()
                ToolResult.text("sent")
            },
        )
        registry.register(
            tool(
                name = "app_open", title = "Open app", description = "d", scope = Scopes.APPS, risk = Risk.ACTION,
            ) { args, ctx ->
                val pkg = args.reqString("package")
                ctx.appAccess.require(pkg, label = if (pkg == "com.whatsapp") "WhatsApp" else null)
                sideEffects.incrementAndGet()
                ToolResult.text("opened $pkg")
            },
        )
        registry.register(
            tool(name = "sms_list", title = "List SMS", description = "d", scope = Scopes.MESSAGES_READ, risk = Risk.READ) { _, ctx ->
                val body = "Your verification code is 482913"
                ToolResult.text(if (ctx.redactOneTimeCodes) OneTimeCodes.redact(body) else body)
            },
        )
    }

    private fun server() = McpServer(
        registry = registry,
        tokens = tokens,
        policy = PolicyEngine(clock = { now }, zone = { zone }),
        confirmations = ConfirmationProvider { req ->
            prompts += req
            ConfirmationResult.APPROVED
        },
        audit = audit,
        serverInfo = ServerInfo("phonebridge", "0.1.0"),
        instructions = null,
        clock = { now },
        globalSecurity = { security() },
    )

    private fun issue(
        rules: Map<String, ToolRule> = emptyMap(),
        hours: ActiveHours? = null,
        appPolicy: AppPolicy = AppPolicy(),
    ): IssuedToken {
        val issued = tokens.issue("Claude", Scopes.ALL, ConfirmPolicy.SENSITIVE, null, appPolicy = appPolicy)
        tokens.update(issued.record.id) { it.copy(toolRules = rules, activeHours = hours) }
        return issued
    }

    private suspend fun McpServer.call(token: IssuedToken, name: String, args: String = "{}"): JsonObject {
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}"""
        val response = handle(TransportRequest("Bearer ${token.plaintext}", body, "relay"))
        return Json.parseToJsonElement(assertNotNull(response.body)).jsonObject["result"]!!.jsonObject
    }

    private fun JsonObject.text() = this["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun JsonObject.isError() = this["isError"]!!.jsonPrimitive.boolean

    private fun lastAudit() = audit.entries.value.first()

    @Test
    fun blockedToolsAreHiddenFromToolsList() = runTest {
        val token = issue(mapOf("sms_send" to ToolRule.BLOCK, "device_status" to ToolRule.ASK))
        val body = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        val response = server().handle(TransportRequest("Bearer ${token.plaintext}", body, "local"))
        val names = Json.parseToJsonElement(response.body!!).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("bridge_capabilities", "device_status", "app_open", "sms_list"), names)
    }

    @Test
    fun blockedToolCallIsRefusedAndAuditedAsPolicy() = runTest {
        val token = issue(mapOf("sms_send" to ToolRule.BLOCK))
        val result = server().call(token, "sms_send")
        assertTrue(result.isError())
        assertTrue(result.text().contains("blocked for this agent by the user"), result.text())
        assertEquals(0, sideEffects.get())
        assertEquals(0, prompts.size)
        assertEquals(AuditOutcome.DENIED_POLICY, lastAudit().outcome)
        assertEquals("sms_send", lastAudit().tool)
    }

    @Test
    fun outsideActiveHoursIsRefusedAndAuditedAsPolicy() = runTest {
        val token = issue(hours = ActiveHours(8 * 60, 10 * 60))
        val result = server().call(token, "device_status")
        assertTrue(result.isError())
        assertTrue(result.text().contains("between 08:00 and 10:00"), result.text())
        assertEquals(AuditOutcome.DENIED_POLICY, lastAudit().outcome)
        now = ZonedDateTime.of(2026, 11, 14, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertFalse(server().call(token, "device_status").isError())
    }

    @Test
    fun askRuleConfirmsEvenReadTools() = runTest {
        val token = issue(mapOf("device_status" to ToolRule.ASK))
        assertFalse(server().call(token, "device_status").isError())
        assertEquals("device_status", prompts.single().toolName)
        assertTrue(lastAudit().detail!!.contains("approved by user"))
    }

    @Test
    fun allowRuleSkipsTheConfirmation() = runTest {
        val token = issue(mapOf("sms_send" to ToolRule.ALLOW))
        assertFalse(server().call(token, "sms_send").isError())
        assertEquals(0, prompts.size)
        assertEquals(1, sideEffects.get())
    }

    @Test
    fun blockedAppIsRefusedAndAuditedAsDeniedApp() = runTest {
        val token = issue(appPolicy = AppPolicy(AppAccessMode.BLOCKLIST, setOf("com.whatsapp")))
        val s = server()
        val result = s.call(token, "app_open", """{"package":"com.whatsapp"}""")
        assertTrue(result.isError())
        assertTrue(result.text().contains("'WhatsApp' (com.whatsapp)"), result.text())
        assertTrue(result.text().contains("Do not try to work around"), result.text())
        assertEquals(AuditOutcome.DENIED_APP, lastAudit().outcome)
        assertEquals("blocked app com.whatsapp", lastAudit().detail)

        // The global never-allow list applies to every token.
        assertTrue(s.call(token, "app_open", """{"package":"com.bank"}""").isError())
        assertEquals(AuditOutcome.DENIED_APP, lastAudit().outcome)

        assertFalse(s.call(token, "app_open", """{"package":"com.maps"}""").isError())
        assertEquals(AuditOutcome.OK, lastAudit().outcome)
        assertEquals(1, sideEffects.get())
    }

    @Test
    fun callContextCarriesTheTokenAndGlobalSettings() = runTest {
        val hours = ActiveHours(0, 1439)
        val token = issue(mapOf("sms_send" to ToolRule.ALLOW), hours, AppPolicy(AppAccessMode.ALLOWLIST, setOf("com.whatsapp")))
        server().call(token, "device_status")
        val ctx = assertNotNull(lastContext.get())
        assertEquals(AppAccessMode.ALLOWLIST, ctx.appAccess.mode)
        assertEquals(setOf("com.whatsapp"), ctx.appAccess.packages)
        assertEquals(setOf("com.bank"), ctx.appAccess.neverAllow)
        assertTrue(ctx.redactOneTimeCodes)
        assertEquals(mapOf("sms_send" to ToolRule.ALLOW), ctx.toolRules)
        assertEquals(hours, ctx.activeHours)

        security = { GlobalSecurity(redactOneTimeCodes = false) }
        server().call(issue(), "device_status")
        val plain = assertNotNull(lastContext.get())
        assertFalse(plain.redactOneTimeCodes)
        assertFalse(plain.appAccess.isRestricted)
    }

    @Test
    fun oneTimeCodesFollowTheGlobalSetting() = runTest {
        val token = issue()
        assertEquals("Your verification code is ${OneTimeCodes.MASK}", server().call(token, "sms_list").text())
        security = { GlobalSecurity(redactOneTimeCodes = false) }
        assertEquals("Your verification code is 482913", server().call(token, "sms_list").text())
    }

    @Test
    fun unreadableSecuritySettingsFailClosed() = runTest {
        security = { throw IllegalStateException("settings not loaded") }
        val result = server().call(issue(), "sms_send")
        assertTrue(result.isError())
        assertTrue(result.text().contains("could not read the phone's security settings"), result.text())
        assertEquals(0, sideEffects.get())
        assertEquals(AuditOutcome.EXCEPTION, lastAudit().outcome)
    }

    @Test
    fun capabilitiesShowTheAgentsLimits() = runTest {
        val token = issue(
            mapOf("sms_send" to ToolRule.BLOCK, "device_status" to ToolRule.ASK),
            ActiveHours(22 * 60, 6 * 60),
            AppPolicy(AppAccessMode.ALLOWLIST, setOf("com.whatsapp", "com.bank")),
        )
        now = ZonedDateTime.of(2026, 11, 14, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val payload = Json.parseToJsonElement(server().call(token, "bridge_capabilities").text()).jsonObject
        val t = payload["token"]!!.jsonObject

        val access = t["app_access"]!!.jsonObject
        assertEquals("ALLOWLIST", access["mode"]!!.jsonPrimitive.content)
        assertEquals(listOf("com.whatsapp"), access["allowed_apps"]!!.jsonArray.map { it.jsonPrimitive.content }, "never-allowed apps are not listed")
        assertFalse(t.toString().contains("com.bank"), "never-allow names are not revealed")

        val rules = t["tool_rules"]!!.jsonObject
        assertEquals("BLOCK", rules["sms_send"]!!.jsonPrimitive.content)
        assertEquals("ASK", rules["device_status"]!!.jsonPrimitive.content)

        val hours = t["active_hours"]!!.jsonObject
        assertTrue(hours["limited"]!!.jsonPrimitive.boolean)
        assertEquals("22:00", hours["start"]!!.jsonPrimitive.content)
        assertEquals("06:00", hours["end"]!!.jsonPrimitive.content)
        assertTrue(t["one_time_codes_hidden"]!!.jsonPrimitive.boolean)

        val tools = payload["tools"]!!.jsonArray.map { it.jsonObject }
        assertNull(tools.firstOrNull { it["name"]!!.jsonPrimitive.content == "sms_send" }, "blocked tools are hidden")
        assertEquals("ASK", tools.first { it["name"]!!.jsonPrimitive.content == "device_status" }["rule"]!!.jsonPrimitive.content)
        assertNull(tools.first { it["name"]!!.jsonPrimitive.content == "app_open" }["rule"])
    }

    @Test
    fun capabilitiesForAnUnrestrictedToken() = runTest {
        security = { GlobalSecurity(redactOneTimeCodes = false) }
        val payload = Json.parseToJsonElement(server().call(issue(), "bridge_capabilities").text()).jsonObject
        val t = payload["token"]!!.jsonObject
        assertEquals("All apps.", t["app_access"]!!.jsonObject["summary"]!!.jsonPrimitive.content)
        assertNull(t["app_access"]!!.jsonObject["note"])
        assertEquals(0, t["tool_rules"]!!.jsonObject.size)
        assertFalse(t["active_hours"]!!.jsonObject["limited"]!!.jsonPrimitive.boolean)
        assertFalse(t["one_time_codes_hidden"]!!.jsonPrimitive.boolean)
        assertEquals(5, payload["tools"]!!.jsonArray.size)
    }

    @Test
    fun readOnlyCapHidesAndRefusesEverythingThatActs() = runTest {
        // The "Read-only" preset: device/apps scopes are granted, but their ACTION tools must not run.
        val readOnly = tokens.issue(
            "Summarizer", Scopes.PRESET_READ_ONLY, ConfirmPolicy.SENSITIVE, null,
            maxRisk = Scopes.PRESET_MAX_RISK.getValue("Read-only"),
        )
        // A tool rule cannot lift the cap.
        tokens.update(readOnly.record.id) { it.copy(toolRules = mapOf("app_open" to ToolRule.ALLOW)) }
        val s = server()
        val list = s.handle(TransportRequest("Bearer ${readOnly.plaintext}", """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", "relay"))
        val names = Json.parseToJsonElement(list.body!!).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("bridge_capabilities", "device_status", "sms_list"), names)

        val refused = s.call(readOnly, "app_open", """{"package":"com.maps"}""")
        assertTrue(refused.isError())
        assertTrue(refused.text().contains("limited it to read-only tools"), refused.text())
        assertEquals(AuditOutcome.DENIED_POLICY, lastAudit().outcome)
        assertEquals(0, sideEffects.get())
        assertEquals(0, prompts.size)
        assertFalse(s.call(readOnly, "device_status").isError())

        val payload = Json.parseToJsonElement(s.call(readOnly, "bridge_capabilities").text()).jsonObject
        assertEquals("READ", payload["token"]!!.jsonObject["max_risk"]!!.jsonPrimitive.content)
        assertEquals(0, payload["token"]!!.jsonObject["tool_rules"]!!.jsonObject.size, "rules of hidden tools are not shown")
        assertEquals(
            listOf("bridge_capabilities", "device_status", "sms_list"),
            payload["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
        )

        // Capped at ACTION: actions run, sending does not.
        val noSending = tokens.issue("Helper", Scopes.ALL, ConfirmPolicy.SENSITIVE, null, maxRisk = Risk.ACTION)
        assertFalse(s.call(noSending, "app_open", """{"package":"com.maps"}""").isError())
        assertTrue(s.call(noSending, "sms_send").isError())
        assertEquals(1, sideEffects.get())

        val uncapped = Json.parseToJsonElement(s.call(issue(), "bridge_capabilities").text()).jsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, uncapped["token"]!!.jsonObject["max_risk"])
    }

    @Test
    fun blocklistSummaryCountsWithoutNaming() {
        val access = dev.phonebridge.core.tools.AppAccess(AppAccessMode.BLOCKLIST, setOf("com.secret.app", "com.other"), setOf("com.bank"))
        val summary = PolicySummary.appAccess(access)
        assertEquals(2, summary["blocked_apps"]!!.jsonPrimitive.int)
        assertFalse(summary.toString().contains("com.secret.app"))
        assertTrue(summary["summary"]!!.jsonPrimitive.content.contains("never allowed for any agent"))
    }
}
