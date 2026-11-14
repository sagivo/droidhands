# PhoneBridge wire protocols

PhoneBridge has three wire-level contracts. Every device implementation (Android today,
iOS/desktop later) and the relay MUST follow them exactly. All JSON is UTF-8.

```
 AI agent (MCP client)                 Relay (self-hosted)                   Phone (device)
 ─────────────────────  HTTPS POST /mcp ─────────────────── WSS /v1/device ───────────────────
  Authorization: Bearer pb1.<deviceId>.<tokenId>.<secret>  ──►  route by deviceId  ──►  verify token, run tool
```

1. **Agent ⇄ endpoint**: MCP over *Streamable HTTP* (stateless subset). Served by the relay at
   `POST {relay}/mcp`, and by the phone itself at `POST http://<phone>:<port>/mcp` (local mode).
2. **Device ⇄ relay**: a JSON WebSocket that tunnels HTTP requests (section 3).
3. **Tokens & device identity**: formats in section 2. The relay never stores or validates agent
   token secrets; the device is the only authority. The one exception is pairing (§5): the relay holds
   the newly minted token in memory until the agent exchanges its code, at most 120 s.

---

## 1. Identifiers and encodings

| Name | Definition |
|---|---|
| `base32` | RFC 4648 alphabet, **lowercase** (`a-z2-7`), **no padding**. |
| `base64url` | RFC 4648 §5 URL-safe alphabet (`A-Za-z0-9-_`), **no padding**. |
| `deviceSecret` | 32 cryptographically random bytes, generated once on the device. Transmitted as base64url (43 chars). |
| `deviceId` | `base32(SHA-256(deviceSecret_bytes)[0..16])` → exactly 26 chars `[a-z2-7]{26}`. Self-certifying: anyone holding the secret can prove ownership of the id, and nobody else can. |
| `tokenId` | 12 chars `[a-z2-7]{12}` (60 random bits), base32 of random bytes truncated to 12 chars. |
| `tokenSecret` | 32 random bytes, base64url (43 chars). |

**Test vectors** (every implementation MUST pass these):

| input | output |
|---|---|
| deviceSecret = bytes `00 01 02 … 1f` (32 bytes) | base64url `AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8`, deviceId `mmg42klgyqzwneiskrelxms3j4` |
| tokenSecret = bytes `64 65 66 … 83` (100..131) | base64url `ZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoM`, stored hash `e3e16780becf601d55e4751e7d722ef91d750bb0cea61b1bb5ea8bc308cdf262` |

## 2. Agent tokens

```
pb1.<deviceId>.<tokenId>.<tokenSecret>
regex: ^pb1\.[a-z2-7]{26}\.[a-z2-7]{12}\.[A-Za-z0-9_-]{43}$
```

* Issued **on the phone**, either through one-tap pairing (§5) or manually (Agents → Connect an AI agent →
  *Use a token instead*). A manual token's plaintext is shown exactly once.
* The device stores `SHA-256(tokenSecret_bytes)` as lowercase hex, never the plaintext, and compares
  with a constant-time comparison.
* The relay only parses `deviceId` from the token to route the request. It never stores tokens.
* Sent by agents as `Authorization: Bearer <token>`. The relay MAY also accept
  `POST /mcp/<token>` (path token) when the operator enables `ALLOW_TOKEN_IN_URL=true`, for clients
  that cannot set headers. In that case the relay rewrites it to an `authorization` header before
  tunneling.

## 3. Device ⇄ relay WebSocket (`/v1/device`)

Endpoint: `wss://<relay-host>/v1/device?deviceId=<deviceId>` (or `ws://` for local development only).
All frames are JSON text frames, each a single object with a `type` field. Unknown `type`s MUST be
ignored by both sides (forward compatibility). Max frame size: 16 MiB.

