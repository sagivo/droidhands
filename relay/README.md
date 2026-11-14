# PhoneBridge relay

A small Node.js server that lets AI agents reach a PhoneBridge phone from anywhere, and lets you
connect an AI app by pasting **one URL** and tapping **Allow** on the phone.

```
AI app (MCP client) ── HTTPS POST /d/<deviceId>/mcp ──► relay ◄── WSS /v1/device (outbound from the phone)
                       Authorization: Bearer pb1.…          │
                       (obtained by one-tap OAuth pairing)  └── /oauth/*: "Approve on your phone"
```

The phone keeps one outbound WebSocket open to the relay, so it works behind NAT, CGNAT and mobile
networks. When an AI app sends an MCP request (Streamable HTTP), the relay forwards the raw HTTP
request over that phone's WebSocket and copies the phone's answer back. The wire formats are
specified in [docs/PROTOCOL.md](../docs/PROTOCOL.md) §2-5.

## Connect an AI app (one-tap pairing)

1. In the PhoneBridge app, copy **Your connection URL**. It looks like
   `https://relay.example.com/d/mmg42klgyqzwneiskrelxms3j4/mcp`.
2. Paste it into your AI app as a remote MCP server / custom connector (see below).
3. Your browser shows **Approve on your phone** with a 4-character code. The phone shows who is
   asking, where it will return, the same code and an access level (Read-only / **Assistant** /
   Full control). Check the code and tap **Allow**.
4. The browser returns to the AI app, which is now connected. Nothing to copy, no token to paste.

To disconnect an app, revoke it on the phone (**Agents**). The app's next request gets a `401` and
it offers to connect again.

**How it works.** The relay is an OAuth 2.1 authorization server ([MCP authorization spec
2025-06-18](https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization)) whose
"login" is the prompt on your phone:

* An unauthenticated request to the connection URL gets `401` with
  `WWW-Authenticate: Bearer realm="phonebridge", resource_metadata="…/.well-known/oauth-protected-resource/d/<id>/mcp"`.
  The app discovers the relay's authorization server metadata from there and registers itself
  (dynamic client registration, no secret; the registration is signed into the `client_id`, so the
  relay stores nothing).
* `/oauth/authorize` checks the client, its redirect URI and PKCE (S256), takes the phone from the
  `resource` parameter (the connection URL), sends a `pair_request` to the phone and shows the
  waiting page, which polls until the phone answers.
* On **Allow** the phone mints a normal `pb1.…` token (named after the app) and hands it to the
  relay, which keeps it **in memory only**, behind a single-use authorization code valid for 120 s.
  The app exchanges the code (with its PKCE verifier) at `/oauth/token`, and the relay forgets the
  token. If the code is never exchanged, the relay tells the phone to delete the token.

### Claude (claude.ai and Claude Desktop)

**Settings › Connectors › Add custom connector**, give it a name (e.g. "My phone"), paste the
connection URL, **Add**, then **Connect**. A browser window opens with the code; approve on the
phone. Custom connectors are shared between claude.ai and Claude Desktop on the same account.

### Claude Code

```sh
claude mcp add --transport http phone https://relay.example.com/d/<deviceId>/mcp
```

Then run `/mcp` in Claude Code, pick **phone** and choose **Authenticate**. Your browser opens the
approval page.

### ChatGPT

With developer mode enabled (**Settings › Apps & Connectors › Advanced**), create a connector, paste
the connection URL as the MCP server URL and choose **OAuth** authentication. Approve on the phone
when ChatGPT opens the browser.

### Other MCP clients

Any client that implements the MCP authorization spec (OAuth with dynamic client registration,
PKCE, and the connection URL sent as `resource`) works the same way: give it the connection URL.
Accepted redirect URIs are `https://…`, loopback `http://localhost|127.0.0.1|[::1][:<port>]/…`, and
private-use schemes with a dot (`com.example.app:/callback`). For loopback URIs any port is accepted
at authorization time (RFC 8252 §7.3), so native apps may register `http://127.0.0.1/callback` or
reuse a registration after their port changed; the token request must then use the exact URI of
the authorization request.

