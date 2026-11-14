# PhoneBridge security model

PhoneBridge gives software running elsewhere the ability to act as you on your phone. The design
goal is that **the phone is the only authority**: every request is authenticated, scoped,
rate-limited, optionally approved by you, and logged on the device itself.

## Threats considered

| Threat | Mitigation |
|---|---|
| Someone on the internet calls your MCP endpoint | 256-bit bearer tokens issued on the phone. Only `SHA-256(secret)` is stored, with constant-time comparison. Unknown, revoked or expired tokens get an identical `401`. |
| A token leaks from an agent's config | Scopes limit what it can do. Expiry. One-tap revoke on the phone. Per-token rate limits. On-phone confirmation for sensitive actions (default). An audit log on the phone. A global **pause** switch in the app and in the persistent notification. |
| The agent (LLM) is prompt-injected, e.g. by a message it reads | The confirmation policy shows the **resolved** action ("Send WhatsApp to Tania Cohen +972…: '…'") on the phone for every `SENSITIVE` tool. The user taps Allow or Deny. With no answer in 60 s, the request is denied. |
| Relay operator or relay compromise | The relay cannot mint tokens: every token is verified on the phone. It stores one secret (`DATA_DIR/relay-secret`, used only to sign OAuth client ids), and during a pairing it holds the new token in memory for at most 120 s, until the agent exchanges its code. It can still read traffic in transit, because MCP clients terminate TLS at the relay. **Self-host the relay** (one small Node process or Docker container). End-to-end encryption between agent and phone would need MCP client support, so it is future work. |
| Device impersonation at the relay | The device id is `base32(SHA-256(deviceSecret))[:26]`, so it is self-certifying. Only the holder of the 32-byte secret can register that id. Operators can also require `RELAY_ACCESS_KEY` so strangers cannot use the relay. |
| Malicious website hits the local-mode server (DNS rebinding / CSRF) | Local mode binds to `127.0.0.1` by default (reach it with `adb forward`). Requests with a non-allowlisted `Origin` header get `403`. A bearer token is still required. There is no CORS. |
| Other apps on the phone | Services are not exported, except system-bound ones protected by `BIND_*` permissions. Confirmation broadcasts use explicit, immutable `PendingIntent`s carrying a random nonce. Tokens and the device secret live in app-private storage. |
| Physical access to an unlocked phone | Out of scope. The app shows the pause switch, the full audit log and the active tokens. |

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

Presets offered in the app: **Read-only** (`device`, `apps`, `contacts.read`,
`messages.read`, `notifications.read`, `calendar.read`, `location`, `screen.read`), **Assistant**
(Read-only + `messages.send`, `calls`, `notifications.act`, `calendar.write`), and **Full control**
(all scopes).

## Confirmation policy (per token)

* `NEVER`: no prompts. Use only for tokens you fully trust.
* `SENSITIVE` (default): prompts for every `SENSITIVE` tool (sending messages, calls, notification
  replies and actions, raw intents).
* `ALL_ACTIONS`: prompts for every `ACTION` and `SENSITIVE` tool.

Prompts appear as a high-priority notification with **Allow** and **Deny** buttons. Each prompt is
its own notification group, so Android never collapses it and hides the buttons. Tapping the
notification opens a screen with the full details. A tool that resolves its targets first
(`confirm: internal`) shows the resolved recipient and number.

## Advanced controls

These are set per agent (token) in **Agents > (agent) > Advanced**, except where marked *global*.

| Control | Effect | Enforced in |
|---|---|---|
| **App access** | `ALL` (default), `ALLOWLIST` (only the selected apps), or `BLOCKLIST` (every app except the selected ones). | Tools, through `CallContext.appAccess` (see below) |
| **Never-allow apps** (*global*) | Apps no agent can touch, whatever its own app-access setting. First run pre-selects installed banking, payments, password-manager and authenticator apps (heuristics plus a known-package list). PhoneBridge itself is always blocked. | Same as app access |
| **Per-tool rules** | Each tool can be set to `ALLOW` (never ask, though still rate-limited), `ASK` (always confirm on the phone) or `BLOCK` (hidden from `tools/list` and refused). These override the confirmation policy. | `PolicyEngine` and `McpServer` (core) |
| **Active hours** | The agent may act only within a daily local-time window, for example 08:00–23:00 (an overnight window like 22:00–06:00 also works). Outside it, calls are refused with an explanation. | `PolicyEngine` (core) |
| **Hide one-time codes** (*global*, default ON) | Verification and 2FA codes are masked as `[code hidden]` in SMS and notification text before agents see them. | Tools, through `CallContext.redactOneTimeCodes`, using the core `OneTimeCodes.redact()` |

**What "app access" covers.** App access governs the *app-facing* tools:

* Screen reading and control: `screen_*` and `ui_*`. Refused while a blocked app is in the foreground. Windows of blocked apps are left out of `screen_read`. `screen_screenshot` is refused if any visible window belongs to a blocked app, or the system recents screen is showing while the token has app restrictions.
* Notifications: notifications from blocked apps are filtered out of `notifications_list`, and `notification_reply` / `notification_action` / `notification_dismiss` refuse them.
* Opening apps: `app_open`, `url_open`, `intent_start`, and `whatsapp_*` / `email_send`. Refused when the target or the resolved handling app is blocked.
* `apps_list` hides blocked apps.
* App actions: `app_actions_list` never lists blocked apps, and `app_action_run` refuses them (see "App
  plugins" below).

When Android would show its app chooser and some candidate apps are blocked, `url_open` and
`intent_start` pin the intent to the single allowed app, or refuse and list the allowed apps. The
chooser is never shown with blocked apps in it. After a `ui_*` action that brings a blocked app to the
foreground, PhoneBridge presses Home and the call fails. Under app restrictions, screenshots are also
refused on the lock screen, in Quick Settings, and while any blocked app's notification is visible
(for example a heads-up). System UI, the home app, keyboards, framework dialogs, the chooser and
permission dialogs are part of the OS, not apps. They stay usable under an allowlist unless named in
a blocklist or the never-allow list.

Approval and pairing screens use `FLAG_SECURE`, hide other apps' overlays (Android 12+) and ignore
obscured touches. PhoneBridge's own screens and notification buttons cannot be read or tapped by
its screen tools, so an agent cannot approve its own requests.

Data scopes such as `contacts.read`, `messages.send` (SMS through the system API) and `calendar.*` are governed by **scopes**, not by app access, because they go through Android system APIs rather than an app's UI.

## Stopping agents, and keeping the record

* **Pause, Revoke, Revoke all and Delete stop work that is already running.** Calls waiting for an
  unlock, an approval or the screen are cancelled. Their prompts are withdrawn, and each call is audited
  as `CANCELLED`. Tools re-check pause and token state after every wait and right before each side effect.
* **No self-approval.** While any approval or pairing prompt is pending or on screen, every screen-control
  gesture is refused. The check runs right before each tap, and hit-testing fails closed on windows that
  Android hides from accessibility. PhoneBridge's own notifications (including heads-up prompts) are removed
  from `screen_read` output, so an agent can neither see nor tap another agent's prompt.
* **Read-only means read-only.** The *Read-only* preset caps the agent at `READ` tools (`maxRisk`). Tools
  that change anything are hidden and refused, whatever the scopes say.
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
  risk cap, per-tool rules, active hours and rate limits, approved according to its risk and audited
  under that name. `app_action_run` itself needs `apps` and must not be blocked or above the risk cap.
  Actions that alias a built-in tool run exactly as that tool, with its scope, approval and audit entry.
* App access applies first: blocked apps' actions are hidden and refused (`DENIED_APP`).
* Intents and links are pinned to the app's package, call intents and dangerous URL schemes are refused
  (again at run time), and screen recipes act only while that app is in the foreground.
* The validator enforces risk floors: sending, paying, ordering, booking, calling, deleting and similar
  actions must be `sensitive`, so the default policy asks before they run.
* Local plugins, imported on the phone for testing, are off by default. Their actions always need
  approval, even under ALLOW rules or the "never ask" policy, and the prompt says "unreviewed plugin" and
  shows what the recipe will really do.

The full rules are in [PLUGINS.md §9](PLUGINS.md#9-security-rules).

## Rate limits (per token, sliding 60 s window)

120 calls per minute in total, and 20 `SENSITIVE` calls per minute. Calls over the limit are
refused with an `isError` result and are audit-logged.

## Known platform limits

* Android doesn't let apps unlock the phone. Screen tools, `whatsapp_send` and `email_send` need it
  unlocked, so they post *"Unlock your phone to let <agent> <action>"* and continue as soon as you
  unlock (or fail after 60 s). Notification replies work while locked.
* Android 15+ hides the content of notifications it classifies as sensitive (and sometimes their
  action buttons) from notification listeners. Replies still work; SMS are read via `sms_list`, and
  calls are handled with `call_status` / `call_answer` / `call_end`.
* Screen tools show whatever is on screen. *Hide one-time codes* covers SMS and notification text,
  but not codes visible inside an app's UI. Block your SMS and authenticator apps for agents that
  have screen access.
* In Doze, Android may delay network access for apps that aren't exempt from battery
  optimization. Grant the exemption in the setup screen. Push wakeups (FCM) are future work.
* Accessibility gives the app broad power over every app. That is how "control any app" works,
  and also why scopes and confirmation exist.