**`deviceId` parameter.** Devices SHOULD append their own `deviceId` as the `deviceId` query
parameter. Relays that must pick where the device's state lives before they read any frame need it:
the Cloudflare relay (relay/CLOUDFLARE.md) routes the connection to the device's Durable Object by it,
and refuses a connection without it with `error` `protocol_version` and close `4002` (so an outdated
app stops retrying and asks to be updated). Other relays accept connections without it. When the
parameter is present, the relay refuses a `hello` for any other `deviceId` with `bad_request` and
`4000`, after the usual secret check (a wrong secret is still `unauthorized`).

### 3.1 Handshake

The device MUST send `hello` as its first frame within 10 s of connecting:

```json
{
  "type": "hello",
  "protocolVersion": 1,
  "deviceId": "<26 chars>",
  "deviceSecret": "<base64url 43 chars>",
  "platform": "android",
  "appVersion": "0.1.0",
  "deviceName": "Pixel 8",
  "accessKey": "<optional, required if relay has RELAY_ACCESS_KEY set>"
}
```

The relay verifies `deviceId == base32(SHA-256(base64urlDecode(deviceSecret))[0..16])`, using a
constant-time comparison, and checks `accessKey` if the relay is locked down. On success:

```json
{ "type": "welcome", "deviceId": "<id>", "mcpUrl": "https://relay.example.com/mcp", "serverTime": 1731500000000 }
```

On failure the relay sends a single `error` frame, then closes:

```json
{ "type": "error", "code": "unauthorized" | "bad_request" | "protocol_version" | "access_key" | "relay_full", "message": "..." }
```

WebSocket close codes: `4000` bad request, `4001` unauthorized/access key, `4002` unsupported
protocol version, `4008` hello timeout, `4009` replaced by a newer connection of the same device,
`4013` relay full (`relay_full`: the relay's `MAX_DEVICES` other devices are connected; checked
after authentication, and never for a device that is already connected, whose new connection
replaces the old one). Devices MUST NOT auto-reconnect after `4001` or `4002` until settings change.
They MAY reconnect after other codes, with backoff; after `4013` they SHOULD show the error
`message` to the user.

If a device connects while another connection for the same `deviceId` is live, the relay keeps the
**new** connection and closes the old one with `4009`. Pending requests on the old connection fail
with HTTP 502.

**Pre-hello limits.** Until its `hello` is accepted, a socket may send at most 16 KiB, or it is dropped.
The 16 MiB frame limit applies only after that. The upgrade is answered `429`/`503` when too many sockets
per IP, or in total, are waiting for their hello. A refused or timed-out handshake gets its close code and
is terminated 2 s later. (The Cloudflare relay counts waiting sockets per device and IP instead, cannot
see bytes before the runtime delivers a whole frame, closes an oversized pre-hello frame with `1009`, and
relies on the runtime to end the connection after the close handshake. Devices act on the close frame.)

### 3.2 Tunneled HTTP request (relay → device)

```json
{
  "type": "request",
  "id": "r_8f3a…",              // unique per request, opaque string
  "method": "POST",
  "path": "/mcp",
  "headers": {                  // lowercase names; only these are forwarded when present
    "authorization": "Bearer pb1....",
    "content-type": "application/json",
    "accept": "application/json, text/event-stream",
    "mcp-protocol-version": "2025-06-18",
    "mcp-session-id": "…",
    "user-agent": "claude-code/2.0",
    "origin": "…"
  },
  "body": "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",   // raw request body string
  "remoteAddress": "203.0.113.7"
}
```

### 3.3 Tunneled HTTP response (device → relay)

```json
{
  "type": "response",
  "id": "r_8f3a…",
  "status": 200,
  "headers": { "content-type": "application/json" },
  "body": "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{…}}"   // string, or null for 202/empty
}
```

