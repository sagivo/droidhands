package com.droidhands.core.oauth

import com.droidhands.core.transport.PairingDecision
import com.droidhands.core.transport.PairingHandler
import com.droidhands.core.transport.PairingRequest
import com.droidhands.core.util.Encoding
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * One-tap pairing on the phone's own HTTP server (docs/PROTOCOL.md §4.1): the OAuth 2.1
 * authorization server the relay implements (§5.2-5.4), answered by the phone itself, so an agent
 * that reaches the phone directly (adb forward, Tailscale, Wi-Fi) pairs by URL plus a tap on Allow.
 *
 * Endpoints (served by [com.droidhands.core.transport.LocalHttpServer] for every path [isOAuthPath]):
 * * `GET /.well-known/oauth-protected-resource[/mcp]`: RFC 9728 metadata of `{base}/mcp`.
 * * `GET /.well-known/oauth-authorization-server` (alias `openid-configuration`): RFC 8414.
 * * `POST /oauth/register`: RFC 7591, stateless client ids ([OAuthClients]).
 * * `GET /oauth/authorize`: validates the request, prompts the phone through the same
 *   [PairingHandler] as relay pairings and renders the waiting page with the 4-character code.
 * * `GET /oauth/pair/<id>?t=<pageToken>[&view=html]`: the waiting page's poll.
 * * `POST /oauth/token`: code exchange; PKCE S256 required.
 *
 * `{base}` is `http://<Host>` when [LocalHosts.issuer] accepts the request's `Host` for the address
 * it arrived on; any other `Host` gets 421 on every endpoint (DNS rebinding, Host spoofing).
 *
 * Rules shared with the relay: redirect URIs ([RedirectUris]), `resource` must name this server's
 * `/mcp` (or be absent: there is only one resource), prompt TTL [Config.promptTtlMs], single-use
 * codes valid [Config.codeTtlMs] and stored hashed, caps on pending pairings overall and per client
 * address, a per-address request budget and authentication-failure limit, constant-time comparisons.
 * An approved token that no agent receives (code expired, failed exchange, response not delivered,
 * [shutdown]) is reported to [PairingHandler.onPairFinished] with completed = false, so the platform
 * deletes it; a delivered one with completed = true. Tokens, codes and page tokens are never logged.
 *
 * Thread-safe. Prompts run on [context]; [handle] never blocks on them.
 */
