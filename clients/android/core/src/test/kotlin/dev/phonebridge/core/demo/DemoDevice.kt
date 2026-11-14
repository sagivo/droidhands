package dev.phonebridge.core.demo

import dev.phonebridge.core.audit.AuditEntry
import dev.phonebridge.core.audit.InMemoryAuditLog
import dev.phonebridge.core.auth.ConfirmPolicy
import dev.phonebridge.core.auth.DeviceIdentity
import dev.phonebridge.core.auth.InMemoryTokenStore
import dev.phonebridge.core.auth.TokenManager
import dev.phonebridge.core.confirm.ConfirmationProvider
import dev.phonebridge.core.confirm.ConfirmationResult
import dev.phonebridge.core.mcp.McpServer
import dev.phonebridge.core.mcp.ServerInfo
import dev.phonebridge.core.mcp.capabilitiesTool
import dev.phonebridge.core.oauth.LocalOAuth
import dev.phonebridge.core.policy.PolicyEngine
import dev.phonebridge.core.tools.Risk
import dev.phonebridge.core.tools.Scopes
import dev.phonebridge.core.tools.Tool
import dev.phonebridge.core.tools.ToolRegistry
import dev.phonebridge.core.tools.ToolResult
import dev.phonebridge.core.tools.objectSchema
import dev.phonebridge.core.tools.optString
import dev.phonebridge.core.tools.reqString
import dev.phonebridge.core.tools.tool
import dev.phonebridge.core.transport.DeviceHello
import dev.phonebridge.core.transport.LocalHttpServer
import dev.phonebridge.core.transport.PairingDecision
import dev.phonebridge.core.transport.PairingHandler
import dev.phonebridge.core.transport.PairingRequest
import dev.phonebridge.core.transport.RelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put

/**
 * A headless "phone" for cross-language end-to-end tests (e.g. against the TypeScript relay).
 * Run with `./gradlew :core:runDemoDevice`.
 *
 * Environment:
 * * `DEMO_POLICY`: NEVER | SENSITIVE (default) | ALL_ACTIONS, for the issued token.
 * * `DEMO_CONFIRM`: `approve` | `deny` | anything else = never answer (tests confirmation timeouts).
 * * `DEMO_CONFIRM_TIMEOUT_MS`: confirmation timeout (default 60000).
 * * `DEMO_LOCAL_PORT`: start the local HTTP server on 127.0.0.1:<port> (0 = any free port).
 * * `DEMO_RELAY_URL`, `DEMO_ACCESS_KEY`: connect to a relay.
 * * `DEMO_DEVICE_SECRET`: base64url device secret for a stable device id (default: random).
 * * `DEMO_PAIR`: how to answer one-tap pairing requests from the relay (docs/PROTOCOL.md §5) and the
 *   local server (§4.1): `approve` (issues a real token named after the client, origin "pairing", all
 *   scopes, the `DEMO_POLICY` confirmation policy), `deny`, or `ignore` (never answers, so the request
 *   expires). Unset: no pairing handler, so the device answers `busy` and the local server has no
 *   OAuth endpoints.
 *
 * Stdout: exactly one `DEMO_TOKEN=<token>` and one `DEMO_DEVICE_ID=<id>` line, then
 * `DEMO_LOCAL_PORT=<port>` if local mode is on, then one `RELAY_STATUS=<status>` line per relay
 * status change, and per pairing `PAIRED tokenId=<id> client=<name>` (approved),
 * `PAIR_COMPLETE id=<pairing id> tokenId=<id>` and `PAIR_ABANDONED id=<pairing id> tokenId=<id|none>`
 * (the token is then deleted). Runs until killed.
 */