Requests that fail because the device disconnected, was replaced, timed out or the relay is shutting
down get a JSON-RPC error saying **the phone may already have performed the action and the agent must
check before resending**. The device keeps running a request whose connection dropped, finishes it and
audits it. The relay copies `status`, the safe headers (`content-type`, `mcp-session-id`, `www-authenticate`,
`retry-after`), and `body` to the agent's HTTP response. If no response arrives within
**150 s**, the relay answers `504` with a JSON-RPC error. The device needs up to 60 s for user
confirmation plus up to 60 s for the tool to run. If the device is offline, the relay answers
`503` with a JSON-RPC error `{"code":-32000,"message":"Device offline"}`.

### 3.4 Keepalive

Either side may send `{"type":"ping","t":<millis>}`; the peer answers `{"type":"pong","t":<same>}`.
The relay also uses WebSocket-level pings every 25 s and drops connections that miss 2 pongs.
Devices SHOULD send WS-level pings every 20 s (OkHttp `pingInterval`).

**Heartbeat.** Once welcomed (never before: the frame would be taken for the hello), the device
sends a heartbeat every 30 s: exactly the 13-byte text frame `{"type":"hb"}`, with no whitespace and
no other fields, so that a relay can match it without parsing. The relay answers
`{"type":"hb_ack"}`. The Cloudflare relay answers through the runtime's WebSocket auto-response, which
does not wake a hibernated Durable Object, so an idle phone costs almost nothing; its WebSocket-level
pings are answered by Cloudflare's edge. A relay MAY treat a device as offline when it has received
neither a frame nor a heartbeat from it for 75 s: it then answers `503 Device offline` at once instead
of waiting for the request timeout, and closes the socket with `1001`. Relays that never saw a
heartbeat from a connection (older apps) keep treating it as online. `hb_ack` is optional: older relays
ignore `hb` like any unknown type, so devices MUST NOT depend on the answer and keep using
WebSocket-level pings for liveness.

### 3.5 Concurrency

Requests are multiplexed by `id`. Responses may be sent out of order. The relay caps in-flight
requests per device at 32 and answers `429` beyond that.

## 4. MCP endpoint behaviour (shared by relay-tunneled and local mode)

Implemented on the device by `McpServer` (core). The relay is a transparent tunnel.

* `POST /mcp`, body = one JSON-RPC 2.0 message **or** a batch array (accepted for older clients).
* Auth: missing or invalid token → HTTP `401`, header `WWW-Authenticate: Bearer realm="phonebridge"`,
  body `{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"Unauthorized"}}`.
  Revoked and expired tokens get the same response. No detail leaks.
* Body that is not valid JSON → `400` with JSON-RPC error `-32700` (Parse error), `id: null`.
* Only notifications/responses (no requests) → `202`, empty body.
* Otherwise → `200`, `content-type: application/json`, the JSON-RPC response (or array for batch).
* Stateless: the server does not issue `Mcp-Session-Id`. `GET /mcp` → `405`; `DELETE /mcp` → `405`.
* Supported `protocolVersion`s (newest first): `2025-11-25`, `2025-06-18`, `2025-03-26`,
  `2024-11-05`. `initialize` echoes the client's version if supported, otherwise the newest.
* Methods: `initialize`, `ping`, `tools/list`, `tools/call`; `resources/list`,
  `resources/templates/list`, `prompts/list` return empty lists. Notifications
  (`notifications/*`) are accepted silently. Anything else → `-32601`.
* `tools/list` returns only tools whose scope is granted to the calling token.
* `tools/call` for an unknown tool or a tool outside the token's scopes → JSON-RPC error `-32602`.
  Tool failures, policy denials, user denials, timeouts and "access paused" are returned as a
  normal result with `isError: true` and a human-readable text explanation, so the LLM can read it.
* Local-mode only: requests whose `Origin` header is present and not allowlisted → `403`
  (DNS-rebinding protection, as required by the MCP spec).

## 5. One-tap agent pairing (OAuth 2.1 through the relay)

