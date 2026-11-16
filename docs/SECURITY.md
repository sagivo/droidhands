# DroidHands security model

DroidHands gives software running elsewhere the ability to act as you on your phone. The design
goal is that **the phone is the only authority**: every request is authenticated, checked against
the access you gave that agent, rate-limited, and logged on the device itself.

**The grant is the control.** You choose an agent's access once, when you connect it: **Everything**,
or **Only what I choose** (which apps, and what it can do there). After that DroidHands does not
interrupt you: requests inside the grant run without a prompt, and requests outside it are refused at
once. Approval prompts exist only where you ask for them (see [Access](#access-the-grant)). Recommend
**Only what I choose** for any agent you don't fully trust, and keep its apps and abilities to what
it needs.

## Threats considered

| Threat | Mitigation |
|---|---|
| Someone on the internet calls your MCP endpoint | 256-bit bearer tokens issued on the phone. Only `SHA-256(secret)` is stored, with constant-time comparison. Unknown, revoked or expired tokens get an identical `401`. |
| A token leaks from an agent's config | The agent's grant limits what the token can do (apps and abilities). Expiry. One-tap revoke on the phone. Per-token rate limits. An audit log on the phone. A global **pause** switch in the app and in the persistent notification, and **Stop** in the live activity notification. |
| The agent (LLM) is prompt-injected, e.g. by a message it reads | **The grant bounds the damage**: an agent can only use the apps and abilities it was given, and never the apps on the never-allow list. Inside its grant it acts without asking, so give an agent you don't fully trust **Only what I choose** (for example no *Send messages* or *Make calls*, and only the apps it needs). Optionally, "Ask me before it sends messages, calls or pays" shows the **resolved** action ("Send WhatsApp to Tania Cohen +972…: '…'") on the phone for every `SENSITIVE` tool; with no answer in 60 s, the request is denied. The live activity notification shows what the agent is doing, with **Stop**. Agents are told to treat text they read as data, not instructions. |
| Relay operator or relay compromise | The relay cannot mint tokens: every token is verified on the phone. It stores one secret (`DATA_DIR/relay-secret`, used only to sign OAuth client ids), and during a pairing it holds the new token in memory for at most 120 s, until the agent exchanges its code. It can still read traffic in transit, because MCP clients terminate TLS at the relay. **Self-host the relay** (one small Node process or Docker container). End-to-end encryption between agent and phone would need MCP client support, so it is future work. Release builds may come with a **default relay** run by whoever publishes them, so that a fresh install is online without setup; everything above applies to its operator. The app says so where it connects (onboarding and Settings), never replaces a relay the user configured, and switches to the user's own relay in one step. |
| Device impersonation at the relay | The device id is `base32(SHA-256(deviceSecret))[:26]`, so it is self-certifying. Only the holder of the 32-byte secret can register that id. Operators can also require `RELAY_ACCESS_KEY` so strangers cannot use the relay. |
| Malicious website hits the local-mode server (DNS rebinding / CSRF) | Local mode binds to `127.0.0.1` by default (reach it with `adb forward`); the Tailscale route binds only the phone's tailnet address ([CONNECTING.md](CONNECTING.md)). Requests with a non-allowlisted `Origin` header get `403`. A bearer token is still required. There is no CORS. The pairing endpoints answer only when the `Host` header names the phone on the address the connection arrived on (loopback names on loopback, the tailnet address or a `*.ts.net` name on Tailscale, the exact LAN address on the LAN), so a rebound DNS name gets `421` and no metadata ([PROTOCOL.md §4.1](PROTOCOL.md#41-one-tap-pairing-on-the-local-server)). |
| Someone who can reach the local server starts a pairing (another app on the phone, a machine on the LAN or tailnet) | Nothing is granted without **Allow** on the unlocked phone, on the same grant screen as relay pairings: client name, where it returns to, the requesting address and the 4-character code that the requester's browser also shows. At most 3 pending prompts (2 per client address) and 20 per hour (10 per address); per-address request and failure limits. Codes are single-use, valid 120 s, bound to the client, redirect URI and PKCE `S256` challenge, and compared in constant time; a token no agent picked up is deleted. Redirect URIs follow the relay's rules (no `javascript:`, `intent:`, plain-http non-loopback, …), and a bad request is shown as an error page, never redirected. |
| The token is sniffed during local pairing | Loopback (`adb forward`) never leaves the phone or USB link, and Tailscale is WireGuard-encrypted. Over the LAN the server speaks plain `http`, so the authorization code and the token cross the Wi-Fi unencrypted; the server listens there only after the user turned LAN access on. Use Tailscale or the relay on networks you don't control, and revoke an agent you paired on an untrusted network. |
| Other apps on the phone | Services are not exported, except system-bound ones protected by `BIND_*` permissions. Confirmation broadcasts use explicit, immutable `PendingIntent`s carrying a random nonce. Tokens and the device secret live in app-private storage. |
| Physical access to an unlocked phone | Out of scope. The app shows the pause switch, the full audit log and the active tokens. |

## Access (the grant)

The same screen, **"Give <agent> access"**, appears when you approve a pairing (relay or local server)
and when you create a token by hand. **Agents > (agent) > Access** opens it again, pre-filled; changes
apply to the agent's next request.

* **Everything**: every scope, every app (except the never-allow list), no approval prompts
  (`ConfirmPolicy.NEVER`, no risk cap). This is the default choice.
* **Only what I choose**:
  * **Apps**: *All apps* or *Only apps I choose* (a searchable list of installed apps), stored as the
    token's app access (`ALL` or `ALLOWLIST`).
  * **What it can do**: a short checklist; each item is a fixed set of scopes, and together they
    cover every scope:

    | Ability | Scopes |
    |---|---|
    | Read messages & notifications | `messages.read`, `notifications.read` |
    | Read contacts & calendar | `contacts.read`, `calendar.read` |
    | Location | `location` |
    | See the screen | `screen.read` |
    | Use apps (open apps and links, run app actions, tap, type, scroll) | `apps`, `screen.control` |
    | Send messages (SMS, WhatsApp, email; reply to, act on or dismiss notifications) | `messages.send`, `notifications.act` |
    | Make calls | `calls` |
    | Change calendar | `calendar.write` |
    | Device settings (volume, ringer, flashlight, media, alarms, clipboard, speech) | `device` |
    | Start any app screen (advanced) | `intents` |

    A new agent's checklist starts with the two *Read* items, *See the screen* and *Use apps*.
    Inside the choice there are no prompts either.
* **Ask me before it sends messages, calls or pays** (one switch, off by default): `ConfirmPolicy.SENSITIVE`,
  so every `SENSITIVE` tool and app action (sending, calling, notification replies and actions, raw
  intents, and plugin actions that pay, order, book or delete) waits for Allow on the phone.
* The never-allow list still overrides **Everything**; the screen says so and links to the list.
* The grant never creates per-tool rules.

**Outside the grant: refused, never prompted.** A tool whose scope the agent lacks is hidden from
`tools/list` and refused (`DENIED_SCOPE`); an app outside its apps is hidden and refused (`DENIED_APP`).
The error names what is missing and where the user can add it, for example *"Claude isn't allowed to
use WhatsApp (com.whatsapp). The user can allow it in DroidHands > Agents > Claude > Access."* or
*"Claude isn't allowed to make or answer calls ('phone_call' needs that). The user can allow it in
DroidHands > Agents > Claude > Access."* Both are audited as before. Agents learn their grant up front:
the MCP `initialize` instructions and `bridge_capabilities` (`token.access`) list their apps, what they
may and may not do, and that actions inside the grant run without asking the user.

**What still asks you, and why:**

* Only what you asked for: the switch above, an explicit **Ask** rule on a tool or app action in
  *Agents > (agent) > Advanced* (an *Ask* rule on `app_action_run` makes every app action ask), and
  agents created before the grant screen that keep *Ask for sensitive actions* or *Ask for every action*.
* **Unreviewed local plugins** (a developer option, off by default): their actions always ask, whatever
  the grant says, because nobody reviewed what they do (see "App plugins").
* **"Unlock your phone to let <agent> …"**: Android does not let apps unlock the phone, so screen tools
  wait for you to unlock. This is a platform requirement, not an approval:
  unlocking doesn't approve anything, and the call is still limited by the grant.

**Older tokens** keep exactly what they had (no migration). The editor shows them in the same terms: an
ability with only some of its scopes is shown as partly granted and stays so until you tick or clear it;
a *Read-only* risk cap, *Ask for every action* or an app blocklist each get their own row and are kept until
you change them.

## Scopes

| scope | grants |
|---|---|
| `device` | device status, volume, ringer, flashlight, media keys, clipboard (write), TTS, alarms/timers |
| `apps` | list/open apps, open URLs and deep links, open a WhatsApp chat without sending, list and run app actions (each action may need another scope too, see "App plugins") |
| `contacts.read` | search contacts |
| `messages.read` | read SMS and call log |
| `messages.send` | send SMS and WhatsApp messages |
| `calls` | place phone calls |
| `notifications.read` | read active notifications (including message previews from every app) |
| `notifications.act` | reply to, trigger actions on, and dismiss notifications |
| `calendar.read` / `calendar.write` | read / create calendar events |
| `location` | current location |
| `screen.read` | read the screen content of any app, take screenshots |
| `screen.control` | tap, type, scroll, swipe, global navigation in any app (effectively full control) |
| `intents` | start arbitrary Android activities (advanced; full power) |

The app no longer offers presets; agents created with the older presets **Read-only** (`device`, `apps`,
`contacts.read`, `messages.read`, `notifications.read`, `calendar.read`, `location`, `screen.read`, capped at
`READ` tools), **Assistant** (Read-only + `messages.send`, `calls`, `notifications.act`, `calendar.write`)
or **Full control** (all scopes) keep them, shown in the grant's terms.

## Confirmation policy (per token)

* `NEVER`: no prompts. What every grant gets unless the switch below is on.
* `SENSITIVE`: "Ask me before it sends messages, calls or pays": prompts for every `SENSITIVE` tool
  (sending messages, calls, notification replies and actions, raw intents, sensitive app actions).
  It was the default for agents connected before the grant screen, and they keep it.
* `ALL_ACTIONS`: prompts for every `ACTION` and `SENSITIVE` tool. Only older agents have it; the grant
  editor shows it and offers to narrow it to `SENSITIVE`.

Prompts appear as a high-priority notification with **Allow** and **Deny** buttons. Each prompt is
its own notification group, so Android never collapses it and hides the buttons. Tapping the
notification opens a screen with the full details. A tool that resolves its targets first
(`confirm: internal`) shows the resolved recipient and number.

## Advanced controls

These are set per agent (token) in **Agents > (agent) > Advanced**, except where marked *global* (app
access is part of the grant, in **Agents > (agent) > Access**).

| Control | Effect | Enforced in |
|---|---|---|
| **App access** (part of the grant) | `ALL` (all apps), `ALLOWLIST` (only the chosen apps), or, for agents created before the grant screen, `BLOCKLIST` (every app except the selected ones). | Tools, through `CallContext.appAccess` (see below) |
| **Never-allow apps** (*global*) | Apps no agent can touch, whatever its own app access, **Everything** included. First run pre-selects installed banking, payments, password-manager and authenticator apps (heuristics plus a known-package list). DroidHands itself is always blocked. Edited in Settings > Security > Never-allow apps, or per app with **Apps > (app) > Block for all agents** (turning that off asks first). | Same as app access |
| **Per-tool rules** | Each tool can be set to `ALLOW` (never ask, though still rate-limited), `ASK` (always confirm on the phone) or `BLOCK` (hidden from `tools/list` and refused). These override the confirmation policy. Only the user sets them, here; the grant never creates them. The same rules apply to single app actions (`<package>/<action>`, listed per app under *App action rules*); an action that runs a built-in tool offers only `BLOCK`, because that tool's own rule decides its approval. | `PolicyEngine` and `McpServer` (core) |
| **Active hours** | The agent may act only within a daily local-time window, for example 08:00–23:00 (an overnight window like 22:00–06:00 also works). Outside it, calls are refused with an explanation. | `PolicyEngine` (core) |
| **Hide one-time codes** (*global*, default ON) | Verification and 2FA codes are masked as `[code hidden]` in SMS and notification text before agents see them. | Tools, through `CallContext.redactOneTimeCodes`, using the core `OneTimeCodes.redact()` |

**What "app access" covers.** App access governs the *app-facing* tools:

* Screen reading and control: `screen_*` and `ui_*`. Refused while a blocked app is in the foreground. Windows of blocked apps are left out of `screen_observe` and `screen_read`, and no screenshot (marked or not) is taken while one is visible. `screen_screenshot` is refused if any visible window belongs to a blocked app, or the system recents screen is showing while the token has app restrictions.
* Notifications: notifications from blocked apps are filtered out of `notifications_list`, and `notification_reply` / `notification_action` / `notification_dismiss` refuse them.
* Opening apps: `app_open`, `url_open`, `intent_start`. Refused when the target or the resolved handling app is blocked.
* Contacts: `contacts_search` lists the apps a contact is on (`apps`), leaving out apps blocked for the agent.
* `apps_list` hides blocked apps.
* App actions: `app_actions_list` never lists blocked apps, and `app_action_run` refuses them (see "App
  plugins" below).

When Android would show its app chooser and some candidate apps are blocked, `url_open` and
`intent_start` pin the intent to the single allowed app, or refuse and list the allowed apps. The
chooser is never shown with blocked apps in it. After a `ui_*` action that brings a blocked app to the
foreground, DroidHands presses Home and the call fails. Under app restrictions, screenshots are also
refused on the lock screen, in Quick Settings, and while any blocked app's notification is visible
(for example a heads-up). System UI, the home app, keyboards, framework dialogs, the chooser and
permission dialogs are part of the OS, not apps. They stay usable under an allowlist unless named in
a blocklist or the never-allow list.

Approval and pairing screens use `FLAG_SECURE`, hide other apps' overlays (Android 12+) and ignore
obscured touches. DroidHands's own screens and notification buttons cannot be read or tapped by
its screen tools, so an agent cannot approve its own requests.

Data scopes such as `contacts.read`, `messages.send` (SMS through the system API) and `calendar.*` are governed by **scopes**, not by app access, because they go through Android system APIs rather than an app's UI.

## Stopping agents, and keeping the record

* **Pause, Revoke, Revoke all and Delete stop work that is already running.** Calls waiting for an
  unlock, an approval or the screen are cancelled. Their prompts are withdrawn, and each call is audited
  as `CANCELLED`. Tools re-check pause and token state after every wait and right before each side effect.
* **You see when an agent is working.** With no prompts inside the grant, this is how you follow what agents
  do. While a tool call runs, one notification says which agent and
  what it is doing ("Claude Code is using your phone" / "Sending WhatsApp to Tania Cohen"), then
  *Approval needed* while a prompt you asked for is open, then Done, Failed or Stopped for 4 s. Read-only calls
  appear after 1.5 s, anything that changes something at once. On Android 16 it asks to be a Live
  Update with a status-bar chip. Its **Stop** ends that agent's running calls (prompts withdrawn,
  audited `CANCELLED` "stopped by the user"; the agent keeps its access, and is told not to retry
  unless asked); **Pause all** is the global pause. It shows only the agent's name, the tool's title
  and a line the tool itself reports about its target (a recipient), never arguments, message text,
  subjects or codes; that line is cleaned like prompt text and passes through *Hide one-time codes*
  when it is on. It is `VISIBILITY_PRIVATE` (the lock screen shows only "An agent is using your
  phone"), silent, local-only, and can be turned off (*Settings* › "Show when agents are working").
  Like every DroidHands notification it is hidden from `screen_read` and its buttons can't be tapped
  by screen tools, but it does not pause screen control: only approval and pairing prompts do.
* **No self-approval.** While any approval or pairing prompt is pending or on screen, every screen-control
  gesture is refused. The check runs right before each tap, and hit-testing fails closed on windows that
  Android hides from accessibility. DroidHands's own notifications (including heads-up prompts) are removed
  from `screen_read` output, so an agent can neither see nor tap another agent's prompt.
* **Read-only means read-only.** Agents created with the older *Read-only* preset are capped at `READ` tools
  (`maxRisk`). Tools that change anything are hidden and refused, whatever the scopes say, until the user
  removes the limit in *Access*.
* **Phone codes are never dialed.** `phone_call`, `sms_send` and WhatsApp recipients must be plain numbers.
  USSD/MMI strings such as `*21*…#` (call forwarding) are refused.
* **Recipients aren't guessed.** A contact is auto-picked only on an exact full-name match, or a unique
  whole-word match that no other contact shares as a prefix ("Dan" does not pick "Danielle").
* **One-time codes** are masked across a notification's title, text and messages together, and in
  `screen_read` text. Screenshots are refused while a code is visible.
* **The audit log can't be flushed.** Refusals before anything runs (bad token, unknown tool, scope or
  policy denials, rate limits) are written verbatim only up to a small per-source budget. Beyond that they
  are summarized as "N more requests like this", so junk calls can't push real entries out of the log.
* **Calls survive a network switch.** A dropped relay connection no longer cancels a running tool. It
  finishes and is audited, and only its response is lost. Agents are told the phone may already have done
  the action, and to check before resending.

## App plugins

Plugins ([PLUGINS.md](PLUGINS.md)) are data, not code: a YAML file of intents, links, screen recipes and
aliases of built-in tools, loaded with a safe YAML parser and checked by a validator at build time and
on import. They never widen what an agent may do:

* Each app action is checked as a tool of its own, `<package>/<action id>`, against the agent's scopes,
  risk cap, per-tool rules, active hours and rate limits, and audited under that name. Inside the grant it
  runs without asking, unless the agent's approval setting or an *Ask* rule says otherwise. `app_action_run` itself needs `apps` and must not be blocked or above the risk cap; an ASK rule on it
  makes every action ask first.
  Actions that alias a built-in tool run exactly as that tool, with its scope, approval and audit entry.
* App access applies first: blocked apps' actions are hidden and refused (`DENIED_APP`).
* Intents and links are pinned to the app's package, call intents and dangerous URL schemes are refused
  (again at run time), and screen recipes act only while that app is in the foreground.
* The validator enforces risk floors: sending, paying, ordering, booking, calling, deleting and similar
  actions must be `sensitive`, so "Ask me before it sends messages, calls or pays" covers them.
* Local plugins, imported on the phone for testing, are off by default. Their actions always need
  approval, even under ALLOW rules or the "never ask" policy, and the prompt says "unreviewed plugin" and
  shows what the recipe will really do.

The full rules are in [PLUGINS.md §9](PLUGINS.md#9-security-rules).

## Rate limits (per token, sliding 60 s window)

120 calls per minute in total, and 20 `SENSITIVE` calls per minute. Calls over the limit are
refused with an `isError` result and are audit-logged.

## Known platform limits

* Android doesn't let apps unlock the phone. Screen tools need it
  unlocked, so they post *"Unlock your phone to let <agent> <action>"* and continue as soon as you
  unlock (or fail after 60 s). This is a platform requirement, not an approval prompt. Notification
  replies work while locked.
* Android 15+ hides the content of notifications it classifies as sensitive (and sometimes their
  action buttons) from notification listeners. Replies still work; SMS are read via `sms_list`, and
  calls are handled with `call_status` / `call_answer` / `call_end`.
* Screen tools show whatever is on screen. *Hide one-time codes* covers SMS and notification text,
  but not codes visible inside an app's UI. Block your SMS and authenticator apps for agents that
  have screen access.
* In Doze, Android may delay network access for apps that aren't exempt from battery
  optimization. Grant the exemption in the setup screen. Push wakeups (FCM) are future work.
* *Scan QR code* (relay setup) uses Google Play services' code scanner: Play services shows the
  camera and hands DroidHands only the decoded text, so DroidHands has no camera permission. The
  ML Kit client library it brings may send Google anonymous usage and diagnostics data under Google's
  ML Kit terms. A scanned code is never applied without the "Connect to relay?" confirmation.
* Accessibility gives the app broad power over every app. That is how "control any app" works,
  and also why each agent's grant (apps and abilities) and the never-allow list exist.

## Reporting a vulnerability

Please report security problems privately, not in a public issue: use **Security → Report a vulnerability**
on the project's GitHub page (GitHub's private vulnerability reporting), with what is affected (app, core,
relay or a plugin), how to reproduce it and what an attacker could do. You'll get an answer as soon as a
maintainer can look at it, and credit in the release notes if you want it. Please give us a reasonable time to
ship a fix before you publish details.

Unreviewed local plugins are trusted by whoever imports them, and the Android platform limits above are
known; reports that show a way around them (an agent approving its own request, reaching a blocked app,
escaping a plugin's app, acting while paused or after a revoke) are very welcome.
