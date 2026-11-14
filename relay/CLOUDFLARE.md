# PhoneBridge relay on Cloudflare Workers

The relay also runs on [Cloudflare Workers](https://developers.cloudflare.com/workers/) with
[Durable Objects](https://developers.cloudflare.com/durable-objects/): no server to run, TLS and a
public URL included, and it fits the **Workers Free plan** for personal use and small groups. It is
the same relay as the Node one in this directory: same URLs, same pairing flow, same device protocol
([docs/PROTOCOL.md](../docs/PROTOCOL.md)). AI apps, browsers and the Android app cannot tell them
apart, and a shared [conformance suite](conformance/) checks both on every change.

The Node relay stays fully supported for self-hosting ([README.md](README.md)).

## How it works

```
AI app ── HTTPS ──► Worker (routing, OAuth discovery/registration, pages, limits)
                      │  picks the phone's Durable Object by its device id
                      ▼
               Durable Object "phone mmg42k…" ◄── WebSocket ── phone (outbound)
               (tunneled requests, pairings, alarm)
```

* The **Worker** answers everything that needs no per-phone state: OAuth metadata, client
  registration (stateless, signed with `RELAY_SECRET`), the setup page, health, request checks.
* Each phone gets its own **Durable Object**, named by its device id. The phone connects to
  `wss://<relay>/v1/device?deviceId=<id>`; the parameter tells the Worker which object to use. The
  object holds the phone's WebSocket, forwards MCP requests over it and waits for the answers, and
  keeps the phone's pending pairings (encrypted with a key derived from `RELAY_SECRET`, in the
  object's own SQLite storage, for at most a few minutes).
* **Hibernation keeps idle phones nearly free.** The phone's WebSocket uses the
  [Hibernation API](https://developers.cloudflare.com/durable-objects/best-practices/websockets/): when
  nothing happens for a few seconds, Cloudflare unloads the object from memory but keeps the socket
  open. The phone's heartbeat (`{"type":"hb"}` every 30 s) and WebSocket pings are answered by
  Cloudflare itself without loading the object. The object only wakes up, for the duration of the
  work, when an AI app calls the phone, a pairing starts, or the phone reconnects. An idle phone uses
  no compute time at all.

## Free plan limits, in plain words

Numbers as of late 2026; Cloudflare changes them now and then, so check
[Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/) and
[Durable Objects pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/).

| Free plan limit | What uses it here |
|---|---|
| 100,000 Worker requests per day | Every MCP call, every phone (re)connection, every pairing page load and poll (one every 1.5 s while the page is open). |
| 100,000 Durable Object requests per day | Every MCP call (one), pairing steps, phone connections. Messages from the phone count as 1/20 of a request each. |
| 13,000 GB-s of Durable Object time per day | An object counts as 128 MB while it is awake: that is about 28 hours of "awake" per day across all phones. An MCP call keeps the phone's object awake while the phone works on it (seconds, or up to a minute while you confirm on the phone), plus about 10 s before it hibernates again. |
| 10 ms CPU per request | Plenty for normal calls: the relay only routes and copies JSON, and waiting for the phone is not CPU time. Copying very large answers (several MiB, such as big screenshots) costs more and may hit this limit; `wrangler dev` does not enforce it, so this is untested. |
| 5 GB storage, 100,000 rows written per day | A few small writes per pairing. Nothing is stored for normal MCP traffic. |

What that means:

* **A personal relay** (you and a few phones, a few hundred AI tool calls a day) uses well under 5 %
  of the free plan.
* **The binding limit for heavier use** is usually the Durable Object time: a burst of calls to the
  same phone shares one wake-up, but calls spread out through the day cost about 11 s of awake time
  each, so roughly **9,000 spread-out calls a day** across all phones (or ~100,000 back-to-back
  calls). Idle phones cost nothing beyond their reconnects.
* When a free limit is reached, Cloudflare refuses requests until the daily reset (00:00 UTC): AI
  apps get errors and phones cannot connect. The [Workers Paid plan](https://developers.cloudflare.com/workers/platform/pricing/)
  ($5 per month) raises the limits to millions of requests and 400,000 GB-s per month.
* WebSocket messages may be up to 32 MiB on Cloudflare; the protocol caps frames at 16 MiB (large
  screenshots fit; verified locally with 15 MiB responses).

## Deploy

You need a Cloudflare account (free) and Node.js 20+.

```sh
cd relay
npm ci
npx wrangler login                       # opens the browser once
npx wrangler secret put RELAY_SECRET     # paste the output of: openssl rand -base64 32
# Optional, for a private relay: only phones that know the key may connect.
npx wrangler secret put RELAY_ACCESS_KEY # paste the output of: openssl rand -base64 32
npm run cf:deploy
```

`cf:deploy` prints the relay's URL, `https://phonebridge-relay.<your-subdomain>.workers.dev`. Check
it with `curl https://…/healthz` (`{"ok":true}`; a `500` names a missing or invalid setting), then
open `https://…/setup` on the phone (with `#key=<RELAY_ACCESS_KEY>` appended if you set one).

To check a deploy end to end without a phone, connect the demo device and drive it like an AI app:

```sh
cd clients/android && DEMO_RELAY_URL=https://… DEMO_PAIR=approve DEMO_CONFIRM=approve ./gradlew :core:runDemoDevice -q
# prints DEMO_TOKEN=… and RELAY_STATUS=Connected(…, connectUrl=https://…/d/<id>/mcp); in another shell:
PB_TOKEN=<DEMO_TOKEN> IDLE_MS=100000 node scripts/e2e/relay-interop.mjs <connectUrl>   # MCP calls, large bodies, heartbeat idle
node scripts/e2e/pairing.mjs <connectUrl>                                              # the OAuth pairing flow
```

`RELAY_SECRET` signs the OAuth client ids of connected AI apps. Keep it: changing it makes every AI
app reconnect (pair again). The relay refuses to serve until it is set. Never put secrets in
`wrangler.toml`.

### Settings

Plain settings live in `[vars]` in [`wrangler.toml`](wrangler.toml) (deploy again after changing
them); secrets are set with `npx wrangler secret put <NAME>`. The variables are the Node relay's
([README.md](README.md#configuration)), plus:

| Variable | Default | Meaning |
|---|---|---|
| `PUBLIC_URL` | *(empty)* | The relay's public URL. Empty: the address each request was sent to. Set it when the relay has a custom domain, so all URLs it hands out use it. |
| `APK_URL` | *(empty)* | Where the setup page's **Install PhoneBridge** button points (for example the APK of the latest GitHub release); `/download/phonebridge.apk` redirects there. Without it the page has no install button. |
| `ALLOWED_ORIGINS` | *(empty)* | As on the Node relay: browser origins allowed to call the MCP endpoints. |
| `SETUP_BANNER` | `false` | Log the setup link and a QR code once per Worker instance. Off by default: deployed logs are stored by Cloudflare, and the link contains `RELAY_ACCESS_KEY` if set. |
| `HEARTBEAT_TIMEOUT_MS` | `75000` | A phone that sends heartbeats but was silent this long counts as offline (AI apps get `503 Device offline` at once). |

`REQUEST_TIMEOUT_MS`, `MAX_BODY_BYTES`, `MAX_INFLIGHT_PER_DEVICE`, `HELLO_TIMEOUT_MS`,
`MAX_PENDING_HANDSHAKES[_PER_IP]`, `RATE_LIMIT_*`, `ALLOW_TOKEN_IN_URL`, `LOG_LEVEL` and
`RELAY_ACCESS_KEY` work as on the Node relay. `TRUST_PROXY`, `DATA_DIR`, `PUBLIC_DIR` and
`ALLOW_IMPLICIT_DEVICE` do not apply and are ignored (with a warning in the logs).
`MAX_PENDING_HANDSHAKES[_PER_IP]` count per phone here (see below).

Leave `ALLOW_TOKEN_IN_URL` off on Cloudflare unless you also turn off invocation logs: Cloudflare
logs every request's URL, and with that setting the agent's token is part of the URL path. (Query
strings, which carry pairing page tokens, are already kept out of the logs by `wrangler.toml`.)

### Custom domain

With a domain on your Cloudflare account, add to `wrangler.toml`:

```toml
routes = [{ pattern = "relay.example.com", custom_domain = true }]
workers_dev = false   # one public URL, so every connection URL looks the same
```

and set `PUBLIC_URL = "https://relay.example.com"` in `[vars]`, then `npm run cf:deploy`. (Or use the
dashboard: **Workers & Pages › phonebridge-relay › Settings › Domains & Routes**.)

### Make release APKs use it

Release builds bake in a default relay URL from the GitHub repository variable
`PHONEBRIDGE_RELAY_URL`. Set it to your relay's URL (**Settings › Secrets and variables › Actions ›
Variables**, or `gh variable set PHONEBRIDGE_RELAY_URL --body https://relay.example.com`), then cut a
release. Phones that install that APK connect to your relay without any setup.

### Deploy from GitHub Actions

[`.github/workflows/relay-cloudflare.yml`](../.github/workflows/relay-cloudflare.yml) runs the relay
tests and both conformance runs on every push to `main` that touches `relay/`, then deploys with
`wrangler deploy`, but only if the repository has the secret `CLOUDFLARE_API_TOKEN` (an API token
from the "Edit Cloudflare Workers" template) and `CLOUDFLARE_ACCOUNT_ID`. Without them the deploy job
is skipped. Set `RELAY_SECRET` once with `wrangler secret put` as above; deploys keep it.

## Costs and privacy

* **Free** for the usage above; nothing is billed unless you move to a paid plan yourself.
* Like any relay, it can read the MCP traffic it carries (messages, contacts, screen contents…). On
  Cloudflare that means your Cloudflare account and Cloudflare itself. Tokens are never stored except
  a freshly paired token for at most 120 s (encrypted; storage history alone does not reveal it), and
  logs contain no tokens, codes, secrets or bodies. Workers Logs are enabled in `wrangler.toml`
  (`[observability]`, with query strings redacted from the URLs Cloudflare records); turn them off
  there if you prefer. A phone's object keeps pairing state (including client addresses for the
  hourly pairing limits) for at most about an hour after its last pairing, then deletes it; requests
  that name a phone which never paired store nothing.
* Updating the relay (a new deploy) restarts the objects: phones reconnect by themselves, and a call
  that was in progress fails with the usual "the phone may already have performed the action" error.

## Differences from the Node relay

Clients see the same behavior; the conformance suite checks it. Operational differences:

* Per-IP rate limits (requests, authentication failures) are counted per Worker instance, so they are
  best effort: Cloudflare runs many instances. Failed device hellos (wrong secret or access key) are
  counted per phone and IP in the phone's object, and also block that IP's next connections to it.
  Per-phone limits (pairings, requests in flight, sockets waiting for their hello) are exact, because
  they live in the phone's object. Cloudflare's own DDoS protection sits in front. Use a long random
  `RELAY_ACCESS_KEY` (as `openssl rand` makes): guessing is then hopeless at any rate.
* Phones must send `?deviceId=` on the device URL. Apps from before that change are refused with
  "update the PhoneBridge app" (close code `4002`, so they stop retrying).
* `pair_complete` is sent when the token is handed out (a Worker cannot see whether the response
  reached the AI app); the phone's 15-minute cleanup of unused tokens still applies.
* Pending pairings and undelivered pairing notices are kept in the phone's object storage, not only in
  memory, so they survive the object being unloaded.
* There is no startup banner (see `SETUP_BANNER`), no graceful shutdown (deploys restart objects), and
  no file system (`APK_URL` replaces `public/phonebridge.apk`).
* Before its hello a socket is limited per frame (16 KiB), not per byte as it arrives. Locally,
  `wrangler dev` keeps the TCP connection of a socket that never sent anything open after closing it
  with `4008`; devices act on the close frame, so this only shows up in tools that wait for TCP.
* A Worker cannot cut a connection off: a refused socket whose client never answers the close frame
  stays open until the client goes away. Until then it keeps counting toward the limits on sockets
  waiting for their hello (16 per client address and 512 per phone by default), as a socket does on
  the Node relay until its TCP connection ends.

### Running a shared public relay

The per-phone limits above bound what any one client can do to one phone, and requests that name
phones which never connected cost a request but store nothing. The daily Free plan quotas, though,
are shared by everyone using the relay, and the per-IP limits are per Worker instance: someone
determined (many addresses, or their own app identity sending a stream of frames) can use up a day's
free requests. For a relay that strangers' phones use, add a
[rate limiting rule](https://developers.cloudflare.com/waf/rate-limiting-rules/) in the Cloudflare
dashboard (for example on `/v1/device` and `/mcp`) and watch the usage graphs. On the Workers Paid
plan such abuse costs money instead of stopping the relay until the daily reset.

## Develop and test locally

Nothing here needs a Cloudflare account: everything runs in `workerd` through Wrangler and Miniflare.

```sh
cd relay
echo "RELAY_SECRET=$(openssl rand -base64 32)" > .dev.vars   # git-ignored
npm run cf:dev                    # http://127.0.0.1:8787 (for phones on your LAN: npm run cf:dev -- --ip 0.0.0.0)
npm run typecheck:worker          # Worker sources against the Workers runtime types
npm run test:worker               # Durable Object tests inside workerd (@cloudflare/vitest-plugin)
npm run conformance:worker        # the shared black-box suite against `wrangler dev --local`
npm run conformance:node          # the same suite against the Node relay
```

Source: [`src/worker/`](src/worker/) (`index.ts` Worker and routing, `deviceObject.ts` the Durable
Object, `config.ts`, `http.ts`). The protocol logic itself is shared with the Node relay
(`src/handshake.ts`, `src/oauth/*`, `src/pages/*`, `src/setup/qr.ts`, …).

To try it with the Kotlin demo device, a headless phone (`clients/android`, JDK 17):

```sh
cd clients/android
DEMO_RELAY_URL=http://127.0.0.1:8787 DEMO_PAIR=approve DEMO_CONFIRM=approve ./gradlew :core:runDemoDevice
node scripts/e2e/pairing.mjs http://127.0.0.1:8787/d/<DEMO_DEVICE_ID>/mcp
```
