# PhoneBridge relay

A small, stateless Node.js server that lets AI agents reach a PhoneBridge phone from anywhere.

```
AI agent (MCP client) ── HTTPS POST /mcp ──► relay ◄── WSS /v1/device (outbound from the phone)
  Authorization: Bearer pb1.<deviceId>.<tokenId>.<secret>
```

The phone keeps one outbound WebSocket open to the relay, so it works behind NAT, CGNAT and mobile
networks. When an agent sends an MCP request (Streamable HTTP) to `POST /mcp`, the relay reads the
`deviceId` from the bearer token, forwards the raw HTTP request over that device's WebSocket and
copies the device's answer back. The wire format is specified in
[docs/PROTOCOL.md](../docs/PROTOCOL.md) §2-4.

## Security model

* **The phone is the only authority.** The relay never validates, stores or logs token secrets. It
  only checks the token's shape and uses the `deviceId` inside it for routing. Every token is
  verified on the phone, which also applies scopes, rate limits, confirmations and the audit log.
* **Device identity is self-certifying.** A device proves it owns its id by sending the 32-byte
  secret from which the id is derived (`deviceId = base32(SHA-256(secret))[:26]`). Nobody else can
  register that id. Set `RELAY_ACCESS_KEY` so that strangers cannot use your relay at all.
* **The relay sees the traffic.** MCP clients terminate TLS at the relay, so whoever runs it can
  read requests and responses (messages, contacts, screen contents…). **Self-host it.** It is one
  small process with a single runtime dependency (`ws`), no database and no disk writes.
* **Stateless.** All state (connected devices, in-flight requests, rate-limit counters) lives in
  memory. Restarting the relay just makes phones reconnect. Run exactly **one** instance per
  public URL: devices and agents must reach the same process.
* **Defensive defaults.** No CORS. Requests carrying an `Origin` header are rejected unless the
  origin is allowlisted. Per-IP limits on requests and authentication failures. Bodies are capped.
  At most 32 requests in flight per device. Logs never contain tokens, device secrets, access keys
  or bodies; device ids are shortened to 6 characters.

## Run locally

Requires Node.js ≥ 20.

```sh
cd relay
npm ci
npm run build
npm start                       # listens on http://0.0.0.0:8787
```

In the PhoneBridge app, set the relay URL to `http://<your-computer's-LAN-IP>:8787` (plain HTTP
and `ws://` are for local development only). The app shows the resulting MCP URL
(`welcome.mcpUrl`).

## Deploy

The relay speaks plain HTTP; put it behind a TLS-terminating reverse proxy, or use a platform that
provides TLS. In both cases set `PUBLIC_URL` and `TRUST_PROXY=true`.

### Docker behind Caddy or nginx

```sh
docker build -t phonebridge-relay relay/
docker run -d --name phonebridge-relay --restart unless-stopped \
  -p 127.0.0.1:8787:8787 \
  -e PUBLIC_URL=https://relay.example.com \
  -e TRUST_PROXY=true \
  -e RELAY_ACCESS_KEY="$(openssl rand -base64 32)" \
  phonebridge-relay
```

The image is multi-stage (`node:22-alpine`), runs as the non-root `node` user, contains only
production dependencies and has a `HEALTHCHECK` on `/healthz`.

**Caddy** (automatic HTTPS, WebSockets and `X-Forwarded-*` out of the box):

```caddyfile
relay.example.com {
    reverse_proxy 127.0.0.1:8787
}
```

**nginx** (note the WebSocket upgrade headers and the read timeout, which must exceed
`REQUEST_TIMEOUT_MS` because the phone may wait up to 60 s for the user's confirmation plus 60 s
for the tool):

```nginx
map $http_upgrade $connection_upgrade { default upgrade; '' close; }

server {
    listen 443 ssl;
    server_name relay.example.com;
    # ssl_certificate ...; ssl_certificate_key ...;

    client_max_body_size 2m;

    location / {
        proxy_pass http://127.0.0.1:8787;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 180s;
        proxy_send_timeout 180s;
        proxy_buffering off;
    }
}
```

