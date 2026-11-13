# PhoneBridge tool catalog (platform-neutral)

Tool names, arguments and semantics are **stable public API** shared by every platform. A platform
may omit tools it cannot support, but MUST NOT change what a name means. Arguments are `snake_case`.
Results are text content (usually compact JSON) unless noted.

Every tool has:
* **scope**: the permission a token needs (see [SECURITY.md](SECURITY.md)). A token never sees
  tools outside its scopes in `tools/list`.
* **risk**: `READ` (no side effects), `ACTION` (local side effects, reversible), or `SENSITIVE`
  (acts on your behalf toward other people or the outside world, or is hard to undo).
  The token's confirmation policy decides which risks need on-phone approval:
  `NEVER`, `SENSITIVE` (default), or `ALL_ACTIONS`.
* **confirm**: `pre` means the server asks for approval before running the tool, using a summary
  built from the raw arguments. `internal` means the tool first resolves its targets (for example
  the contact name to a number), then asks for approval with the *resolved* details before any
  side effect.

Contact resolution: tools that accept `contact` (a name) resolve it with the same logic as
`contacts_search`. If zero or several contacts match, the tool fails with an error that lists the
candidates, so the agent can ask the user. It never guesses.

## Meta

| tool | scope | risk | args | notes |
|---|---|---|---|---|
| `bridge_capabilities` | *(none, always allowed)* | READ | – | The token's name and scopes, and each visible tool with its availability (for example a missing permission, accessibility off, or phone locked). Agents should call it when unsure. |

## Device — scope `device`

| tool | risk | args |
|---|---|---|
| `device_status` | READ | – → battery %, charging, model, OS version, screen on, locked, network, ringer mode, volumes, timezone/local time, free storage, foreground app (if accessibility is on) |
| `flashlight_set` | ACTION | `on`: bool (req) |
| `volume_set` | ACTION | `stream`: `music\|ring\|alarm\|notification\|call` (req), `percent`: 0-100 (req) |
| `ringer_mode_set` | ACTION | `mode`: `normal\|vibrate\|silent` (req) |
| `media_control` | ACTION | `action`: `play\|pause\|play_pause\|next\|previous\|stop` (req) |
| `clipboard_set` | ACTION | `text` (req) |
| `speak` | ACTION | `text` (req), `language` (BCP-47, opt) – text-to-speech out loud on the phone |
| `alarm_set` | ACTION | `hour` 0-23 (req), `minute` 0-59 (req), `label`, `days`: [`mon`..`sun`] |
| `timer_set` | ACTION | `seconds` 1-86400 (req), `label` |

## Apps — scope `apps`

| tool | risk | args |
|---|---|---|
| `apps_list` | READ | `query` (substring filter), `include_system` bool → `[{package,label,version}]` launchable apps |
| `app_open` | ACTION | `package` or `name` (fuzzy app-label match; ambiguous → error with candidates) |
| `url_open` | ACTION | `url` (req; `http`, `https`, `geo`, `mailto`, `market`, `whatsapp`, `tg` and other app deep links. `intent:`, `file:`, `content:`, `javascript:` are rejected), `package` (opt) |
| `whatsapp_open_chat` | ACTION | `to` (phone) or `contact` (name), `business` bool – opens the chat without sending anything |

## Intents — scope `intents` (advanced)

| tool | risk | args |
|---|---|---|
| `intent_start` | SENSITIVE (pre) | `action` (req), `data`, `mime_type`, `package`, `class_name`, `categories`: [str], `extras`: object of string/number/bool – starts an activity |

## Contacts — scope `contacts.read`

| tool | risk | args |
|---|---|---|
| `contacts_search` | READ | `query` (req; name, part of name, phone, or email), `limit` (default 10) → `[{id,name,phones:[{number,type,normalized}],emails,whatsapp:[{number,jid}],starred}]`. Exact and prefix matches come first, then fuzzy and transliteration-tolerant matches. |

## Messages — scopes `messages.read`, `messages.send`, `calls`

