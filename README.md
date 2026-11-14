# PhoneBridge

Let **your** AI agents (Claude, or any MCP client) operate **your** phone, securely.

> "Send a WhatsApp to Tania saying I'll be 10 minutes late."

The agent calls `contacts_search("Tania")`, then
`whatsapp_send(contact="Tania Cohen", message="I'll be 10 minutes late")`. Your phone shows
*"Claude wants to: Send WhatsApp to Tania Cohen (+972 54-…): 'I'll be 10 minutes late'"* with
**Allow / Deny**. On Allow, PhoneBridge opens the chat and taps Send.

PhoneBridge is an Android app (iOS later) that exposes the phone's capabilities as an
[MCP](https://modelcontextprotocol.io) server. Agents reach it through a small relay (the one the
app comes with, or your own self-hosted one; works over LTE and behind NAT) or directly over
Tailscale, privately and without a relay (also USB / LAN). Routes combine; see
[docs/CONNECTING.md](docs/CONNECTING.md).

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
* **Ready-made actions for every app**: see below.

## Apps and plugins

Agents don't have to drive every app by tapping. PhoneBridge gives **every installed app** a menu of
actions, and the agent picks the app, then the action:

```
apps_list                          → Spotify (com.spotify.music): play, search, share_text, open, …
app_actions_list(app="Spotify")    → play(query), search(query), …  with JSON Schemas
app_action_run(app="Spotify", action="play", params={query: "lofi beats"})
```

* **Discovered actions, for every app**: PhoneBridge scans what each app declares (sharing text, its
  launcher shortcuts and App Actions, media and web search, maps links), so even apps nobody wrote
  anything for get `open` plus whatever they support.
* **Plugins**: one small YAML file per app in [`plugins/`](plugins), written by the community and
  reviewed in pull requests, adds the actions written for that app (deep links, intents and, if needed,
  screen recipes). They ship inside the app.
* **Same security as any tool**: each action is checked, approved (by its risk), rate-limited and
  audited as `<package>/<action>`; it is pinned to its app, and you can block an app or a single action
  for every agent or for one. The **Apps** tab shows every app and what agents can do in it.

**Add an app in 5 minutes, no code:** copy `plugins/_template`, fill it in, run
`./scripts/plugin-check.sh`, try it on your phone, open a pull request. See
[CONTRIBUTING.md](CONTRIBUTING.md#add-an-app-in-5-minutes) and the format reference,
[docs/PLUGINS.md](docs/PLUGINS.md).

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
| `docs/` | Platform-neutral contracts: [PROTOCOL.md](docs/PROTOCOL.md), [TOOLS.md](docs/TOOLS.md), [PLUGINS.md](docs/PLUGINS.md), [SECURITY.md](docs/SECURITY.md), [ARCHITECTURE.md](docs/ARCHITECTURE.md); how agents reach the phone: [CONNECTING.md](docs/CONNECTING.md) |
| `plugins/` | App plugins, one folder per app, bundled into the app ([plugins/README.md](plugins/README.md) lists them). `scripts/plugin-check.sh`, `plugin-scaffold.sh`, `plugin-push.sh` and `plugin-table.sh` help write them ([CONTRIBUTING.md](CONTRIBUTING.md)). |
| `relay/` | The relay (TypeScript / Node; deps: `ws`, `qrcode`), including one-tap OAuth pairing. [relay/README.md](relay/README.md) |
| `scripts/setup-phone.sh` | One-command USB setup: install, grant every permission and special access, connect to the relay |
| `scripts/relay-tailscale.sh` | Run your own relay on your computer, published over Tailscale (tailnet only, or `--public` with Funnel) |
| `scripts/make-release-key.sh`, `.github/workflows/` | Release signing key, CI (tests and builds on every push) and releases (signed APK and relay image for every `v*` tag). [docs/RELEASING.md](docs/RELEASING.md) |
| `scripts/e2e/` | End-to-end scenarios (real MCP calls through the relay to a device/emulator) and the pairing flow test |
| `clients/android/fixtures` | Emulator-only stand-ins for WhatsApp and a mail app used by the E2E tests |
| `clients/android/core` | Bridge core in pure Kotlin: MCP server, tokens, policy, audit, relay client, local HTTP server. Platform-neutral. |
| `clients/android/app` | The Android app: services, setup UI, tool implementations |

## Quick start

No computer needed:

1. **Install.** On your Android phone (11 or newer), download **`PhoneBridge.apk`** from the project
   site or the [latest GitHub release](../../releases/latest), allow your browser to install apps,
   and install it.
2. **Open it.** The setup wizard walks you through the permissions (including Android 13+'s
   "Allow restricted settings" step for downloaded apps), and the phone goes online by itself
   through the app's default relay.
3. **Connect an agent.** Tap **Connect an AI agent**, paste the URL into Claude, ChatGPT or any MCP
   client, check that the code matches, and tap **Allow**.

Details, **self-hosting the relay** (the most private option) and the **developer setup over USB**
are in **[docs/GETTING_STARTED.md](docs/GETTING_STARTED.md)**. Other ways to connect (Tailscale
without a relay, your own relay on your computer) are compared in
**[docs/CONNECTING.md](docs/CONNECTING.md)**. Publishing your own builds:
[docs/RELEASING.md](docs/RELEASING.md).

## Contributing

App plugins and code are both welcome: [CONTRIBUTING.md](CONTRIBUTING.md). Please report security problems
privately ([docs/SECURITY.md](docs/SECURITY.md#reporting-a-vulnerability)).

## License

Apache License 2.0, see [LICENSE](LICENSE).
