# Getting started

Three steps, about five minutes, all on the phone (Android 11 or newer):

1. Download and install the app.
2. Open it: the wizard grants the permissions, and the phone goes online by itself.
3. Connect your AI agent: paste one URL into it and tap **Allow** on the phone.

## 1. Install the app

On the phone, download **`PhoneBridge.apk`** from the project website or from the latest GitHub
release (*Releases › Latest › Assets › PhoneBridge.apk*), then open the downloaded file.

* Android asks whether your browser (or file manager) may install apps. Tap **Settings**, turn on
  **Allow from this source**, go back, and tap **Install**.
* Google Play Protect may offer to scan the app or warn that it comes from an unknown developer.
  Scan it, or choose **Install anyway**.
* Optional: each release lists the APK's SHA-256 in `SHA256SUMS`, if you want to check the file.

PhoneBridge is not on the Play Store, so it tells you itself when a new version is out (see
[Updates](#updates)).

## 2. Open it and follow the wizard

The wizard has four short steps. Every step can be skipped and reopened later from
*Settings › About › Run setup again*.

1. **Welcome**: what agents can and can't do.
2. **Permissions**: **Grant all** asks for the regular permissions (notifications for the Allow/Deny
   prompts, contacts, SMS, phone, calendar, location; Android asks about each). Then the special
   accesses, one at a time, each with one button that opens the right system screen: accessibility
   (to operate apps for you), notification access, battery (so the phone stays reachable), and two
   optional ones.

   **Android 13 and newer** block accessibility for apps installed from a download until you allow
   it. The wizard notices and shows a card with the three steps:

   1. Tap **Open Accessibility** and try to turn PhoneBridge on. Android says it's a restricted
      setting: tap **OK**.
   2. Tap **Open App info**, then the three-dot menu (top right), choose **Allow restricted
      settings** and confirm.
   3. Come back and turn on accessibility.

3. **Connect**: nothing to do. The app has already connected to its **default relay** and shows
   **You're online**. The relay forwards your agents' requests to the phone over HTTPS. It can't do
   anything by itself: every agent needs a token you approve on the phone. Whoever runs the relay
   could see the requests passing through, so for maximum privacy use **Use my own relay**
   ([self-hosting](#self-hosting-the-relay-optional-most-private)).

   Builds without a default relay (for example ones you build from source) ask for your relay here
   instead.
4. **Add an agent**: the same page as [step 3](#3-connect-your-ai-agent) below.

## 3. Connect your AI agent

In PhoneBridge, tap **Connect an AI agent** and copy **your connection URL**
(`https://<relay>/d/<id>/mcp`). Then:

| Agent | What to do |
|---|---|
| Claude.ai / Claude Desktop | Settings → Connectors → *Add custom connector* → paste the URL |
| Claude Code | `claude mcp add --transport http phone <URL>`, then run `/mcp` and choose *Authenticate* |
| ChatGPT | Settings → Connectors → Developer mode → *Create* → paste the URL |
| Any other MCP client | Add a Streamable-HTTP server with that URL (OAuth sign-in) |

Your browser opens a page saying **"Approve on your phone"** with a 4-letter code. On the
phone, check that the code matches, choose an access level (**Read-only**, **Assistant**
or **Full control**), and tap **Allow**. That's it.

Now ask your agent: *"Send a WhatsApp to Tania saying I'm running 10 minutes late."*

## Day to day

* **Approvals.** Sensitive actions (sending messages, calls, replies) pop up on the phone with
  the resolved recipient and text. Approving needs an unlocked phone. With no answer in 60 s,
  the action is denied.
* **Locked phone.** Screen-based actions such as WhatsApp sending show "Unlock your phone to let
  Claude …" and continue as soon as you unlock. Replies to notifications work while locked.
* **Agents.** Each connected agent shows when it was last active, its calls today, a 14-day
  usage chart and its most-used tools. You can change its access level, choose which apps it may
  touch, set per-tool Allow / Ask / Block rules (also for single app actions, grouped by app) and
  active hours, or **Revoke** it. Revoking takes effect immediately.
* **Apps.** Every app on the phone with what agents can do in it: *Integration* (a community
  plugin), *Local plugin* or *Basic* (what PhoneBridge found by itself), each action with its risk.
  Tap an app to **Block it for all agents**, request a better integration, or create a plugin
  draft to write one yourself ([CONTRIBUTING.md](../CONTRIBUTING.md)).
* **Activity.** The full log of every request, filterable by agent and outcome.
* **Pause.** One switch in the app, or *Pause* in the PhoneBridge notification, stops all agents.
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
(`ghcr.io/<owner>/phonebridge-relay`), and deploy configs are included for
[Fly.io](../relay/fly.toml) and [Render](../relay/render.yaml), as is a
[Dockerfile](../relay/Dockerfile). For TLS, put it behind Caddy
(`caddy reverse-proxy --from relay.example.com --to :8787`). See [relay/README.md](../relay/README.md).

Then point the app at it, either way:

* Open the setup link on the phone (or scan the QR code) and tap **Open in PhoneBridge**. The app
  asks "Connect to relay …?" and connects.
* Or in the app: the Connect step's **Use my own relay**, or *Settings › Connection*, and paste the
  URL and access key.

Agents you connected through the previous relay need its URL, so connect them again with the new
connection URL.

> If your agent and phone are on the same Tailscale network, or you only use USB, you can skip
> the relay and use the app's *local server* instead (Connect step › *Local only*).

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
| "App not installed" when installing the APK | Another PhoneBridge signed with a different key is installed (e.g. a debug build). Uninstall it first. |
| Accessibility toggle is greyed out ("Restricted setting") | Android 13+ protection for downloaded apps. Try to turn it on once, then open App info → ⋮ → *Allow restricted settings*, and try again. The wizard's card walks you through it. |
| Agent says the accessibility service is stuck | After an app update Android sometimes leaves it half-connected. Turn PhoneBridge off and on in Settings → Accessibility. |
| Notifications show "Sensitive notification content hidden" | Android 15 hides content that it classifies as sensitive from apps like PhoneBridge. Replies still work. For SMS the agent uses `sms_list`, and for calls `call_status` / `call_answer`. |
| Agent can't reach the phone after a while | Exempt PhoneBridge from battery optimization (Settings → Permissions & access). Check that the relay shows the phone as connected. |
| "Device offline" | The app isn't connected to the relay. Open it and check the status card. |
| "This relay is full right now" | The relay reached its limit of connected phones. PhoneBridge keeps retrying; to connect now, use your own relay. |