| tool | scope | risk | args |
|---|---|---|---|
| `sms_list` | messages.read | READ | `box`: `inbox\|sent\|all` (default all), `address` or `contact`, `query` (text contains), `since_hours`, `limit` (default 20, max 200) |
| `call_log_list` | messages.read | READ | `type`: `incoming\|outgoing\|missed\|all`, `address` or `contact`, `since_hours`, `limit` (default 20) |
| `sms_send` | messages.send | SENSITIVE (internal) | `to` (phone) or `contact` (name), `message` (req) → waits up to 30 s for the radio's sent status |
| `whatsapp_send` | messages.send | SENSITIVE (internal) | `to` (phone, international format preferred) or `contact` (name), `message` (req), `business` bool (use WhatsApp Business). Needs accessibility for the automatic Send tap. Without it, the chat opens with the text prefilled and the result says the user must tap Send. Fails if the phone is locked. |
| `phone_call` | calls | SENSITIVE (internal) | `to` (phone) or `contact` (name) – places a call |
| `email_send` | messages.send | SENSITIVE (internal) | `to` (email address(es), comma-separated) or `contact` (name, resolved to the contact's email), `cc`, `subject`, `body` (req), `app` (package, default: the default email app, preferring Gmail) – opens the app's compose screen pre-filled and taps Send with accessibility. Without accessibility, the compose screen is left open and the result says the user must tap Send. To reply to a received email: use `notification_reply` when the mail notification has an inline reply, otherwise `notification_action` "Reply" plus screen tools, or the mail app via screen tools. |

## Notifications — scopes `notifications.read`, `notifications.act`

Requires notification access. Replies through notifications work **even when the phone is
locked**, and work for any messaging app with a reply action (WhatsApp, Telegram, Signal,
Messages, Slack…).

| tool | scope | risk | args |
|---|---|---|---|
| `notifications_list` | notifications.read | READ | `package`, `query`, `include_ongoing` bool, `limit` (default 30) → `[{key,package,app,title,text,messages:[{sender,text,time}],time,actions:[{index,title,can_reply}],conversation}]` |
| `notification_reply` | notifications.act | SENSITIVE (internal) | `key` (req), `message` (req) |
| `notification_action` | notifications.act | SENSITIVE (pre) | `key` (req), `action` (title, case-insensitive) or `index` |
| `notification_dismiss` | notifications.act | ACTION | `key` (req) |

## Calendar — scopes `calendar.read`, `calendar.write`

| tool | scope | risk | args |
|---|---|---|---|
| `calendar_list` | calendar.read | READ | – → calendars `[{id,name,account,writable,primary}]` |
| `calendar_events_list` | calendar.read | READ | `from` (ISO-8601, default now), `to` (ISO-8601) or `days` (default 7), `query`, `limit` (default 50) |
| `calendar_event_create` | calendar.write | ACTION | `title` (req), `start` (ISO-8601, req), `end` or `duration_minutes` (default 60), `all_day`, `location`, `description`, `calendar_id` (default: primary writable) |

## Location — scope `location`

| tool | risk | args |
|---|---|---|
| `location_get` | READ | `accuracy`: `high\|balanced` (default balanced), `timeout_seconds` (default 20), `address` bool (reverse-geocode) → `{latitude,longitude,accuracy_m,time,provider,address?}` |

## Screen — scopes `screen.read`, `screen.control`

Generic control of **any** app through the platform's accessibility layer. Subject to the token's
**app access** (docs/SECURITY.md): refused while a blocked app is in the foreground. Blocked apps'
windows are omitted. Screenshots are refused when any blocked window is visible, and when the
notification shade or recents screen is showing while app restrictions apply. Element references
(`ref`, like `n12`) come from the most recent `screen_read`/`screen_find` and stay valid until the
next one.

| tool | scope | risk | args |
|---|---|---|---|
| `screen_read` | screen.read | READ | `include_all` bool (default: only meaningful nodes), `max_nodes` (default 300) → package, activity, and an indented tree `n12 Button "Send" [clickable] (x,y,w,h)` |
| `screen_find` | screen.read | READ | `text`, `view_id`, `description`, `clickable_only` → matching refs |
| `screen_screenshot` | screen.read | READ | `max_dimension` (default 1280), `format`: `jpeg\|png` → MCP image content |
| `ui_wait` | screen.read | READ | `text` and/or `package`, `timeout_ms` (default 5000, max 30000) → true/false and the current package |
| `ui_tap` | screen.control | ACTION | `ref` or `text` (visible text / content-description, case-insensitive) or `x`+`y`; `long_press` bool |
| `ui_type` | screen.control | ACTION | `text` (req), `ref` (default: focused field), `append` bool, `submit` bool (IME enter) |
| `ui_scroll` | screen.control | ACTION | `direction`: `up\|down\|left\|right` (req), `ref` (default: the largest scrollable) |
| `ui_swipe` | screen.control | ACTION | `x1,y1,x2,y2` (req), `duration_ms` (default 300) |
| `ui_global_action` | screen.control | ACTION | `action`: `back\|home\|recents\|notifications\|quick_settings\|lock_screen\|power_dialog` (req) |
