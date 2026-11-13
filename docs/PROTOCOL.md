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
3. **Tokens & device identity**: formats in section 4. The relay never stores or validates agent
   token secrets; the device is the only authority.

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

## 2. Agent tokens

```
pb1.<deviceId>.<tokenId>.<tokenSecret>
regex: ^pb1\.[a-z2-7]{26}\.[a-z2-7]{12}\.[A-Za-z0-9_-]{43}$
```

* Issued **on the phone** by the user (Tokens screen). The plaintext is shown exactly once.
* The device stores `SHA-256(tokenSecret_bytes)` as lowercase hex, never the plaintext, and compares
  with a constant-time comparison.
* The relay only parses `deviceId` from the token to route the request. It never stores tokens.
* Sent by agents as `Authorization: Bearer <token>`. The relay MAY also accept
  `POST /mcp/<token>` (path token) when the operator enables `ALLOW_TOKEN_IN_URL=true`, for clients
  that cannot set headers. In that case the relay rewrites it to an `authorization` header before
  tunneling.

## 3. Device ⇄ relay WebSocket (`/v1/device`)

Endpoint: `wss://<relay-host>/v1/device` (or `ws://` for local development only).
All frames are JSON text frames, each a single object with a `type` field. Unknown `type`s MUST be
ignored by both sides (forward compatibility). Max frame size: 16 MiB.

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
{ "type": "error", "code": "unauthorized" | "bad_request" | "protocol_version" | "access_key", "message": "..." }
```

WebSocket close codes: `4000` bad request, `4001` unauthorized/access key, `4002` unsupported
protocol version, `4008` hello timeout, `4009` replaced by a newer connection of the same device.
Devices MUST NOT auto-reconnect after `4001` or `4002` until settings change. They MAY reconnect
after other codes, with backoff.

If a device connects while another connection for the same `deviceId` is live, the relay keeps the
**new** connection and closes the old one with `4009`. Pending requests on the old connection fail
with HTTP 502.

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

The relay copies `status`, the safe headers (`content-type`, `mcp-session-id`, `www-authenticate`,
`retry-after`), and `body` to the agent's HTTP response. If no response arrives within
**150 s**, the relay answers `504` with a JSON-RPC error. The device needs up to 60 s for user
confirmation plus up to 60 s for the tool to run. If the device is offline, the relay answers
`503` with a JSON-RPC error `{"code":-32000,"message":"Device offline"}`.

### 3.4 Keepalive

Either side may send `{"type":"ping","t":<millis>}`; the peer answers `{"type":"pong","t":<same>}`.
The relay also uses WebSocket-level pings every 25 s and drops connections that miss 2 pongs.
Devices SHOULD send WS-level pings every 20 s (OkHttp `pingInterval`).

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

## 5. Versioning

* `hello.protocolVersion` is the device⇄relay tunnel version (currently `1`).
* Token prefix `pb1` versions the token format.
* Tool names are stable public API (see [TOOLS.md](TOOLS.md)). Platforms may omit tools they
  cannot support, but MUST NOT change the semantics of a name.