### Fly.io

```toml
# fly.toml
app = "my-phonebridge-relay"

[build]
  dockerfile = "Dockerfile"

[env]
  PUBLIC_URL = "https://my-phonebridge-relay.fly.dev"
  TRUST_PROXY = "true"

[http_service]
  internal_port = 8787
  force_https = true
  auto_stop_machines = "off"   # phones hold a long-lived WebSocket
  auto_start_machines = true
  min_machines_running = 1

[[http_service.checks]]
  path = "/healthz"
  interval = "30s"
  timeout = "5s"
```

```sh
cd relay
fly launch --no-deploy --copy-config
fly secrets set RELAY_ACCESS_KEY="$(openssl rand -base64 32)"
fly deploy
fly scale count 1   # exactly one machine: state is in memory
```

### Render

Create a **Web Service** from the repository with root directory `relay` and runtime **Docker**.
Set `PUBLIC_URL` to the service URL, `TRUST_PROXY=true` and a `RELAY_ACCESS_KEY` secret, use
`/healthz` as the health check path, and keep one instance on a plan that does not sleep (a sleeping
service drops the phone's WebSocket).

## Configuration

All settings are environment variables. Empty values mean "use the default".

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8787` | TCP port to listen on. |
| `HOST` | `0.0.0.0` | Interface to bind. Use `127.0.0.1` behind a local reverse proxy. |
| `PUBLIC_URL` | *(unset)* | Public base URL, e.g. `https://relay.example.com` (a path prefix is allowed). Devices are told `welcome.mcpUrl = PUBLIC_URL + "/mcp"`. When unset, it is derived from the device's `Host` header, and from `X-Forwarded-Proto`/`X-Forwarded-Host` only if `TRUST_PROXY=true`. |
| `TRUST_PROXY` | `false` | Trust `X-Forwarded-For`/`-Proto`/`-Host` from a reverse proxy. The client address is the **right-most** `X-Forwarded-For` entry, i.e. the one appended by your proxy. This assumes exactly one proxy hop; never enable it when clients can reach the relay directly. |
| `RELAY_ACCESS_KEY` | *(unset)* | When set, devices must send it as `hello.accessKey` (compared in constant time, used verbatim). Otherwise any device can register its self-certifying id. |
| `ALLOW_TOKEN_IN_URL` | `false` | Also accept `POST /mcp/<token>` for MCP clients that cannot set headers. The relay moves the token into an `authorization` header before tunneling. URLs end up in client configs, proxy logs and browser history, so prefer the header. |
| `ALLOWED_ORIGINS` | *(empty)* | Comma-separated list of allowed `Origin` values (e.g. `https://inspector.example.com`), or `*`. Requests that carry any other `Origin` get `403`. Native MCP clients send no `Origin` and are unaffected. The device WebSocket is authenticated by the device secret and is not subject to this check. |
| `REQUEST_TIMEOUT_MS` | `150000` | How long to wait for the device's answer before replying `504`. |
| `MAX_BODY_BYTES` | `1048576` | Largest accepted request body (at most 8 MiB, so that a tunneled request fits in a 16 MiB frame); larger requests get `413` and the connection is closed. |
| `MAX_INFLIGHT_PER_DEVICE` | `32` | Requests a device may have in flight; more get `429`. A request stays in flight until the device answers or it times out, even if the agent disconnects. |
| `HELLO_TIMEOUT_MS` | `10000` | Time a new device socket has to send its `hello` (close code `4008`). |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` or `silent`. Logs are JSON lines on stdout. |
| `RATE_LIMIT_REQUESTS_PER_MIN` | `600` | Requests per client IP per minute on `/mcp` (device WebSocket upgrades count too); more get `429`. |
| `RATE_LIMIT_AUTH_FAILURES_PER_MIN` | `30` | Authentication failures per client IP per minute (malformed tokens rejected by the relay, `401`s returned by devices, and failed device hellos). Exceeding it blocks that IP for 60 s (`429`). |

Per-IP limits group IPv6 clients by `/64`. Behind a proxy without `TRUST_PROXY=true`, all agents
share the proxy's address and therefore one budget.

## Connect an agent

Create a token on the phone (**Tokens** screen); it is shown once. Then:

**Claude Code**

```sh
claude mcp add --transport http phone https://relay.example.com/mcp \
  --header "Authorization: Bearer pb1.xxxxxxxxxxxxxxxxxxxxxxxxxx.xxxxxxxxxxxx.xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
```

**Any MCP client with a JSON config** (Streamable HTTP transport):

```json
{
  "mcpServers": {
    "phone": {
      "type": "http",
      "url": "https://relay.example.com/mcp",
      "headers": { "Authorization": "Bearer pb1...." }
    }
  }
}
```

**Smoke test** (initialize + tools/list, prints the tool names, exits non-zero on failure):

```sh
npm run smoke -- https://relay.example.com/mcp pb1....
# or keep the token out of your shell history:
PHONEBRIDGE_TOKEN=pb1.... npm run smoke -- https://relay.example.com/mcp
```

## HTTP API

| Request | Response |
|---|---|
| `POST /mcp` with `Authorization: Bearer <token>` | Tunneled to the device. Status, `content-type`, `mcp-session-id`, `www-authenticate`, `retry-after` and the body are copied back. |
| `POST /mcp/<token>` | Same, if `ALLOW_TOKEN_IN_URL=true`; otherwise `404`. |
| `GET` / `DELETE /mcp` | `405`, `Allow: POST` (stateless server, no SSE stream). |
| `GET /healthz` | `200 {"ok":true}` |
| `GET /` | Short text banner. |
| `GET /v1/device` (WebSocket upgrade) | Device tunnel ([PROTOCOL.md](../docs/PROTOCOL.md) §3). |
| anything else | `404` |

Errors produced by the relay itself (bodies are JSON-RPC errors with `id: null`):

| Status | When |
|---|---|
| `401` | Missing or malformed token (`-32001 Unauthorized`, `WWW-Authenticate: Bearer realm="phonebridge"`). No device is contacted. |
| `403` | `Origin` header not allowlisted. |
| `413` | Body larger than `MAX_BODY_BYTES`. |
| `429` | Per-IP rate limit, auth-failure block, or the device's in-flight cap (`Retry-After` set). |
| `502` | The device disconnected or reconnected before answering, or sent an invalid response. |
| `503` | The device is offline (`-32000 Device offline`, `Retry-After: 30`), or the relay is shutting down. |
| `504` | No answer within `REQUEST_TIMEOUT_MS` (`-32000 Device did not respond in time`). |

Every response carries `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.

## Operations

* **Logs**: one JSON object per line, e.g.
  `{"time":"…","level":"info","msg":"mcp request","status":200,"ms":412,"device":"mmg42k","ip":"203.0.113.7"}`.
* **Shutdown**: on `SIGINT`/`SIGTERM` the relay stops accepting connections, answers pending
  requests with `503`, closes device sockets with code `1001` (phones reconnect with backoff) and
  exits; a second signal exits immediately.
* **Heartbeats**: WebSocket pings every 25 s; a device that misses two pongs is dropped.

## Development

```sh
npm install
npm test           # builds, then runs all node:test suites against real relays on port 0
npx tsc --noEmit   # type-check sources and tests
```

Source layout (`src/`): `relay.ts` (`createRelay(config)` → `{ server, address, url, close }`),
`server.ts` (CLI entry), `httpHandler.ts` (agent HTTP), `deviceSocket.ts` (handshake and frames),
`deviceConnection.ts` (request multiplexing), `config.ts`, `encoding.ts`, `token.ts`, `net.ts`,
`rateLimit.ts`, `protocol.ts`, `logger.ts`.

```ts
import { createRelay } from 'phonebridge-relay';
const relay = await createRelay({ port: 0, host: '127.0.0.1', logLevel: 'silent' });
console.log(relay.url); // http://127.0.0.1:54321
await relay.close();
```
