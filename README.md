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

* **Tokens are created on the phone**: 256-bit, shown once, stored only as a hash. Each one is
  revocable, can expire, and is limited to **scopes** (Read-only / Assistant / Full control, or
  pick scopes individually).
* **On-phone approval** of sensitive actions (default). Approval shows the *resolved* recipient and
  text, needs an unlocked phone, and auto-denies after 60 s.
* Per-token rate limits, a full **audit log** on the phone, and a global **pause** switch (in the
  app and in the notification).
* The relay is a stateless tunnel. It can't mint or verify tokens and stores nothing. Device ids
  are self-certifying, so nobody can impersonate your phone at the relay.

## Repository layout

| Path | What |
|---|---|
| `docs/` | Platform-neutral contracts: [PROTOCOL.md](docs/PROTOCOL.md), [TOOLS.md](docs/TOOLS.md), [SECURITY.md](docs/SECURITY.md), [ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| `relay/` | The relay (TypeScript / Node, one dependency). [relay/README.md](relay/README.md) |
| `clients/android/core` | Bridge core in pure Kotlin: MCP server, tokens, policy, audit, relay client, local HTTP server. Platform-neutral. |
| `clients/android/app` | The Android app: services, setup UI, tool implementations |

## Quick start

See **[docs/GETTING_STARTED.md](docs/GETTING_STARTED.md)**.