class LocalOAuth(
    private val pairing: PairingHandler,
    /** HMAC key of the stateless client ids: secret, stable across restarts (≥ 16 bytes). */
    clientKey: ByteArray,
    /** Shown on the waiting page ("Open DroidHands on <name>"); null for "your phone". */
    private val deviceName: () -> String? = { null },
    private val config: Config = Config(),
    private val clock: () -> Long = System::currentTimeMillis,
    context: CoroutineContext = Dispatchers.Default,
    private val random: SecureRandom = SecureRandom(),
    private val log: (String) -> Unit = {},
) {
    /** Limits and lifetimes; the defaults match the relay's (docs/PROTOCOL.md §5.3). */
    data class Config(
        /** How long the phone has to answer. */
        val promptTtlMs: Long = 180_000,
        /** Authorization code lifetime. */
        val codeTtlMs: Long = 120_000,
        /** How long a decided pairing stays pollable. */
        val retainMs: Long = 600_000,
        val maxPending: Int = 3,
        val maxPerHour: Int = 20,
        val maxPendingPerAddress: Int = 2,
        val maxPerAddressPerHour: Int = 10,
        /** Pending plus decided-but-still-pollable pairings. */
        val maxRecords: Int = 100,
        /** OAuth requests per client address per minute (the waiting page polls ~40 times a minute). */
        val requestsPerMinute: Int = 120,
        /** Failed client/code checks per client address per 10 minutes before it gets 429. */
        val maxAuthFailures: Int = 20,
    )

    /** One HTTP request to an OAuth path. [query] is raw, without `?`. */
    class Request(
        val method: String,
        val path: String,
        val query: String = "",
        /** The `Host` header. */
        val host: String?,
        /** The phone's address the connection arrived on. */
        val localAddress: InetAddress?,
        /** The client's address. */
        val remoteAddress: String?,
        val contentType: String? = null,
        val authorization: String? = null,
        val userAgent: String? = null,
        val body: String = "",
    )

    /**
     * The answer; header names are lowercase. [onSent] (if set) must be called once the response
     * was written (true) or could not be (false): it reports whether the agent received its token.
     */
    class Response(
        val status: Int,
        val headers: Map<String, String>,
        val body: ByteArray?,
        val onSent: ((Boolean) -> Unit)? = null,
    ) {
        val text: String get() = body?.toString(Charsets.UTF_8) ?: ""
    }

    private enum class Status(val wire: String) { PENDING("pending"), APPROVED("approved"), DENIED("denied"), EXPIRED("expired") }

    /** What the browser asked for in `/oauth/authorize`, after validation. */
    private class Authorization(
        val clientId: String,
        val clientName: String,
        val redirectUri: String,
        val redirectUriProvided: Boolean,
        val codeChallenge: String,
        val state: String?,
        val issuer: String,
    )

    private class Record(
        val id: String,
        val request: Authorization,
        val address: String,
        val pageToken: String,
        val displayCode: String,
        val redirectHost: String,
        val expiresAt: Long,
    ) {
        var status = Status.PENDING
        var redirect: String? = null
        var decidedAt = 0L
        var job: Job? = null
    }

    /** An authorization code's binding, handed to the token endpoint exactly once. */
    private class Grant(
        val pairingId: String,
        val request: Authorization,
        val tokenId: String,
        /** Never log it; cleared once handed out or abandoned. */
        var accessToken: String,
        val expiresAt: Long,
    ) {
        var expiry: Job? = null
    }

    private class Budget(var windowStart: Long, var count: Int) {
        val failures = ArrayDeque<Long>()
    }

    private val clients = OAuthClients(clientKey)
    private val scope = CoroutineScope(SupervisorJob() + context + CoroutineName("droidhands-oauth"))
    private val lock = Any()
    private val records = LinkedHashMap<String, Record>()

    /** Keyed by SHA-256(code), so raw codes are never stored. */
    private val grants = HashMap<String, Grant>()

    /** Creation times within the last hour, per [ALL] / client address. */
    private val history = HashMap<String, ArrayDeque<Long>>()
    private val budgets = LinkedHashMap<String, Budget>()

    /** Pairings not yet decided (tests and diagnostics). */
    val pendingCount: Int get() = synchronized(lock) { records.values.count { it.status == Status.PENDING } }

    /** Codes waiting for their exchange (tests and diagnostics). */
    val codeCount: Int get() = synchronized(lock) { grants.size }

    /**
     * `WWW-Authenticate` for a 401 from `/mcp` on a request with [host] that arrived on
     * [localAddress], pointing MCP clients at the metadata (§4.1); null when the Host is not accepted.
     */
    fun challenge(host: String?, localAddress: InetAddress?): String? {
        val base = LocalHosts.issuer(host, localAddress) ?: return null
        return "Bearer realm=\"droidhands\", resource_metadata=\"$base$PRM_PATH$MCP_PATH\""
    }

    fun handle(request: Request): Response {
        val path = request.path
        val address = request.remoteAddress ?: "unknown"
        val html = path == AUTHORIZE_PATH || (path.startsWith(PAIR_PREFIX) && formOrNull(request.query)?.get("view")?.firstOrNull() == "html")
        if (!admit(address)) {
            val headers = mapOf("retry-after" to "60")
            return if (html) page(429, RATE_LIMITED.first, RATE_LIMITED.second, headers) else json(429, error("invalid_request", "too many requests; retry later"), headers)
        }
        val issuer = LocalHosts.issuer(request.host, request.localAddress)
            ?: return if (html) {
                page(421, "This address does not belong to your phone.", "Use the connection URL shown in the DroidHands app.")
            } else {
                json(421, error("invalid_request", "the Host header does not name this phone; use the connection URL shown in the DroidHands app"))
            }
        sweep()
        return when {
            path == PRM_PATH || path == PRM_PATH + MCP_PATH ->
                get(request) { json(200, resourceMetadata(issuer)) }
            path in AS_METADATA_PATHS ->
                get(request) { json(200, serverMetadata(issuer)) }
            path == REGISTER_PATH -> post(request) { register(request) }
            // GET only: a HEAD must not prompt the phone.
            path == AUTHORIZE_PATH -> if (request.method == "GET") authorize(request, issuer, address) else methodNotAllowed("GET")
            path.startsWith(PAIR_PREFIX) -> get(request) { pairStatus(request, path.removePrefix(PAIR_PREFIX)) }
            path == TOKEN_PATH -> post(request) { token(request, address) }
            else -> json(404, error("invalid_request", "not found"))
        }
    }

    /**
     * Cancels open prompts and abandons codes not yet exchanged (the server is stopping: nobody can
     * finish them). The instance stays usable.
     */
    fun shutdown() {
        val jobs = ArrayList<Job>()
        val abandoned = ArrayList<Grant>()
        synchronized(lock) {
            records.values.mapNotNullTo(jobs) { it.job }
            abandoned += grants.values
            grants.clear()
            records.clear()
            history.clear()
        }
        jobs.forEach { it.cancel() }
        abandoned.forEach { abandon(it) }
    }

    // --- Discovery and registration ---

    private fun resourceMetadata(issuer: String) = buildJsonObject {
        put("resource", issuer + MCP_PATH)
        put("authorization_servers", buildJsonArray { add(JsonPrimitive(issuer)) })
        put("bearer_methods_supported", stringArray("header"))
        put("resource_name", "DroidHands")
        put("scopes_supported", stringArray(SCOPE))
    }

    private fun serverMetadata(issuer: String) = buildJsonObject {
        put("issuer", issuer)
        put("authorization_endpoint", issuer + AUTHORIZE_PATH)
        put("token_endpoint", issuer + TOKEN_PATH)
        put("registration_endpoint", issuer + REGISTER_PATH)
        put("response_types_supported", stringArray("code"))
        put("response_modes_supported", stringArray("query"))
        put("grant_types_supported", stringArray("authorization_code"))
        put("code_challenge_methods_supported", stringArray("S256"))
        put("token_endpoint_auth_methods_supported", stringArray("none"))
        put("scopes_supported", stringArray(SCOPE))
        put("authorization_response_iss_parameter_supported", true)
    }

    private fun register(request: Request): Response {
        val metadata = try {
            Json.parseToJsonElement(request.body)
        } catch (e: IllegalArgumentException) {
            return json(400, error(OAuthClients.INVALID_METADATA, "the request body must be JSON"))
        }
        return when (val result = clients.register(metadata, clock())) {
            is OAuthClients.Registration.Ok -> {
                log("OAuth client registered: ${result.client.clientName}")
                json(201, result.body())
            }
            is OAuthClients.Registration.Failed -> json(400, error(result.error, result.description))
        }
    }

    // --- Authorization ---

    private fun authorize(request: Request, issuer: String, address: String): Response {
        val q = formOrNull(request.query) ?: return page(400, "The request is malformed.", RECONNECT_HINT)
        AUTHORIZE_SINGLE.firstOrNull { (q[it]?.size ?: 0) > 1 }?.let {
            return page(400, "The request repeats the \"$it\" parameter.", RECONNECT_HINT)
        }
        val clientId = q.one("client_id") ?: ""
        val client = clients.decode(clientId)
            ?: return page(
                400,
                "Your AI app's registration is not valid for this phone (it may have been made before DroidHands was reset).",
                RECONNECT_HINT,
            )
        val requestedRedirect = q.one("redirect_uri")
        val redirectUri = when {
            requestedRedirect == null -> client.redirectUris.singleOrNull()
            RedirectUris.matches(client.redirectUris, requestedRedirect) -> requestedRedirect
            else -> null
        } ?: return page(400, "Your AI app asked to return to an address it did not register.", RECONNECT_HINT)
        val state = q.one("state")
        if (state != null && state.length > MAX_STATE_LENGTH) return page(400, "The request's \"state\" parameter is too long.", RECONNECT_HINT)
        if (q.one("response_type") != "code") {
            return page(400, "Your AI app asked for an unsupported kind of authorization (only response_type=code is supported).", UPDATE_HINT)
        }
        val challenge = q.one("code_challenge")
        if (q.one("code_challenge_method") != "S256" || challenge == null || !CODE_CHALLENGE.matches(challenge)) {
            return page(400, "Your AI app did not use PKCE with S256, which DroidHands requires to keep your phone safe.", UPDATE_HINT)
        }
        val resource = q.one("resource")
        if (resource != null && !resourceMatches(resource, issuer)) {
            return page(
                400,
                "The connection URL your AI app uses does not belong to this phone ($issuer).",
                "Copy your connection URL again from the DroidHands app; it looks like $issuer$MCP_PATH.",
            )
        }
        val authorization = Authorization(clientId, client.clientName, redirectUri, requestedRedirect != null, challenge, state, issuer)
        val record = when (val created = create(authorization, address, cleanUserAgent(request.userAgent))) {
            is Created.Ok -> created.record
            is Created.Refused -> return page(created.status, created.problem, created.hint)
        }
        return waitingPage(record)
    }

    private sealed interface Created {
        class Ok(val record: Record) : Created
        class Refused(val status: Int, val problem: String, val hint: String) : Created
    }

    /**
     * Starts a pairing and prompts the phone. A repeated request for the same flow (same client,
     * redirect URI, PKCE challenge and state, e.g. a page reload) returns the existing pairing while
     * it is pending or approved, instead of prompting again.
     */
    private fun create(authorization: Authorization, address: String, userAgent: String?): Created {
        val record: Record
        val job: Job
        synchronized(lock) {
            val now = clock()
            records.values.firstOrNull { sameFlow(it, authorization) }?.let { return Created.Ok(it) }
            val pending = records.values.filter { it.status == Status.PENDING }
            if (pending.size >= config.maxPending) {
                return Created.Refused(429, "Your phone already has several connection requests waiting.", "Answer them on your phone, or wait 3 minutes for them to expire, then reload this page.")
            }
            if (recent(ALL, now) >= config.maxPerHour) {
                return Created.Refused(
                    429,
                    "Your phone received too many connection requests in the last hour.",
                    "Wait a while, then try again. If you didn't start them, someone else can reach your phone; deny their requests on your phone.",
                )
            }
            if (pending.count { it.address == address } >= config.maxPendingPerAddress || recent(address, now) >= config.maxPerAddressPerHour) {
                return Created.Refused(429, "Too many connection requests from your computer.", "Wait a while, then try again.")
            }
            if (records.size >= config.maxRecords) return Created.Refused(503, "Your phone is busy right now.", "Try again in a few minutes.")

            record = Record(
                id = "p_" + randomToken(16),
                request = authorization,
                address = address,
                pageToken = randomToken(16),
                displayCode = displayCode(),
                redirectHost = RedirectUris.displayHost(authorization.redirectUri),
                expiresAt = now + config.promptTtlMs,
            )
            val prompt = PairingRequest(
                id = record.id,
                clientName = authorization.clientName,
                redirectHost = record.redirectHost,
                displayCode = record.displayCode,
                remoteAddress = address,
                userAgent = userAgent,
                expiresAt = record.expiresAt,
            )
            job = scope.launch(start = CoroutineStart.LAZY) { prompt(prompt) }
            record.job = job
            records[record.id] = record
            remember(ALL, now)
            remember(address, now)
        }
        log("Local pairing requested from $address")
        job.start()
        return Created.Ok(record)
    }

    private suspend fun prompt(request: PairingRequest) {
        val decision = try {
            withTimeoutOrNull(config.promptTtlMs) { pairing.onPairRequest(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PairingDecision.Denied(REASON_BUSY)
        }
        settle(request.id, decision)
    }

    /** The phone's [decision] (null: no answer in time) for pairing [id]. */
    private fun settle(id: String, decision: PairingDecision?) {
        var orphan: String? = null
        synchronized(lock) {
            val record = records[id]
            val now = clock()
            if (record != null) expireIfDue(record, now)
            if (record == null || record.status != Status.PENDING) {
                // Nobody can pick this token up any more.
                if (decision is PairingDecision.Approved) orphan = decision.tokenId
                return@synchronized
            }
            val request = record.request
            when (decision) {
                null -> expire(record, now)
                is PairingDecision.Denied -> {
                    val description = when (decision.reason) {
                        "timeout" -> "Nobody answered the request on the phone in time"
                        REASON_BUSY -> "The phone was busy with another request; try again"
                        else -> "The request was denied on the phone"
                    }
                    decide(record, Status.DENIED, denial(request, description), now)
                }
                is PairingDecision.Approved -> {
                    val code = randomToken(32)
                    val grant = Grant(record.id, request, decision.tokenId, decision.accessToken, now + config.codeTtlMs)
                    val key = hash(code)
                    grants[key] = grant
                    grant.expiry = scope.launch {
                        delay(config.codeTtlMs)
                        expireCode(key, grant)
                    }
                    decide(
                        record,
                        Status.APPROVED,
                        RedirectUris.withParams(request.redirectUri, listOf("code" to code, "state" to request.state, "iss" to request.issuer)),
                        now,
                    )
                }
            }
        }
        orphan?.let { finished(id, it, completed = false) }
    }

    private fun waitingPage(record: Record): Response {
        val pollUrl = "$PAIR_PREFIX${record.id}?t=${record.pageToken}"
        val remaining = maxOf(0L, (record.expiresAt - clock() + 999) / 1000)
        val page = OAuthPages.waiting(
            clientName = record.request.clientName,
            displayCode = record.displayCode,
            redirectHost = record.redirectHost,
            deviceName = deviceName(),
            pollUrl = pollUrl,
            pageUrl = "$pollUrl&view=html",
            remainingSeconds = remaining,
            random = random,
        )
        return Response(200, page.headers, page.html.toByteArray(Charsets.UTF_8))
    }

    /** `GET /oauth/pair/<id>?t=<pageToken>[&view=html]`. */
    private fun pairStatus(request: Request, id: String): Response {
        val q = formOrNull(request.query) ?: emptyMap()
        val wantsHtml = q.one("view") == "html"
        val token = q.one("t") ?: ""
        val record = if (PAIRING_ID.matches(id)) {
            synchronized(lock) {
                records[id]?.takeIf { Encoding.constantTimeEquals(it.pageToken, token) }?.also { expireIfDue(it, clock()) }
            }
        } else {
            null
        }
        if (record == null) {
            return if (wantsHtml) page(404, "This connection request is no longer valid.", "Start again from your AI app.") else json(404, buildJsonObject { put("error", "not_found") })
        }
        val (status, redirect) = synchronized(lock) { record.status to record.redirect }
        if (!wantsHtml) {
            return json(
                200,
                buildJsonObject {
                    put("status", status.wire)
                    redirect?.let { put("redirect", it) }
                    put("expiresAt", record.expiresAt)
                },
            )
        }
        if (redirect != null) return Response(303, mapOf("location" to redirect, "referrer-policy" to "no-referrer"), null)
        return waitingPage(record)
    }

    // --- Token ---

    private fun token(request: Request, address: String): Response {
        val contentType = request.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (contentType != "application/x-www-form-urlencoded") {
            return tokenError(400, "invalid_request", "the body must be application/x-www-form-urlencoded")
        }
        val params = formOrNull(request.body) ?: return tokenError(400, "invalid_request", "the body is not valid form data")
        TOKEN_SINGLE.firstOrNull { (params[it]?.size ?: 0) > 1 }?.let { return tokenError(400, "invalid_request", "\"$it\" must not repeat") }
        val grantType = params.one("grant_type") ?: return tokenError(400, "invalid_request", "grant_type is required")
        if (grantType != "authorization_code") {
            return tokenError(400, "unsupported_grant_type", "only authorization_code is supported; to reconnect, authorize again")
        }
        val clientId = when (val id = requestClientId(params.one("client_id"), request.authorization)) {
            ClientId.Conflict -> return tokenError(400, "invalid_request", "client_id in the body and in Basic auth differ")
            is ClientId.Value -> id.value
        }
        val code = params.one("code")
        val verifier = params.one("code_verifier")
        if (clientId == null || code == null || verifier == null) {
            return tokenError(400, "invalid_request", "client_id, code and code_verifier are required")
        }
        if (clients.decode(clientId) == null) return authFailure(address, "invalid_client", "unknown client_id; register again")
        val grant = takeCode(code) ?: return authFailure(address, "invalid_grant", INVALID_CODE)

        // From here on the code is consumed: any mismatch abandons the pairing so the phone deletes the token.
        val problem = grantProblem(grant, clientId, params, verifier)
        if (problem != null) {
            abandon(grant)
            return authFailure(address, problem.first, problem.second)
        }
        val body = buildJsonObject {
            put("access_token", grant.accessToken)
            put("token_type", "Bearer")
            put("scope", SCOPE)
        }.toString().toByteArray(Charsets.UTF_8)
        grant.accessToken = ""
        log("Local pairing token issued to $address")
        // Completed only once the response has been handed to the network; abandoned otherwise.
        return Response(200, TOKEN_HEADERS, body) { sent -> finished(grant.pairingId, grant.tokenId, completed = sent) }
    }

    /** The binding checks of §5.4: (error, description), or null when the request matches the code. */
    private fun grantProblem(grant: Grant, clientId: String, params: Map<String, List<String>>, verifier: String): Pair<String, String>? {
        val request = grant.request
        if (!Encoding.constantTimeEquals(request.clientId, clientId)) return "invalid_grant" to "the code was issued to another client"
        val redirectUri = params.one("redirect_uri")
        val redirectOk = if (request.redirectUriProvided) redirectUri == request.redirectUri else redirectUri == null || redirectUri == request.redirectUri
        if (!redirectOk) return "invalid_grant" to "redirect_uri does not match the authorization request"
        if (!verifyPkce(verifier, request.codeChallenge)) return "invalid_grant" to "code_verifier does not match the code_challenge"
        val resource = params.one("resource")
        if (resource != null && !resourceMatches(resource, request.issuer)) {
            return "invalid_target" to "resource does not match the phone that approved this request"
        }
        return null
    }

    /** Removes and returns the grant for [code], or null when it is unknown, used or expired. */
    private fun takeCode(code: String): Grant? {
        if (code.length > MAX_CODE_LENGTH) return null
        val expired: Grant
        synchronized(lock) {
            val grant = grants.remove(hash(code)) ?: return null
            grant.expiry?.cancel()
            if (clock() < grant.expiresAt) return grant
            expired = grant
        }
        abandon(expired)
        return null
    }

    private fun expireCode(key: String, grant: Grant) {
        val removed = synchronized(lock) { grants.remove(key, grant) }
        if (removed) abandon(grant)
    }

    private fun abandon(grant: Grant) {
        grant.accessToken = ""
        finished(grant.pairingId, grant.tokenId, completed = false)
    }

    private fun finished(id: String, tokenId: String?, completed: Boolean) {
        try {
            pairing.onPairFinished(id, tokenId, completed)
        } catch (e: Exception) {
            // The platform's bookkeeping failed; nothing useful to tell the agent.
        }
    }

    private sealed interface ClientId {
        data object Conflict : ClientId
        class Value(val value: String?) : ClientId
    }

    /** client_id from the body, or from HTTP Basic credentials (some clients send public ids that way). */
    private fun requestClientId(fromBody: String?, authorization: String?): ClientId {
        val basic = BASIC.find(authorization?.trim() ?: "")?.groupValues?.get(1) ?: return ClientId.Value(fromBody)
        val decoded = try {
            Base64.getDecoder().decode(basic).toString(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return ClientId.Value(fromBody)
        }
        val user = decoded.substringBefore(':')
        val fromBasic = try {
            URLDecoder.decode(user, Charsets.UTF_8.name())
        } catch (e: IllegalArgumentException) {
            user
        }
        if (fromBody != null && fromBody != fromBasic) return ClientId.Conflict
        return ClientId.Value(fromBasic)
    }

    // --- Bookkeeping ---

    /** Applies due expirations: prompts past their TTL, codes never exchanged, retention, counters. */
    private fun sweep() {
        val expired = ArrayList<Grant>()
        synchronized(lock) {
            val now = clock()
            val iterator = records.values.iterator()
            while (iterator.hasNext()) {
                val record = iterator.next()
                expireIfDue(record, now)
                if (record.status != Status.PENDING && now - record.decidedAt >= config.retainMs) iterator.remove()
            }
            val codes = grants.entries.iterator()
            while (codes.hasNext()) {
                val grant = codes.next().value
                if (now >= grant.expiresAt) {
                    codes.remove()
                    grant.expiry?.cancel()
                    expired += grant
                }
            }
            history.values.forEach { times -> while (times.isNotEmpty() && now - times.first() >= HOUR_MS) times.removeFirst() }
            history.values.removeAll { it.isEmpty() }
        }
        expired.forEach(::abandon)
    }

    /** Call with [lock] held. */
    private fun expireIfDue(record: Record, now: Long) {
        if (record.status == Status.PENDING && now >= record.expiresAt) expire(record, record.expiresAt)
    }

    /** Call with [lock] held. Cancels the prompt. */
    private fun expire(record: Record, now: Long) {
        decide(record, Status.EXPIRED, denial(record.request, "The request expired before it was approved on the phone"), now)
        record.job?.cancel()
    }

    private fun decide(record: Record, status: Status, redirect: String, now: Long) {
        record.status = status
        record.redirect = redirect
        record.decidedAt = now
    }

    private fun sameFlow(record: Record, request: Authorization): Boolean {
        val r = record.request
        return (record.status == Status.PENDING || record.status == Status.APPROVED) &&
            r.clientId == request.clientId && r.redirectUri == request.redirectUri &&
            r.codeChallenge == request.codeChallenge && r.state == request.state && r.issuer == request.issuer
    }

    private fun recent(key: String, now: Long): Int = history[key]?.count { now - it < HOUR_MS } ?: 0

    private fun remember(key: String, now: Long) {
        history.getOrPut(key) { ArrayDeque() }.addLast(now)
    }

    /** The per-address request budget; false when [address] must wait (429). */
    private fun admit(address: String): Boolean = synchronized(lock) {
        val now = clock()
        val budget = budgets[address] ?: Budget(now, 0).also {
            if (budgets.size >= MAX_BUDGETS) budgets.remove(budgets.keys.first())
            budgets[address] = it
        }
        if (now - budget.windowStart >= MINUTE_MS) {
            budget.windowStart = now
            budget.count = 0
        }
        while (budget.failures.isNotEmpty() && now - budget.failures.first() >= FAILURE_WINDOW_MS) budget.failures.removeFirst()
        if (budget.failures.size >= config.maxAuthFailures) return@synchronized false
        budget.count++
        budget.count <= config.requestsPerMinute
    }

    private fun authFailure(address: String, error: String, description: String): Response {
        synchronized(lock) { budgets[address]?.failures?.addLast(clock()) }
        log("Local token request rejected ($error) from $address")
        return tokenError(400, error, description)
    }

    private fun randomToken(bytes: Int): String = Encoding.base64Url(ByteArray(bytes).also(random::nextBytes))

    private fun displayCode(): String = buildString { repeat(DISPLAY_CODE_LENGTH) { append(DISPLAY_CODE_ALPHABET[random.nextInt(DISPLAY_CODE_ALPHABET.length)]) } }

    // --- Responses ---

    private fun json(status: Int, body: JsonObject, extra: Map<String, String> = emptyMap()) =
        Response(status, JSON_HEADERS + extra, body.toString().toByteArray(Charsets.UTF_8))

    private fun tokenError(status: Int, error: String, description: String) =
        Response(status, TOKEN_HEADERS, error(error, description).toString().toByteArray(Charsets.UTF_8))

    private fun page(status: Int, problem: String, hint: String, extra: Map<String, String> = emptyMap()): Response {
        val page = OAuthPages.error(problem, hint, random)
        return Response(status, page.headers + extra, page.html.toByteArray(Charsets.UTF_8))
    }

    private inline fun get(request: Request, answer: () -> Response): Response =
        if (request.method == "GET" || request.method == "HEAD") answer() else methodNotAllowed("GET, HEAD")

    private inline fun post(request: Request, answer: () -> Response): Response =
        if (request.method == "POST") answer() else methodNotAllowed("POST")

    private fun methodNotAllowed(allow: String) =
        json(405, error("invalid_request", "use ${allow.substringBefore(',')}"), mapOf("allow" to allow))

    companion object {
        /** [com.droidhands.core.auth.DeviceIdentity.deriveKey] purpose of the [clientKey] platforms use. */
        const val CLIENT_KEY_PURPOSE = "local-oauth-clients"

        /** Longest OAuth request body (registration metadata, token requests) the server reads. */
        const val MAX_BODY_BYTES = 16 * 1024

        /** The only scope; access levels are chosen on the phone. */
        const val SCOPE = "phone"

        const val MCP_PATH = "/mcp"
        const val PRM_PATH = "/.well-known/oauth-protected-resource"
        const val REGISTER_PATH = "/oauth/register"
        const val AUTHORIZE_PATH = "/oauth/authorize"
        const val TOKEN_PATH = "/oauth/token"
        const val PAIR_PREFIX = "/oauth/pair/"

        private val AS_METADATA_PATHS = setOf(
            "/.well-known/oauth-authorization-server",
            "/.well-known/openid-configuration",
            // Path-inserted forms some clients derive from the resource URL.
            "/.well-known/oauth-authorization-server$MCP_PATH",
            "/.well-known/openid-configuration$MCP_PATH",
        )

        /** True for the paths [handle] answers (everything below `/.well-known/` and `/oauth/`). */
        fun isOAuthPath(path: String): Boolean = path.startsWith("/.well-known/") || path.startsWith("/oauth/")

        private const val REASON_BUSY = "busy"
        private const val ALL = "*"
        private const val HOUR_MS = 3_600_000L
        private const val MINUTE_MS = 60_000L
        private const val FAILURE_WINDOW_MS = 10 * 60_000L
        private const val MAX_BUDGETS = 1024
        private const val MAX_STATE_LENGTH = 1024
        private const val MAX_CODE_LENGTH = 256
        private const val MAX_USER_AGENT_LENGTH = 200
        private const val DISPLAY_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private const val DISPLAY_CODE_LENGTH = 4
        private const val INVALID_CODE = "the authorization code is invalid, expired or already used"
        private const val RECONNECT_HINT = "Remove DroidHands from your AI app's connectors, then add your connection URL again."
        private const val UPDATE_HINT = "Update the app, or use an MCP client that supports the MCP authorization spec."
        private val RATE_LIMITED = "Too many requests from your computer." to "Wait a minute, then reload this page."

        private val PAIRING_ID = Regex("^p_[A-Za-z0-9_-]{22}$")

        /** BASE64URL(SHA-256(verifier)): always 43 chars. */
        private val CODE_CHALLENGE = Regex("^[A-Za-z0-9_-]{43}$")

        /** RFC 7636 §4.1: 43-128 unreserved characters. */
        private val CODE_VERIFIER = Regex("^[A-Za-z0-9._~-]{43,128}$")

        private val BASIC = Regex("^basic[ \\t]+([A-Za-z0-9+/=]+)[ \\t]*$", RegexOption.IGNORE_CASE)

        private val AUTHORIZE_SINGLE = listOf("response_type", "client_id", "redirect_uri", "code_challenge", "code_challenge_method", "state", "resource", "scope")
        private val TOKEN_SINGLE = listOf("grant_type", "code", "redirect_uri", "client_id", "code_verifier", "resource")

        private val JSON_HEADERS = mapOf("content-type" to "application/json")
        private val TOKEN_HEADERS = JSON_HEADERS + ("pragma" to "no-cache")

        /** True when [verifier] is well-formed and hashes to [challenge] (constant-time comparison). */
        internal fun verifyPkce(verifier: String, challenge: String): Boolean {
            if (!CODE_VERIFIER.matches(verifier)) return false
            val computed = Encoding.base64Url(Encoding.sha256(verifier.toByteArray(Charsets.US_ASCII)))
            return Encoding.constantTimeEquals(computed, challenge)
        }

        /**
         * True when [resource] (RFC 8707) is `{issuer}/mcp` or `{issuer}`, ignoring a trailing slash,
         * the default port and host case. Queries, fragments and credentials never match.
         */
        internal fun resourceMatches(resource: String, issuer: String): Boolean {
            val normalized = normalizeUrl(resource) ?: return false
            return normalized == normalizeUrl(issuer + MCP_PATH) || normalized == normalizeUrl(issuer)
        }

        private fun normalizeUrl(value: String): String? {
            val uri = try {
                URI(value)
            } catch (e: URISyntaxException) {
                return null
            }
            if (!uri.isAbsolute || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
            val scheme = uri.scheme.lowercase()
            val host = uri.host?.lowercase() ?: return null
            val port = if (uri.port == -1 || (scheme == "http" && uri.port == 80)) "" else ":${uri.port}"
            return "$scheme://$host$port${(uri.rawPath ?: "").trimEnd('/')}"
        }

        private fun hash(code: String): String = Encoding.hex(MessageDigest.getInstance("SHA-256").digest(code.toByteArray(Charsets.UTF_8)))

        private fun error(error: String, description: String) = buildJsonObject {
            put("error", error)
            put("error_description", description)
        }

        private fun stringArray(vararg values: String) = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

        private fun denial(request: Authorization, description: String): String = RedirectUris.withParams(
            request.redirectUri,
            listOf("error" to "access_denied", "error_description" to description, "state" to request.state, "iss" to request.issuer),
        )

        /** The browser's user agent for the phone prompt: printable ASCII, bounded. */
        private fun cleanUserAgent(value: String?): String? =
            value?.filter { it in ' '..'~' }?.trim()?.take(MAX_USER_AGENT_LENGTH)?.ifEmpty { null }

        /** `application/x-www-form-urlencoded` (also query strings); null when malformed. */
        internal fun formOrNull(text: String): Map<String, List<String>>? {
            val out = LinkedHashMap<String, MutableList<String>>()
            if (text.isEmpty()) return out
            for (pair in text.split('&')) {
                if (pair.isEmpty()) continue
                val name = pair.substringBefore('=')
                val value = if ('=' in pair) pair.substringAfter('=') else ""
                try {
                    out.getOrPut(URLDecoder.decode(name, "UTF-8")) { ArrayList(1) } += URLDecoder.decode(value, "UTF-8")
                } catch (e: IllegalArgumentException) {
                    return null
                }
            }
            return out
        }

        private fun Map<String, List<String>>.one(name: String): String? = this[name]?.firstOrNull()
    }
}
