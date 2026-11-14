# Getting started

Setup takes three steps, about 10 minutes:

1. Run the relay.
2. Install the app on your phone.
3. Paste one URL into your AI agent and tap **Allow** on the phone.

## 1. Run the relay (once)

The relay is a tiny stateless server that lets agents reach your phone from anywhere, even over
LTE or behind a router. It needs Node ≥ 20 or Docker, plus HTTPS in front of it if it's on the
internet.

```bash
cd relay
npm ci && npm run build
PUBLIC_URL=https://relay.example.com RELAY_ACCESS_KEY=$(openssl rand -hex 16) npm start
```

On startup it prints a **setup link and a QR code**. One-click deploy configs are included for
[Fly.io](../relay/fly.toml) and [Render](../relay/render.yaml), as is a [Dockerfile](../relay/Dockerfile).
For TLS, put it behind Caddy (`caddy reverse-proxy --from relay.example.com --to :8787`).
See [relay/README.md](../relay/README.md).

> If your agent and phone are on the same Tailscale network, or you only use USB, you can skip
> the relay and use the app's *local server* instead.

## 2. Install the app

**Option A: one command over USB** (developers). Enable *USB debugging* on the phone, plug it in, then run:

```bash
./scripts/setup-phone.sh --relay https://relay.example.com --key <RELAY_ACCESS_KEY>
```

This builds and installs the app, grants every permission and special access (accessibility,
notification access, battery, Do Not Disturb), connects it to your relay, and opens it.

**Option B: no computer.** Open the relay's setup link on the phone, or scan the QR code. Tap
**Install PhoneBridge**, then **Open in PhoneBridge**. The app walks you through each permission
with one tap per step.

> On Android 13+, sideloaded apps need one extra step before accessibility can be turned on.
> Open **App info → ⋮ → Allow restricted settings**. The app shows you where.

## 3. Connect your AI agent

In PhoneBridge, tap **Connect an AI agent** and copy **your connection URL**
(`https://relay.example.com/d/<id>/mcp`). Then:

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
  touch, set per-tool Allow / Ask / Block rules and active hours, or **Revoke** it. Revoking
  takes effect immediately.
* **Activity.** The full log of every request, filterable by agent and outcome.
* **Pause.** One switch in the app, or *Pause* in the PhoneBridge notification, stops all agents.
* **Security.** Settings → Security covers the global **never-allow apps** list (banking,
  password managers and authenticators are pre-selected), **hide one-time codes** (on by
  default), and *Revoke all agents*.

## Troubleshooting

| Symptom | Fix |
|---|---|
| Agent says the accessibility service is stuck | After an app update Android sometimes leaves it half-connected. Turn PhoneBridge off and on in Settings → Accessibility. |
| Accessibility toggle is greyed out | Android 13+ sideload protection. Open App info → ⋮ → *Allow restricted settings*, then try again. |
| Notifications show "Sensitive notification content hidden" | Android 15 hides content that it classifies as sensitive from apps like PhoneBridge. Replies still work. For SMS the agent uses `sms_list`, and for calls `call_status` / `call_answer`. |
| Agent can't reach the phone after a while | Exempt PhoneBridge from battery optimization (Settings → Permissions & access). Check that the relay shows the phone as connected. |
| "Device offline" | The app isn't connected to the relay. Open it and check the status card. |