The goal is that the user pastes **one URL** into any MCP client, then taps **Allow** on the phone.
There is no token copy/paste. The relay implements the MCP Authorization spec (2025-06-18) as an
OAuth 2.1 authorization server whose "login" is a pairing prompt on the phone. The phone still
mints the token, so the relay never holds anything long-term.

### 5.1 Device connector URL

Every device has a fixed connector URL: `{relay}/d/<deviceId>/mcp`. It behaves exactly like
`POST /mcp` (section 4), with these differences:

* The device is taken from the path. A bearer token whose embedded deviceId differs from the path's
  gets `401`.
* Every `401` on this path, whether the relay produced it or the device returned it (for example a
  revoked token), carries
  `WWW-Authenticate: Bearer realm="phonebridge", resource_metadata="{relay}/.well-known/oauth-protected-resource/d/<deviceId>/mcp"`.
  The relay rewrites or adds this header, so MCP clients start (or restart) the OAuth flow.
* `welcome` gains `"connectUrl": "{relay}/d/<deviceId>/mcp"`. The app shows it as
  **"Your connection URL"**.

### 5.2 Discovery and registration (relay)

| Endpoint | Behaviour |
|---|---|
| `GET /.well-known/oauth-protected-resource/d/<id>/mcp` | RFC 9728: `{"resource":"{relay}/d/<id>/mcp","authorization_servers":["{relay}"],"bearer_methods_supported":["header"],"resource_name":"PhoneBridge"}` |
| `GET /.well-known/oauth-authorization-server` (alias `/.well-known/openid-configuration`) | RFC 8414: `issuer` `{relay}`, `authorization_endpoint` `{relay}/oauth/authorize`, `token_endpoint` `{relay}/oauth/token`, `registration_endpoint` `{relay}/oauth/register`, `response_types_supported` `["code"]`, `grant_types_supported` `["authorization_code"]`, `code_challenge_methods_supported` `["S256"]`, `token_endpoint_auth_methods_supported` `["none"]`, `scopes_supported` `["phone"]` |
| `POST /oauth/register` | RFC 7591 dynamic registration for **public clients**. `redirect_uris` must be `https://…` or loopback `http://localhost|127.0.0.1|[::1]:<port>/…`. Private-use schemes are allowed (`com.example.app:/cb`, `cursor://…`) except a denylist of schemes that
  execute, read local data or reach other handlers (`javascript:`, `data:`, `file:`, `blob:`, `intent:`, `content:`, `ws:`,
  `mailto:`, `tel:`, …). Unsupported `token_endpoint_auth_method`/`grant_types` values are replaced in the 201
  response (RFC 7591 §3.2.1). `client_id` is **stateless**: `base64url(json{n:client_name,r:redirect_uris,t:issuedAt}) + "." + base64url(HMAC-SHA256(relaySecret, payload))`. Answers `201` with `client_id`, `client_name`, `redirect_uris`, `token_endpoint_auth_method:"none"`, `grant_types`, `response_types`, `client_id_issued_at`. |

`relaySecret` comes from `RELAY_SECRET`, or is generated once and persisted to `{DATA_DIR}/relay-secret`
(the Cloudflare relay has no disk and refuses to serve without the `RELAY_SECRET` secret).
Discovery, registration and token endpoints send `Access-Control-Allow-Origin: *` (no
credentials) so browser-based clients work.

### 5.3 Authorization (browser ⇄ relay ⇄ phone)

`GET /oauth/authorize?response_type=code&client_id&redirect_uri&code_challenge&code_challenge_method=S256&state&resource[&scope]`

1. The relay validates `client_id` (HMAC), checks that `redirect_uri` is one of the registered
   URIs, and requires PKCE `S256`. Matching is exact, except that loopback URIs match on any port
   (RFC 8252 §7.3), and loopback registrations may omit the port. An unsupported `response_type` also gets
   an HTML error page, never a redirect. On any of these errors it renders an HTML error
   page and does **not** redirect.