If the browser says *"Your AI app didn't say which phone to connect to"*, the app was given the
relay address instead of the connection URL, or it predates the MCP authorization spec 2025-06-18
and omits `resource`: use the connection URL, or update the app. (`ALLOW_IMPLICIT_DEVICE=true`
restores the old behaviour of pairing with the only connected phone; see Configuration.)

Clients without OAuth support can still use a token created by hand in the PhoneBridge app (shown
once), sent as a header:

```sh
claude mcp add --transport http phone https://relay.example.com/d/<deviceId>/mcp \
  --header "Authorization: Bearer pb1.xxxxxxxxxxxxxxxxxxxxxxxxxx.xxxxxxxxxxxx.xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
```

```json
{
  "mcpServers": {
    "phone": {
      "type": "http",
      "url": "https://relay.example.com/d/<deviceId>/mcp",
      "headers": { "Authorization": "Bearer pb1...." }
    }
  }
}
```

The older endpoint `POST /mcp` (device taken from the token) keeps working for existing configs.

## Set up a phone

When the relay starts it prints the **setup link** and a QR code:

```
  Set up a phone: open this link on it, or scan the QR code with its camera.

    https://relay.example.com/setup#key=…
```

`/setup` is a mobile-friendly page with:

1. **Install PhoneBridge**: a download link to `APK_URL` (for example the latest GitHub release's
   `PhoneBridge.apk`) if you set it, otherwise to `relay/public/phonebridge.apk` if you put the APK
   there (served from `/download/phonebridge.apk`; see [public/README.md](public/README.md)).
2. **Open in PhoneBridge**: opens the app pre-configured for this relay. The app asks "Connect to
   relay …?" and connects.
3. The relay URL (and access key) for manual entry, plus what to do next.

The access key travels in the URL **fragment** (`#key=…`), which browsers never send to the
server, so it never appears in request logs. Opened on a computer, the page also shows a QR code of
the same link for the phone to scan. The startup banner goes to stderr (not the JSON log); it
contains the access key, so set `SETUP_BANNER=false` if your stderr is shipped somewhere you don't
want the key.

## Security model

* **The phone is the only authority.** Tokens are minted on the phone, which verifies every request
  and applies scopes, per-app rules, confirmations and the audit log. The relay never validates or
  stores tokens. During pairing it holds the new token in memory until the app collects it (at most
  120 s), then drops it.
* **Pairing needs your thumb.** Every connection requires **Allow** on the unlocked phone, which
  shows the app's name, where it returns to and a code that must match the browser. Codes are
  single-use, bound to the client, redirect URI, PKCE challenge and phone. The authorization request
  must name the phone by its connection URL, which only you know (the unguessable device id is in
  it), so knowing the relay's address is not enough to send your phone a prompt. Request errors
  (unknown client, unregistered redirect URI, unsupported response type, no PKCE, no phone) are
  shown as a page and never redirected, so the relay cannot be used to bounce browsers to other
  sites. Limits: 3 pending requests per phone, 20 per phone per hour, 5 pending and 30 per hour per
  client IP.
* **Device identity is self-certifying.** A device proves it owns its id by sending the 32-byte
  secret from which the id is derived (`deviceId = base32(SHA-256(secret))[:26]`). Nobody else can
  register that id. Set `RELAY_ACCESS_KEY` so that strangers cannot use your relay at all.
* **The relay sees the traffic.** MCP clients terminate TLS at the relay, so whoever runs it can
  read requests and responses (messages, contacts, screen contents…). **Self-host it.** It is one
  small process with no database.
* **Almost stateless.** Connected devices, in-flight requests, pending pairings and rate-limit
  counters live in memory; restarting the relay just makes phones reconnect. The only thing on disk
  is `DATA_DIR/relay-secret` (or `RELAY_SECRET`), the key that signs OAuth client ids so connected
  apps survive restarts. Run exactly **one** instance per public URL.
