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
| Relay operator or relay compromise | The relay holds no token secrets and cannot mint tokens: every token is verified on the phone. It can still read traffic in transit, because MCP clients terminate TLS at the relay. **Self-host the relay** (one small Node process or Docker container). End-to-end encryption between agent and phone would need MCP client support, so it is future work. |
| Device impersonation at the relay | The device id is `base32(SHA-256(deviceSecret))[:26]`, so it is self-certifying. Only the holder of the 32-byte secret can register that id. Operators can also require `RELAY_ACCESS_KEY` so strangers cannot use the relay. |
| Malicious website hits the local-mode server (DNS rebinding / CSRF) | Local mode binds to `127.0.0.1` by default (reach it with `adb forward`). Requests with a non-allowlisted `Origin` header get `403`. A bearer token is still required. There is no CORS. |
| Other apps on the phone | Services are not exported, except system-bound ones protected by `BIND_*` permissions. Confirmation broadcasts use explicit, immutable `PendingIntent`s carrying a random nonce. Tokens and the device secret live in app-private storage. |
| Physical access to an unlocked phone | Out of scope. The app shows the pause switch, the full audit log and the active tokens. |

## Scopes

| scope | grants |
|---|---|
| `device` | device status, volume, ringer, flashlight, media keys, clipboard (write), TTS, alarms/timers |
| `apps` | list/open apps, open URLs and deep links, open a WhatsApp chat without sending |
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

Prompts appear as a high-priority notification with **Allow** and **Deny** buttons. Tapping the
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

Data scopes such as `contacts.read`, `messages.send` (SMS through the system API) and `calendar.*` are governed by **scopes**, not by app access, because they go through Android system APIs rather than an app's UI.

## Rate limits (per token, sliding 60 s window)

120 calls per minute in total, and 20 `SENSITIVE` calls per minute. Calls over the limit are
refused with an `isError` result and are audit-logged.

## Known platform limits

* Android doesn't let apps unlock the phone. Screen tools and `whatsapp_send` need it unlocked.
  Notification replies work while locked.
* In Doze, Android may delay network access for apps that aren't exempt from battery
  optimization. Grant the exemption in the setup screen. Push wakeups (FCM) are future work.
* Accessibility gives the app broad power over every app. That is how "control any app" works,
  and also why scopes and confirmation exist.