2. The relay picks the device from `resource`, which must be `{relay}/d/<id>/mcp` or `{relay}/d/<id>`.
   `resource` is **required**: without it the relay shows an error page. The operator can set
   `ALLOW_IMPLICIT_DEVICE=true` to fall back to the only online device, but that lets anyone who knows the
   relay URL push a pairing prompt to that phone.
3. If the device is offline, the page says "Your phone is offline. Open PhoneBridge, make sure it
   shows Connected, then reload this page."
4. The relay creates a pending pairing: `id` = `p_` + 16 random bytes in base64url; `displayCode` = 4
   chars from `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`; `pageToken` = 16 random bytes; TTL 180 s. At most
   3 pending pairings per device and 20 per device per hour. Beyond that, an error page.
5. Relay → device:
   ```json
   { "type": "pair_request", "id": "p_…", "clientName": "Claude", "redirectHost": "claude.ai",
     "displayCode": "K7Q2", "remoteAddress": "203.0.113.7", "userAgent": "…", "expiresAt": 1731500180000 }
   ```
   `clientName` is the registered `client_name`, truncated to 60 chars and stripped of control
   characters. `redirectHost` is the host (and port, for loopback) of `redirect_uri`.
6. The relay renders an HTML page (no external assets): "Approve on your phone. Open PhoneBridge on
   **<deviceName>** and tap Allow. Check that the code shown there is **K7Q2**." The page polls
   `GET /oauth/pair/<id>?t=<pageToken>` every 1.5 s. That endpoint returns
   `{"status":"pending"|"approved"|"denied"|"expired","redirect":"…"}`, and `redirect` is present
   once the pairing is decided. On a decision the page navigates to `redirect`. It also has a
   noscript fallback link that reloads it.
7. The phone shows a prompt: client name, redirect host, display code, an access-level choice
   (Read-only / **Assistant** (default) / Full control) and the confirmation policy. Allow requires an
   unlocked phone. The prompt expires at `expiresAt`. The device answers:
   ```json
   { "type": "pair_response", "id": "p_…", "approved": true, "accessToken": "pb1.…", "tokenId": "abcd…" }
   { "type": "pair_response", "id": "p_…", "approved": false, "reason": "denied" | "timeout" | "busy" }
   ```
   The device sets the issued token's `name` to the client name (the user can edit it) and
   `origin` to `"pairing"`.
8. Approved: the relay creates a single-use authorization `code` (32 random bytes, TTL 120 s) bound to
   `client_id`, `redirect_uri`, `code_challenge` and the device, and holds the access token **in
   memory only**. `redirect` = `redirect_uri?code=…&state=…&iss={relay}`. Denied or expired:
   `redirect_uri?error=access_denied&state=…`.
9. When the browser abandons the flow or the code is never exchanged before it expires, the relay sends
   `{ "type": "pair_abandoned", "id": "p_…", "tokenId": "…" }` (tokenId if one was issued). The
   device then deletes that token. After a successful exchange the relay sends
   `{ "type": "pair_complete", "id": "p_…", "tokenId": "…" }`. When a pending pairing expires
   before the phone answers, the relay sends `{ "type": "pair_cancel", "id": "p_…" }` and the phone
   removes its prompt.

**Implementation notes** (relay ≥ wave 2). Denied and expired redirects also carry `iss` and
`error_description` (RFC 9207). The status endpoint also returns `expiresAt`, and `&view=html` serves a
reloadable page that 303-redirects once the pairing is decided. Repeating an identical authorization
request reuses the pending (or approved) pairing instead of prompting the phone again. When a phone
disconnects, its pending pairings become `expired`; no `pair_cancel` is sent, because the connection is
gone. When a phone reconnects while the relay still has pending pairings for it, the relay re-sends each
`pair_request` (same `id`, `displayCode` and `expiresAt`), and the phone keeps a single prompt per id.
The device converts `expiresAt` to its own clock using the welcome's `serverTime`. A late or foreign
`pair_response` carrying a token gets `pair_abandoned` with its tokenId, and a
duplicate approval is ignored. `pair_complete`/`pair_abandoned` notices for an offline phone are
queued in memory and delivered when it reconnects. The device rejects malformed `pair_request`s
(bad id, or a display code outside the alphabet) with `denied`. It answers `busy` beyond 5 open
prompts. It interprets `expiresAt` in relay time, using the welcome's `serverTime` to correct for
clock skew. It deletes a paired token that is still unused 15 min after approval if neither
`pair_complete` nor `pair_abandoned` arrived.

