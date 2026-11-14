# PhoneBridge

Let **your** AI agents (Claude, or any MCP client) operate **your** phone, securely.

> "Send a WhatsApp to Tania saying I'll be 10 minutes late."

The agent calls `contacts_search("Tania")`, then
`whatsapp_send(contact="Tania Cohen", message="I'll be 10 minutes late")`. Your phone shows
*"Claude wants to: Send WhatsApp to Tania Cohen (+972 54-…): 'I'll be 10 minutes late'"* with
**Allow / Deny**. On Allow, PhoneBridge opens the chat and taps Send.

PhoneBridge is an Android app (iOS later) that exposes the phone's capabilities as an
[MCP](https://modelcontextprotocol.io) server. Agents reach it through a small self-hosted relay
(works over LTE and behind NAT) or directly (USB / LAN / Tailscale).

```
AI agent ──MCP over HTTPS──► relay (yours) ◄──WebSocket── PhoneBridge app ──► WhatsApp, SMS, contacts,
         Bearer pb1.… token                 (outbound)                        calls, notifications, calendar,
                                                                              location, any app's UI…
```

## What agents can do

About 40 tools (full list: [docs/TOOLS.md](docs/TOOLS.md)):

* **People and messaging**: search contacts (fuzzy and transliteration-aware: "Tania" ≈ "Tanya" ≈ "טניה"),
  WhatsApp send / open chat, SMS send and read, place calls, read call log.
* **Notifications**: read every app's notifications, and **reply inline** (WhatsApp, Telegram, Signal,
  Messages…; works while the phone is locked), trigger actions, dismiss.
* **Any app**: read the screen as a compact tree, take screenshots, tap / type / scroll / swipe,
  back / home / recents. This is the generic fallback for everything without a dedicated tool.
* **Apps and device**: list and open apps and URLs, battery, network, volume, ringer, flashlight,
  media keys, clipboard, text-to-speech, alarms, timers.
* **Calendar** (list and create), **location**, raw Android **intents** (advanced).

## Security

The phone is the only authority ([docs/SECURITY.md](docs/SECURITY.md)):

* **One-tap pairing**: paste your connection URL into Claude.ai, Claude Code, ChatGPT or any MCP
  client, check the 4-letter code on the phone, choose an access level, and tap **Allow**. The token
  is minted on the phone (256-bit, stored only as a hash). Manual tokens are available too.
* **Per-agent control**: access level (Read-only / Assistant / Full control, or custom scopes),
  **which apps** it may see and operate, per-tool **Allow / Ask / Block** rules, **active hours**,
  expiry, and instant **revoke**. A global never-allow list pre-selects banking, password-manager
  and authenticator apps, and one-time codes are hidden from agents by default.
* **On-phone approval** of sensitive actions (default). Approval shows the *resolved* recipient and
  text, needs an unlocked phone, and auto-denies after 60 s.
* Per-token rate limits, a full **audit log** with per-agent usage charts, and a global **pause**
  switch (in the app and in the notification).
* The relay is a tunnel. It can't mint or verify tokens. Its only state is one secret file used to
  sign OAuth client ids, plus, during a pairing, the new token held in memory for at most 120 s. Device ids
  are self-certifying, so nobody can impersonate your phone at the relay.

## Repository layout

| Path | What |
|---|---|
| `docs/` | Platform-neutral contracts: [PROTOCOL.md](docs/PROTOCOL.md), [TOOLS.md](docs/TOOLS.md), [SECURITY.md](docs/SECURITY.md), [ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| `relay/` | The relay (TypeScript / Node; deps: `ws`, `qrcode`), including one-tap OAuth pairing. [relay/README.md](relay/README.md) |
| `scripts/setup-phone.sh` | One-command USB setup: install, grant every permission and special access, connect to the relay |
| `scripts/e2e/` | End-to-end scenarios (real MCP calls through the relay to a device/emulator) and the pairing flow test |
| `clients/android/fixtures` | Emulator-only stand-ins for WhatsApp and a mail app used by the E2E tests |
| `clients/android/core` | Bridge core in pure Kotlin: MCP server, tokens, policy, audit, relay client, local HTTP server. Platform-neutral. |
| `clients/android/app` | The Android app: services, setup UI, tool implementations |

## Quick start

See **[docs/GETTING_STARTED.md](docs/GETTING_STARTED.md)**.