* **Defensive defaults.** MCP endpoints reject requests carrying a non-allowlisted `Origin` and send
  CORS headers (without credentials) only to origins listed in `ALLOWED_ORIGINS`. OAuth discovery,
  registration and token endpoints allow any origin without credentials (browser-based MCP clients
  need that). Pages use a strict CSP (no external assets, nonce-only scripts, no framing),
  `Referrer-Policy: no-referrer`, and escape everything. Per-IP limits on requests and
  authentication failures; bodies are capped; at most 32 requests in flight per device. Device
  sockets that have not completed their `hello` are capped (16 per IP, 512 in total), may send at
  most 16 KiB before it (the 16 MiB frame limit applies only afterwards), and are dropped 2 s after
  a failed handshake's close frame. Secrets are compared in constant time. Logs never contain
  tokens, codes, PKCE verifiers, device secrets, access keys, the relay secret or bodies; device ids
  are shortened to 6 characters.
* **No blind retries.** If the phone's connection drops, it reconnects or the relay restarts while a
  request is in flight, the phone may still perform the action. The agent's error says so and asks
  it to check (for example with `sms_list` or `screen_read`) before sending the request again, and
  carries no `Retry-After`.

## Run locally

Requires Node.js ≥ 20.

```sh
cd relay
npm ci
npm run build
npm start                       # listens on http://0.0.0.0:8787 and prints the setup link
```

Without `PUBLIC_URL` the setup link uses your computer's LAN address, so a phone on the same Wi-Fi
can open it. Plain HTTP and `ws://` are for local development only; AI apps such as claude.ai need
an HTTPS URL they can reach (deploy the relay, or use a tunnel such as Tailscale Funnel or
`cloudflared`).

## Deploy

The relay speaks plain HTTP; put it behind a TLS-terminating reverse proxy, or use a platform that
provides TLS. In both cases set `TRUST_PROXY=true`, and `PUBLIC_URL` unless the platform is Render
or Fly.io (detected automatically).

### Fly.io

[`fly.toml`](fly.toml) is ready to use:

```sh
cd relay
fly launch --copy-config --no-deploy --name my-phonebridge-relay
fly secrets set RELAY_ACCESS_KEY="$(openssl rand -base64 32)" RELAY_SECRET="$(openssl rand -base64 32)"
fly deploy --ha=false             # exactly one machine: state is in memory
fly logs                          # shows the setup link
```

### Render

