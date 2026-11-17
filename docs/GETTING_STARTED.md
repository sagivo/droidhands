# Getting started

Three steps, about five minutes, all on the phone (Android 11 or newer):

1. Download and install the app.
2. Open it: the wizard grants the permissions, and the phone goes online by itself.
3. Connect your AI agent: pick it, tap **Add to Claude** (or copy one command), and tap **Allow** on
   the phone.

## 1. Install the app

On the phone, download **`DroidHands.apk`** from the project website or from the latest GitHub
release (*Releases › Latest › Assets › DroidHands.apk*), then open the downloaded file.

* Chrome warns "File might be harmful" for every APK. Tap **Download anyway**.
* The first time, Android asks whether your browser (or Files app) may install unknown apps. Tap
  **Settings**, turn on **Allow from this source**, go back, and tap **Install**.
* Google Play Protect may offer to scan the app or warn that it comes from an unknown developer.
  Scan it, or choose **Install anyway**.
* Optional: each release lists the APK's SHA-256 in `SHA256SUMS`, if you want to check the file.

DroidHands is not on the Play Store, so it tells you itself when a new version is out (see
[Updates](#updates)).

## 2. Open it and follow the wizard

The wizard has three short steps (four in builds without a default relay, which add **Connect**).
Every step can be skipped and reopened later from *Settings › About › Run setup again*.

1. **Welcome**: what agents can and can't do.
2. **Permissions**: **Grant all** asks for the regular permissions (notifications for the Allow/Deny
   prompts, contacts, SMS, phone, calendar, location; Android asks about each). Then the special
   accesses, one at a time, each with one button that opens the right system screen: accessibility
   (to operate apps for you), notification access, battery (so the phone stays reachable), and two
   optional ones.

   **Android 13 and newer** block accessibility and notification access (and Android 15 also SMS)
   for apps installed from a download until you allow restricted settings. The wizard notices and
   shows a card with the three steps first:

   1. Tap **Open Accessibility** and try to turn DroidHands on. Android says it's a restricted
      setting: tap **OK**.
   2. Tap **Open App info**, then the three-dot menu (top right), choose **Allow restricted
      settings** and confirm.
   3. Come back and turn on accessibility.

3. **Add an agent**: the same page as [step 3](#3-connect-your-ai-agent) below. The phone is
   already online through the app's **default relay**, so there is no Connect step; **More ways to
   connect** under it opens the other routes.

   About the relay: it forwards your agents' requests to the phone over HTTPS. It can't do
   anything by itself: every agent needs a token you approve on the phone. Whoever runs the relay
   could see the requests passing through, so for maximum privacy open **More ways to connect**:
   **Tailscale** (agents on your own devices reach the phone directly, no relay) or **Your own
   relay** ([self-hosting](#self-hosting-the-relay-optional-most-private)). The routes are compared
   in [CONNECTING.md](CONNECTING.md).

   Builds without a default relay (for example ones you build from source) have a **Connect** step
   before it, which asks for your relay or offers Tailscale.

## 3. Connect your AI agent

In DroidHands, tap **Connect an AI agent** (the wizard's last step is the same page) and pick your
agent:

| Agent | What to do |
|---|---|
| Claude (claude.ai, Desktop, mobile) | Tap **Add to Claude**. Claude opens with DroidHands filled in: tap *Add*, then *Connect*. |
| ChatGPT | Tap **Copy URL and open ChatGPT**, turn on *Developer mode* under *Advanced*, then *Create*, paste, choose OAuth. |
| Claude Code | Copy (or **Share** to your computer) `claude mcp add --transport http phone <URL>`, then `/mcp` › *Authenticate*. |
| Codex | `codex mcp add phone --url <URL>`. Sign-in opens in the browser (or `codex mcp login phone`). |
| Gemini CLI | `gemini mcp add --transport http -s user phone <URL>`, then `/mcp auth phone`. |
| Any other MCP client | Add a Streamable-HTTP server with your connection URL (OAuth sign-in). |

Setting up on a computer? Share the connection URL to it and open it there: the page has the same
one-click buttons and commands (plus **Add to Cursor**).

Your browser opens a page saying **"Tap Allow on your phone"** with a 4-letter code. If DroidHands
is open, the approval screen appears by itself; otherwise tap the notification. Check that the code
matches and choose what the agent gets (**Give Claude access**):

* **Everything**: every app and everything DroidHands can do, without asking you first.
* **Only what I choose**: *All apps* or just the apps you pick, and what it can do (read messages
  and notifications, read contacts and calendar, location, see the screen, use apps, send messages,
  make calls, change the calendar, device settings, and an advanced option for developers).

Optionally turn on **Ask me before it sends messages, calls or pays**. Then tap **Allow**. That's
it: from now on the agent acts inside what you gave it without asking, and anything else is
refused. Apps on your never-allow list (banking and password apps are pre-selected) stay blocked
even with Everything. For an agent you don't fully trust, pick **Only what I choose**.

Now ask your agent: *"Send a WhatsApp to Tania saying I'm running 10 minutes late."*

## Day to day

* **No prompts.** Inside the access you gave it, an agent just acts. While it works, a
  notification says which agent and what it is doing ("Sending WhatsApp to Tania Cohen"), with
  **Stop** and **Pause all**. Requests outside its access are refused, and the agent is told what
  to ask you to allow.
* **Approvals, if you want them.** With *Ask me before it sends messages, calls or pays* on (or an
  *Ask* rule on a tool), those actions pop up on the phone with the resolved recipient and text.
  Approving needs an unlocked phone; with no answer in 60 s, the action is denied.
* **Locked phone.** Screen-based actions such as WhatsApp sending show "Unlock your phone to let
  Claude …" and continue as soon as you unlock. Replies to notifications work while locked.
* **Agents.** Each connected agent shows when it was last active, its calls today, a 14-day
  usage chart and its most-used tools. **Access** opens the same screen as at pairing, so you can
  change its apps and what it can do (it applies to its next request). Under *Advanced* you can set
  per-tool Allow / Ask / Block rules (also for single app actions, grouped by app), active hours and
  expiry, or **Revoke** it. Revoking takes effect immediately.
* **Apps.** Every app on the phone with what agents can do in it: *Integration* (a community
  plugin), *Local plugin* or *Basic* (what DroidHands found by itself), each action with its risk.
  Tap an app for **Block for all agents**, **Request a better integration**, or **Create plugin
  draft** to write one yourself ([CONTRIBUTING.md](../CONTRIBUTING.md)).
* **Activity.** The full log of every request, filterable by agent and outcome.
* **Pause.** One switch in the app, or *Pause* in the DroidHands notification, stops all agents.
* **Security.** Settings → Security covers the global **never-allow apps** list (banking,
  password managers and authenticators are pre-selected), **hide one-time codes** (on by
  default), and *Revoke all agents*.

## Updates

Once a day the app asks GitHub whether a newer release is out (an anonymous request; nothing is
installed by itself). When there is one, Home shows **Update available: vX.Y.Z**. Tap
**Download**, open the file, and tap **Update**: your agents and settings stay. **Not now** hides
the card until the next release. Turn the check off in *Settings › About › Check for updates*.

## Self-hosting the relay (optional, most private)

The relay is a tiny stateless server that lets agents reach your phone from anywhere, even over
LTE or behind a router. Running your own means nobody else handles your agents' traffic. It needs
Node ≥ 20 or Docker, plus HTTPS in front of it if it's on the internet.

```bash
cd relay
npm ci && npm run build
PUBLIC_URL=https://relay.example.com RELAY_ACCESS_KEY=$(openssl rand -hex 16) npm start
```

On startup it prints a **setup link and a QR code**. Each release also publishes a Docker image
(`ghcr.io/sagivo/droidhands-relay`), and deploy configs are included for
[Fly.io](../relay/fly.toml) and [Render](../relay/render.yaml), as is a
[Dockerfile](../relay/Dockerfile). For TLS, put it behind Caddy
(`caddy reverse-proxy --from relay.example.com --to :8787`). See [relay/README.md](../relay/README.md).

No server? `./scripts/relay-tailscale.sh` runs the relay on your computer and publishes it over
Tailscale, for your tailnet only or, with `--public`, for cloud agents too
([CONNECTING.md](CONNECTING.md#on-your-computer-with-scriptsrelay-tailscalesh)).

Then point the app at it, either way:

* Open the setup link on the phone (or scan the QR code) and tap **Open in DroidHands**. The app
  asks "Connect to relay …?" and connects.
* Or in the app: the wizard's **More ways to connect** › Your own relay, or *Settings › How
  agents reach this phone* › Relay, and paste the URL and access key.

Agents you connected through the previous relay need its URL, so connect them again with the new
connection URL.

> If your agent and phone are on the same Tailscale network, you can skip the relay: turn on
> **Tailscale** (the wizard's *More ways to connect*, or Settings) and the phone serves agents on
> its tailnet address only ([CONNECTING.md](CONNECTING.md#tailscale-private-no-relay)). For USB,
> use the *local server* (*More ways to connect* › *Local only*).

## Developer setup over USB (scripts/setup-phone.sh)

For development, or to skip the wizard: enable *USB debugging* on the phone, plug it in, then run

```bash
./scripts/setup-phone.sh --relay https://relay.example.com --key <RELAY_ACCESS_KEY>
```

This builds and installs a debug build, grants every permission and special access
(accessibility, notification access, battery, Do Not Disturb, and Android 13+'s restricted
settings), connects it to your relay, and opens it. Without `--relay` it skips the relay step.

A debug build is signed with your own debug key, so it can't be installed over a released APK (or
the other way round): uninstall one first. Building and publishing signed releases is described in
[RELEASING.md](RELEASING.md).

## Troubleshooting

| Symptom | Fix |
|---|---|
| "App not installed" when installing the APK | Another DroidHands signed with a different key is installed (e.g. a debug build). Uninstall it first. |
| Accessibility toggle is greyed out ("Restricted setting") | Android 13+ protection for downloaded apps. Try to turn it on once, then open App info → ⋮ → *Allow restricted settings*, and try again. The wizard's card walks you through it. |
| "App was denied access to SMS" | The same Android 15 protection. Allow restricted settings as above, then App info → Permissions → SMS → Allow. |
| Agent says the accessibility service is stuck | After an app update Android sometimes leaves it half-connected. Turn DroidHands off and on in Settings → Accessibility. |
| Notifications show "Sensitive notification content hidden" | Android 15 hides content that it classifies as sensitive from apps like DroidHands. Replies still work. For SMS the agent uses `sms_list`, and for calls `call_status` / `call_answer`. |
| Agent can't reach the phone after a while | Exempt DroidHands from battery optimization (Settings → Permissions & access). Check that the relay shows the phone as connected. |
| "Device offline" | The app isn't connected to the relay. Open it and check the status card. |
| "This relay is full right now" | The relay reached its limit of connected phones. DroidHands keeps retrying; to connect now, use your own relay. |
| "Waiting for Tailscale" | The Tailscale route is on but the phone isn't on your tailnet. Open Tailscale and connect; if it's on, check that DroidHands isn't excluded in Tailscale's app settings. |
| claude.ai or ChatGPT can't reach the Tailscale URL | Expected: cloud agents can't reach your tailnet. Use the relay for them (both routes can be on). |