fun main() {
    val env = System.getenv()
    val identity = env["DEMO_DEVICE_SECRET"]?.takeIf { it.isNotBlank() }?.let(DeviceIdentity::fromEncoded) ?: DeviceIdentity.generate()
    val policy = env["DEMO_POLICY"]?.takeIf { it.isNotBlank() }?.let { ConfirmPolicy.valueOf(it.trim().uppercase()) } ?: ConfirmPolicy.SENSITIVE
    val confirmMode = env["DEMO_CONFIRM"]?.trim()?.lowercase()
    val confirmTimeoutMs = env["DEMO_CONFIRM_TIMEOUT_MS"]?.toLongOrNull() ?: 60_000L

    val tokens = TokenManager(InMemoryTokenStore(), identity.deviceId)
    val issued = tokens.issue("Demo agent", Scopes.ALL, policy, ttlMillis = null)

    val registry = ToolRegistry()
    registry.register(capabilitiesTool(registry) { mapOf("platform" to "demo", "locked" to false) })
    demoTools().forEach(registry::register)

    val confirmations = ConfirmationProvider { request ->
        System.err.println("CONFIRM? ${request.toolName}: ${request.summary}")
        when (confirmMode) {
            "approve" -> ConfirmationResult.APPROVED
            "deny" -> ConfirmationResult.DENIED
            else -> awaitCancellation()
        }
    }

    val audit = object : InMemoryAuditLog() {
        override fun record(entry: AuditEntry) {
            super.record(entry)
            System.err.println("AUDIT ${entry.outcome} ${entry.method} ${entry.tool ?: ""} ${entry.detail ?: ""}".trimEnd())
        }
    }

    val server = McpServer(
        registry = registry,
        tokens = tokens,
        policy = PolicyEngine(),
        confirmations = confirmations,
        audit = audit,
        serverInfo = ServerInfo("phonebridge-demo", "0.1.0", "PhoneBridge demo device"),
        instructions = "Demo device for end-to-end tests. Tools: echo, demo_send, demo_image.",
        confirmationTimeoutMs = confirmTimeoutMs,
    )

    println("DEMO_TOKEN=${issued.plaintext}")
    println("DEMO_DEVICE_ID=${identity.deviceId}")
    System.out.flush()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val pairing = demoPairingHandler(env["DEMO_PAIR"]?.trim()?.lowercase(), tokens, policy)

    env["DEMO_LOCAL_PORT"]?.trim()?.toIntOrNull()?.let { port ->
        val oauth = pairing?.let {
            LocalOAuth(it, identity.deriveKey(LocalOAuth.CLIENT_KEY_PURPOSE), deviceName = { "Demo device" }, log = System.err::println)
        }
        val local = LocalHttpServer(port = port, handler = server::handle, oauth = oauth)
        local.start()
        Runtime.getRuntime().addShutdownHook(Thread { local.stop() })
        println("DEMO_LOCAL_PORT=${local.boundPort}")
        System.out.flush()
    }

    env["DEMO_RELAY_URL"]?.takeIf { it.isNotBlank() }?.let { url ->
        val relay = RelayClient(
            relayBaseUrl = url,
            identity = identity,
            hello = DeviceHello("demo", "0.1.0", "Demo device", accessKey = env["DEMO_ACCESS_KEY"]),
            handler = server::handle,
            scope = scope,
            pairingHandler = pairing,
        )
        scope.launch {
            relay.status.collect {
                println("RELAY_STATUS=$it")
                System.out.flush()
            }
        }
        relay.start()
        Runtime.getRuntime().addShutdownHook(Thread { relay.stop() })
    }

    runBlocking { awaitCancellation() }
}

/**
 * Pairing handler for `DEMO_PAIR` = `approve` | `deny` | `ignore`; null (answer `busy`) otherwise.
 * Prints `PAIRED` / `PAIR_COMPLETE` / `PAIR_ABANDONED` lines on stdout (never the token itself).
 */
internal fun demoPairingHandler(mode: String?, tokens: TokenManager, policy: ConfirmPolicy): PairingHandler? {
    if (mode !in setOf("approve", "deny", "ignore")) return null
    return object : PairingHandler {
        override suspend fun onPairRequest(request: PairingRequest): PairingDecision {
            System.err.println("PAIR_REQUEST id=${request.id} client=${request.clientName} host=${request.redirectHost} code=${request.displayCode}")
            return when (mode) {
                "approve" -> {
                    val issued = tokens.issue(
                        request.clientName, Scopes.ALL, policy, ttlMillis = null, origin = "pairing", clientHost = request.redirectHost,
                    )
                    println("PAIRED tokenId=${issued.record.id} client=${issued.record.name}")
                    System.out.flush()
                    PairingDecision.Approved(issued.plaintext, issued.record.id)
                }
                "deny" -> PairingDecision.Denied("denied")
                else -> awaitCancellation()
            }
        }

        override fun onPairFinished(id: String, tokenId: String?, completed: Boolean) {
            if (completed) {
                println("PAIR_COMPLETE id=$id tokenId=${tokenId ?: "none"}")
            } else {
                tokenId?.let(tokens::delete)
                println("PAIR_ABANDONED id=$id tokenId=${tokenId ?: "none"}")
            }
            System.out.flush()
        }
    }
}

/** `echo`, `demo_send` (internal confirmation) and `demo_image`. */
internal fun demoTools(): List<Tool> = listOf(
    tool(
        name = "echo",
        title = "Echo",
        description = "Returns the given text unchanged. Use it to test connectivity.",
        scope = Scopes.DEVICE,
        risk = Risk.READ,
        schema = objectSchema { string("text", "Text to echo back", required = true) },
    ) { args, _ -> ToolResult.text(args.optString("text") ?: "") },
    tool(
        name = "demo_send",
        title = "Send demo message",
        description = "Pretends to send a message. Asks the user to confirm on the phone first (sensitive).",
        scope = Scopes.MESSAGES_SEND,
        risk = Risk.SENSITIVE,
        confirmsInternally = true,
        schema = objectSchema {
            string("to", "Recipient", required = true)
            string("text", "Message text", required = true)
        },
    ) { args, ctx ->
        val to = args.reqString("to")
        val text = args.reqString("text")
        ctx.requireConfirmation("Send '$text' to $to")
        ToolResult.json {
            put("sent", true)
            put("to", to)
        }
    },
    tool(
        name = "demo_image",
        title = "Demo image",
        description = "Returns a 1x1 PNG image.",
        scope = Scopes.DEVICE,
        risk = Risk.READ,
    ) { _, _ -> ToolResult.image(ONE_PIXEL_PNG, "image/png") },
)

/** A valid 1x1 transparent PNG, standard base64. */
internal const val ONE_PIXEL_PNG =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="