[`render.yaml`](render.yaml) is a Blueprint: **New › Blueprint**, pick your fork of this repository
and set the Blueprint path to `relay/render.yaml`. It runs one always-on instance (free instances
sleep, which drops the phone's connection) and generates `RELAY_ACCESS_KEY` and `RELAY_SECRET`. The
setup link is in the service's logs.

### Docker behind Caddy or nginx

```sh
docker build -t phonebridge-relay relay/
docker run -d --name phonebridge-relay --restart unless-stopped \
  -p 127.0.0.1:8787:8787 \
  -v phonebridge-data:/data \
  -e PUBLIC_URL=https://relay.example.com \
  -e TRUST_PROXY=true \
  -e RELAY_ACCESS_KEY="$(openssl rand -base64 32)" \
  phonebridge-relay
docker logs phonebridge-relay    # shows the setup link
```

The image is multi-stage (`node:22-alpine`), runs as the non-root `node` user, contains only
production dependencies, keeps the relay secret in the `/data` volume (`DATA_DIR`), and has a
`HEALTHCHECK` on `/healthz`. To offer the APK from `/setup`, set `APK_URL` (e.g.
`https://github.com/<owner>/<repo>/releases/latest/download/PhoneBridge.apk`), or copy the APK to
`relay/public/` before building, or mount a directory and set `PUBLIC_DIR`. Every release also
publishes a ready-made image, `ghcr.io/<owner>/phonebridge-relay:<version>` and `:latest`.

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

If `PUBLIC_URL` has a path prefix (`https://example.com/relay`), the proxy must strip it, and should
also route `/.well-known/oauth-authorization-server/relay` and
`/.well-known/oauth-protected-resource/relay/…` (RFC 8414/9728 put the prefix after
`.well-known`) to the relay; it accepts both forms.

## Run the default relay for your build

Release builds of the app can come with a **default relay**: on first run the app connects to it by
itself, so someone who installs the APK never sees a relay step (they can still switch to their own
relay at any time). If you publish builds (the project's releases, or your fork's), you run that
relay. It is the same relay as above; a few settings matter more because strangers' phones use it.

**1. Deploy the image.** Each release pushes `ghcr.io/<owner>/phonebridge-relay:<version>` and
`:latest` (see [docs/RELEASING.md](../docs/RELEASING.md)). New GHCR packages are private: make it
public once (*your profile › Packages › phonebridge-relay › Package settings › Change visibility*),
or log the server in with `docker login ghcr.io`.

* **Any VPS with Caddy** (automatic HTTPS): run the container as in
  [Docker behind Caddy or nginx](#docker-behind-caddy-or-nginx), with the image from GHCR and these
  extra variables, and the same two-line Caddyfile:

  ```sh
  docker run -d --name phonebridge-relay --restart unless-stopped \
    -p 127.0.0.1:8787:8787 -v phonebridge-data:/data \
    -e PUBLIC_URL=https://relay.example.com -e TRUST_PROXY=true \
    -e MAX_DEVICES=1000 \
    -e APK_URL=https://github.com/<owner>/<repo>/releases/latest/download/PhoneBridge.apk \
    ghcr.io/<owner>/phonebridge-relay:latest
  ```

* **Fly.io**: from `relay/`, `fly launch --copy-config --no-deploy --name <app>`, then
  `fly secrets set RELAY_SECRET="$(openssl rand -base64 32)"`, set `MAX_DEVICES` and `APK_URL` in
  `fly.toml`'s `[env]`, and `fly deploy --ha=false --image ghcr.io/<owner>/phonebridge-relay:<version>`.
  Raise `[http_service.concurrency]` and the VM's memory together with `MAX_DEVICES`.

**2. Settings for a shared relay.**

* **No `RELAY_ACCESS_KEY`.** The app ships without one, so every phone must be able to register.
  Phones stay protected: device ids are self-certifying, and every agent needs a token approved on
  the phone. `MAX_DEVICES` caps how many phones may be connected at once; a new phone beyond it is
  refused with close code `4013` and keeps retrying, while phones already connected may always
  reconnect. Start with the default and watch memory.
* **Keep the relay secret.** Set `RELAY_SECRET` or keep the `/data` volume, so paired apps survive
  restarts and redeploys.
* `TRUST_PROXY=true` behind the proxy (per-IP limits need the real client address), and keep
  `ALLOW_IMPLICIT_DEVICE` and `ALLOW_TOKEN_IN_URL` off (the defaults).
* `APK_URL` makes the relay's `/setup` page offer the release APK.

The operator of a shared relay can read the traffic it forwards (see [Security model](#security-model)).
Say who runs it and how, keep it updated, and tell people they can run their own.

**3. Bake its URL into the app.** Set the repository variable `PHONEBRIDGE_RELAY_URL` (*Settings ›
Secrets and variables › Actions › Variables*) to the relay's public URL, e.g.
`https://relay.example.com`. The release workflow passes it to the build as
`PHONEBRIDGE_DEFAULT_RELAY_URL`, which becomes `BuildConfig.DEFAULT_RELAY_URL`: every APK it builds
goes online through that relay on first run. Without the variable, builds have no default relay and
the app asks for one as before. Changing it later only affects new installs: the app never replaces
a relay that is already configured.

Local builds can do the same with `-Pphonebridge.defaultRelayUrl=https://relay.example.com` (or the
environment variable).

## Configuration

All settings are environment variables. Empty values mean "use the default".

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8787` | TCP port to listen on. |
| `HOST` | `0.0.0.0` | Interface to bind. Use `127.0.0.1` behind a local reverse proxy. |
| `PUBLIC_URL` | *(unset)* | Public base URL, e.g. `https://relay.example.com` (a path prefix is allowed). It is the OAuth issuer and the base of every URL the relay hands out (`welcome.connectUrl`, metadata, setup link). When unset: Render's `RENDER_EXTERNAL_URL` or `https://<FLY_APP_NAME>.fly.dev` if present, otherwise derived per request from the `Host` header (and from `X-Forwarded-Proto`/`X-Forwarded-Host` only if `TRUST_PROXY=true`). |
| `TRUST_PROXY` | `false` | Trust `X-Forwarded-For`/`-Proto`/`-Host` from a reverse proxy. The client address is the **right-most** `X-Forwarded-For` entry, i.e. the one appended by your proxy. This assumes exactly one proxy hop; never enable it when clients can reach the relay directly. |
| `RELAY_ACCESS_KEY` | *(unset)* | When set, devices must send it as `hello.accessKey` (compared in constant time, used verbatim). It is included in the setup link's fragment. Otherwise any device can register its self-certifying id. |
| `RELAY_SECRET` | *(unset)* | Key (at least 32 characters, used verbatim) that signs OAuth client ids. When unset, one is generated into `DATA_DIR/relay-secret`. Changing it disconnects every paired app at its next authorization (they re-pair). |
| `DATA_DIR` | `./data` (`/data` in Docker) | Where `relay-secret` is created (directory `0700`, file `0600`) when `RELAY_SECRET` is unset. If it cannot be written, the relay uses a temporary secret and logs a warning: apps paired then must reconnect after a restart. |
| `PUBLIC_DIR` | `relay/public` | Directory served for `/download/phonebridge.apk`. |
| `APK_URL` | *(unset)* | Where the setup page's **Install PhoneBridge** button downloads the app, e.g. `https://github.com/<owner>/<repo>/releases/latest/download/PhoneBridge.apk`. Wins over a file in `PUBLIC_DIR`; `/download/phonebridge.apk` then redirects (`302`) to it. Absolute `http(s)` URL without credentials. |
| `MAX_DEVICES` | `1000` | Phones that may be connected at the same time. A new phone beyond it gets an `error` frame `relay_full` and close code `4013`, and retries with backoff. A phone that is already connected may always reconnect (its new connection replaces the old one). |
| `SETUP_BANNER` | `true` | Print the setup link and QR code to stderr at startup. |
| `ALLOW_TOKEN_IN_URL` | `false` | Also accept `POST /mcp/<token>` for MCP clients that cannot set headers. The relay moves the token into an `authorization` header before tunneling. URLs end up in client configs, proxy logs and browser history, so prefer OAuth or the header. |
| `ALLOWED_ORIGINS` | *(empty)* | Comma-separated list of allowed `Origin` values (e.g. `https://inspector.example.com`), or `*`, for the MCP endpoints. Requests that carry any other `Origin` get `403`. Requests from an allowed origin get CORS headers for it (`Access-Control-Allow-Origin: <origin>`; `WWW-Authenticate`, `Mcp-Session-Id` and `Retry-After` exposed; no credentials) and `OPTIONS` preflights are answered with `204`, so browser-based MCP clients on that origin work. Native and server-side MCP clients send no `Origin` and are unaffected. OAuth and setup endpoints are not subject to this check. |
| `ALLOW_IMPLICIT_DEVICE` | `false` | Legacy: when an authorization request has no `resource`, pair with the only connected phone instead of showing an error. Leave it off on a relay reachable from the internet: otherwise anyone who knows the relay's address can send your phone pairing prompts. |
| `REQUEST_TIMEOUT_MS` | `150000` | How long to wait for the device's answer before replying `504`. |
| `MAX_BODY_BYTES` | `1048576` | Largest accepted request body (at most 8 MiB, so that a tunneled request fits in a 16 MiB frame); larger requests get `413` and the connection is closed. |
| `MAX_INFLIGHT_PER_DEVICE` | `32` | Requests a device may have in flight; more get `429`. A request stays in flight until the device answers or it times out, even if the agent disconnects. |
| `HELLO_TIMEOUT_MS` | `10000` | Time a new device socket has to send its `hello` (close code `4008`). |
| `MAX_PENDING_HANDSHAKES` | `512` | Device sockets that may wait for their `hello` at the same time; more get `503`. |
| `MAX_PENDING_HANDSHAKES_PER_IP` | `16` | The same per client IP (IPv6: per `/64`); more get `429`. Behind a proxy without `TRUST_PROXY=true`, every phone counts against the proxy's address. |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` or `silent`. Logs are JSON lines on stdout. |
| `RATE_LIMIT_REQUESTS_PER_MIN` | `600` | Requests per client IP per minute across the MCP, OAuth and setup endpoints (device WebSocket upgrades count too); more get `429`. |
| `RATE_LIMIT_AUTH_FAILURES_PER_MIN` | `30` | Authentication failures per client IP per minute (malformed or wrong-device tokens, `401`s returned by devices, failed device hellos, rejected token requests, wrong setup access keys). Exceeding it blocks that IP for 60 s (`429`). A connector request with no token at all (the first step of OAuth discovery) does not count. |

Per-IP limits group IPv6 clients by `/64`. Behind a proxy without `TRUST_PROXY=true`, all clients
share the proxy's address and therefore one budget.

## Smoke test

`initialize` + `tools/list` with a token, prints the tool names, exits non-zero on failure:

```sh
npm run smoke -- https://relay.example.com/d/<deviceId>/mcp pb1....
# or keep the token out of your shell history:
PHONEBRIDGE_TOKEN=pb1.... npm run smoke -- https://relay.example.com/mcp
```

## HTTP API

| Request | Response |
|---|---|
| `POST /d/<deviceId>/mcp` with `Authorization: Bearer <token>` | The device connector URL: tunneled to that device. The token must belong to it. Every `401` (relay- or device-produced) carries `WWW-Authenticate: Bearer realm="phonebridge", resource_metadata="{relay}/.well-known/oauth-protected-resource/d/<deviceId>/mcp"`. |
| `POST /mcp` with `Authorization: Bearer <token>` | Tunneled to the device named in the token. |
| `POST /mcp/<token>` | Same, if `ALLOW_TOKEN_IN_URL=true`; otherwise `404`. |
| `GET` / `DELETE` on either MCP endpoint | `405`, `Allow: POST` (stateless server, no SSE stream). |
| `OPTIONS` on either MCP endpoint | From an origin in `ALLOWED_ORIGINS`: `204` CORS preflight answer (`POST`; `Authorization`, `Content-Type`, `Accept`, `Mcp-Protocol-Version`, `Mcp-Session-Id`). Otherwise `403` (other origins) or `405`. |
| `GET /.well-known/oauth-protected-resource/d/<deviceId>/mcp` | RFC 9728 protected resource metadata. |
| `GET /.well-known/oauth-authorization-server` (alias `/.well-known/openid-configuration`) | RFC 8414 authorization server metadata. |
| `POST /oauth/register` | RFC 7591 dynamic registration of public clients → `201` with a stateless `client_id`. |
| `GET /oauth/authorize?…` | Validates the request (`resource` must be the phone's connection URL), prompts the phone and shows the "Approve on your phone" page. Every error is an HTML page, never a redirect. |
| `GET /oauth/pair/<id>?t=<pageToken>` | Pairing status for the page: `{"status":"pending"\|"approved"\|"denied"\|"expired","redirect"?}`. With `&view=html`, the reloadable page (redirects once decided). |
| `POST /oauth/token` | Authorization code + PKCE verifier → `{"access_token":"pb1.…","token_type":"Bearer","scope":"phone"}`. Errors per RFC 6749 §5.2. |
| `GET /setup` | Phone setup page. |
| `GET /download/phonebridge.apk` | `302` to `APK_URL` when set; otherwise the APK from `PUBLIC_DIR`, if present (`application/vnd.android.package-archive`). |
| `POST /setup/qr` | Used by the setup page: `{"key"}` → QR code (SVG path) of the setup link, if the key is the access key. |
| `GET /healthz` | `200 {"ok":true}` |
| `GET /` | Short text banner with the setup link. |
| `GET /v1/device` (WebSocket upgrade) | Device tunnel ([PROTOCOL.md](../docs/PROTOCOL.md) §3). `429`/`503` when too many sockets from that IP / in total are waiting for their `hello`; close code `4013` (`relay_full`) after the `hello` when `MAX_DEVICES` other phones are connected. |
| anything else | `404` |

Errors produced by the relay itself on the MCP endpoints (bodies are JSON-RPC errors with `id: null`):

| Status | When |
|---|---|
| `401` | Missing or malformed token, or a token for another device on `/d/<id>/mcp` (`-32001 Unauthorized`). No device is contacted. |
| `403` | `Origin` header not allowlisted. |
| `413` | Body larger than `MAX_BODY_BYTES`. |
| `429` | Per-IP rate limit, auth-failure block, or the device's in-flight cap (`Retry-After` set). |
| `502` | The device disconnected or reconnected before answering, or sent an invalid response. |
| `503` | The device is offline (`-32000 Device offline`, `Retry-After: 30`), or the relay is shutting down (`Retry-After: 30` only if the request had not been forwarded yet). |
| `504` | No answer within `REQUEST_TIMEOUT_MS` (`-32000 The phone did not answer in time. …`). |

When a forwarded request fails (`502`, `504`, or `503` during shutdown), the phone may still have
performed the action (tool calls such as sending a message are not idempotent). The message says
so, e.g. `The phone's connection dropped before it answered. The phone may already have performed
the action: check whether it happened (for example with sms_list or screen_read) before sending the
request again.`

Every response carries `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.

## Operations

* **Logs**: one JSON object per line on stdout, e.g.
  `{"time":"…","level":"info","msg":"mcp request","status":200,"ms":412,"device":"mmg42k","ip":"203.0.113.7","route":"connector"}`.
  Pairing events: `client registered`, `pairing requested`, `pairing approved` / `denied` /
  `expired`, `token issued`, `pairing completed` / `abandoned`.
* **Shutdown**: on `SIGINT`/`SIGTERM` the relay stops accepting connections, cancels pending
  pairing prompts, tells phones to delete tokens no app collected, answers pending requests with
  `503` (saying the phone may already have performed the action), closes device sockets with code
  `1001` (phones reconnect with backoff) and exits; a second signal exits immediately.
* **Pairing across reconnects**: if a phone reconnects while the relay still holds its previous
  (possibly dead) socket, pending pairing requests are sent again on the new connection with the
  same id, code and expiry, so the prompt still appears.
* **Heartbeats**: WebSocket pings every 25 s; a device that misses two pongs is dropped.

## Development

```sh
npm install
npm test           # builds, then runs all node:test suites against real relays on port 0
npx tsc --noEmit   # type-check sources and tests
```

Source layout (`src/`):

| Path | What |
|---|---|
| `relay.ts` | `createRelay(config)` → `{ server, address, url, close }` |
| `server.ts` | CLI entry, startup banner |
| `httpHandler.ts` | Router; `mcpHandler.ts` tunnels MCP requests; `httpDeps.ts`, `httpUtil.ts` shared helpers |
| `deviceSocket.ts`, `deviceConnection.ts`, `deviceRegistry.ts`, `handshakeGuard.ts` | Device handshake (and its limits), frames, request multiplexing |
| `oauth/` | `metadata.ts` (discovery documents), `clientRegistration.ts` (stateless client ids), `redirectUris.ts`, `pkce.ts`, `pairingStore.ts` (pairings, codes, limits), `pairingFrames.ts`, and the routes (`discoveryRoutes.ts`, `authorizeRoutes.ts`, `tokenRoute.ts`) |
| `pages/` | Safe HTML templating with CSP (`html.ts`), pairing pages, setup page |
| `setup/` | `/setup` routes, setup URL, QR rendering, startup banner |
| `config.ts`, `relaySecret.ts`, `encoding.ts`, `token.ts`, `net.ts`, `rateLimit.ts`, `protocol.ts`, `logger.ts`, `text.ts`, `body.ts` | Plumbing |

```ts
import { createRelay } from 'phonebridge-relay';
const relay = await createRelay({ port: 0, host: '127.0.0.1', logLevel: 'silent', relaySecret: '…32+ chars…' });
console.log(relay.url); // http://127.0.0.1:54321
await relay.close();
```