**Opaque values.** Pairing ids, page tokens (`t`) and authorization codes are opaque to the browser,
the MCP client and the phone. A relay may embed routing data in them: the Cloudflare relay starts page
tokens and codes with the deviceId (both stay `[A-Za-z0-9_-]`), so its Worker can hand them to the
device's Durable Object. Nobody may parse them.

**Cloudflare relay differences.** A Durable Object can be evicted while the phone is answering, so
the Cloudflare relay keeps each device's pairing state, including a token waiting for its code
exchange (at most 120 s), in that device's Durable Object storage, encrypted with a key derived from
`RELAY_SECRET`, instead of in memory. Notices for an offline phone are kept there too. It sends
`pair_complete` when it hands out the token (a Worker cannot observe whether the response reached the
client). It applies the per-IP pairing limits per device, and does not support
`ALLOW_IMPLICIT_DEVICE` (it has no list of online phones): `resource` is always required.

### 5.4 Token endpoint

`POST /oauth/token` (`application/x-www-form-urlencoded`), with `grant_type=authorization_code`, `code`,
`redirect_uri`, `client_id`, `code_verifier`, and optionally `resource`. The relay verifies the
code, client, redirect URI, PKCE (`BASE64URL(SHA256(verifier)) == challenge`) and resource (if
sent). It then deletes the code and answers
`{"access_token":"pb1.…","token_type":"Bearer","scope":"phone"}`. There's no `expires_in`, because
tokens live until revoked on the phone, and no refresh token. A revoked token triggers a `401` with
`resource_metadata`, and the client simply pairs again. Errors follow RFC 6749 §5.2 (`invalid_grant`,
`invalid_request`, `invalid_client`, `unsupported_grant_type`, and `invalid_target` for a resource
mismatch) with status 400. `client_id` may also arrive with HTTP Basic auth. Any failed exchange burns
the code and sends `pair_abandoned`, so the phone deletes the token.

### 5.5 Phone setup link

`GET /setup` serves a mobile-friendly page with: (1) "Install PhoneBridge", a link to the relay's
`APK_URL` (e.g. the latest GitHub release's `PhoneBridge.apk`) if set, otherwise to
`/download/phonebridge.apk` if the operator put an APK in the relay's `public/` dir; and (2)
"Open in PhoneBridge", an `intent://configure?relay=<relay>&key=<accessKey>#Intent;scheme=phonebridge;package=dev.phonebridge.android;end`
link. The access key is read from the page URL **fragment** (`/setup#key=…`), so it never reaches
the server or its logs. On startup the relay prints the setup URL, with the fragment when
`RELAY_ACCESS_KEY` is set, plus a terminal QR code. The app handles
`phonebridge://configure?relay=…&key=…` (both values percent-encoded) by asking "Connect to relay
<host>?" and then saving and connecting. When an access key is set, the page draws its QR code
through `POST /setup/qr` (`{"key"}` → SVG path; a wrong key gets 403 and counts as an auth failure),
so the key never appears in a page served to someone who doesn't already have it.

## 6. Versioning

* `hello.protocolVersion` is the device⇄relay tunnel version (currently `1`).
* Token prefix `pb1` versions the token format.
* Tool names are stable public API (see [TOOLS.md](TOOLS.md)). Platforms may omit tools they
  cannot support, but MUST NOT change the semantics of a name.
