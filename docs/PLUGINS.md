# App plugins

PhoneBridge gives agents a menu of **actions for every app on the phone**. An agent picks the app,
then the action, and PhoneBridge runs it:

```
apps_list                      → [{label: "Spotify", package: "com.spotify.music", actions: ["play", "search", "open", …]}, …]
app_actions_list(app="Spotify") → play(query), search(query), open(), share_text(text)  (with JSON Schemas)
app_action_run(app="Spotify", action="play", params={query: "lofi beats"})
```

Actions come from two places:

1. **Plugins**: one small YAML file per app, written by the community and reviewed in pull requests.
   They live in [`plugins/<id>/plugin.yaml`](../plugins), ship inside the app, and are written for each app
   (deep links, intents and, if needed, step-by-step screen recipes).
2. **Discovered actions**: PhoneBridge scans every installed app for the standard things Android apps
   declare (sharing text, launcher shortcuts, App Actions capabilities, media search, web search, maps),
   so even apps without a plugin get `open` plus whatever they support.

Anything else still works through the generic screen tools (`screen_read`, `ui_tap`, …). Plugins make
the common tasks fast, reliable and safe to approve.

**Adding an app takes one folder and no code.** See [CONTRIBUTING.md](../CONTRIBUTING.md) for the
5-minute walkthrough. This document is the format reference. The format is platform-neutral: each
plugin has an `android:` section today and will get an `ios:` section later.

---

## 1. Files

```
plugins/
  plugin.schema.json        JSON Schema for editor autocompletion (the validator below is authoritative)
  _template/plugin.yaml     copy this to start
  spotify/plugin.yaml       one folder per app; folder name == plugin id
  spotify/README.md         optional: notes, screenshots, known issues
```

Every `plugin.yaml` starts with
`# yaml-language-server: $schema=../plugin.schema.json`, so VS Code and other editors give
completion and inline errors.

`./scripts/plugin-check.sh` (which runs `./gradlew :core:checkPlugins`) validates every plugin and
runs its examples (§7) on your computer, with no phone needed. CI runs it on every pull request.

## 2. A complete example

```yaml
# yaml-language-server: $schema=../plugin.schema.json
id: spotify
name: Spotify
description: Music and podcast streaming.
category: music
homepage: https://www.spotify.com
status: experimental              # stable | beta | experimental (default)
android:
  packages: [com.spotify.music]

actions:
  - id: play
    title: Play music
    description: >-
      Searches Spotify and starts playing the best match: a song, artist, album, playlist or podcast.
    keywords: [music, song, listen, playlist, podcast]
    risk: action
    params:
      query:
        description: What to play, e.g. "Bohemian Rhapsody" or "lofi beats playlist"
        required: true
        max_length: 200
    confirm: Play "{{query}}" on Spotify
    completes: true
    android:
      intent:
        action: android.media.action.MEDIA_PLAY_FROM_SEARCH
        extras:
          query: "{{query}}"
          android.intent.extra.focus: vnd.android.cursor.item/*
    examples:
      - params: {query: lofi beats}
        expect:
          intent:
            action: android.media.action.MEDIA_PLAY_FROM_SEARCH
            package: com.spotify.music
            extras: {query: lofi beats, android.intent.extra.focus: vnd.android.cursor.item/*}

  - id: search
    title: Search
    description: Opens Spotify's search results for a query, without playing anything.
    risk: action
    params:
      query: {description: Search text, required: true}
    android:
      url: "spotify:search:{{query}}"
    examples:
      - params: {query: daft punk}
        expect: {url: "spotify:search:daft%20punk"}
```

## 3. Plugin fields

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | `^[a-z][a-z0-9_]{1,39}$`. Must equal the folder name. |
| `name` | yes | The app's name as users know it (≤ 60 chars). |
| `description` | yes | One sentence about the app (≤ 300 chars). |
| `category` | yes | One of `messaging`, `email`, `social`, `music`, `video`, `maps`, `travel`, `rides`, `food`, `shopping`, `productivity`, `browser`, `news`, `photos`, `health`, `system`, `other`. |
| `homepage` | no | `https://` URL. |
| `status` | no | `stable` (verified on a real device, see `tested`), `beta`, or `experimental` (default). Agents are told when an integration is experimental. |
| `maintainers` | no | GitHub handles. |
| `tested` | no | List of `{app_version, android, date, by}` records from people who ran every action on a device. |
| `android.packages` | yes* | Package names this plugin applies to (e.g. WhatsApp and WhatsApp Business). The first installed one is used. |
| `ios.bundle_ids` | no | Reserved for the iOS client. |
| `actions` | yes | 1 to 30 actions (§4). |

Unknown keys are errors, so typos are caught. `ios` sections are kept but not checked yet.

## 4. Action fields

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | `^[a-z][a-z0-9_]{0,39}$`, unique within the plugin. Avoid the discovered ids (§8) unless you mean to replace them. |
| `title` | yes | Short imperative title, e.g. "Play music" (≤ 60 chars). |
| `description` | yes | For the agent: what it does, what it does *not* do, and caveats (≤ 600 chars). |
| `keywords` | no | Extra words for `app_actions_list(query=…)` search. |
| `risk` | yes** | `read`, `action` (local, reversible: opens or plays something) or `sensitive` (reaches other people, spends money, or is hard to undo). Drives on-phone approval. See §9 for the floors the validator enforces. |
| `scope` | no | The token scope required (default `apps`). Allowed: `apps`, `device`, `contacts.read`, `messages.read`, `messages.send`, `calls`, `notifications.read`, `calendar.read`, `calendar.write`, `location`, `screen.read`. |
| `params` | no | Ordered map of parameter name to spec (§5). Names match `^[a-z][a-z0-9_]{0,39}$`, at most 10. |
| `confirm` | no | Template for the approval prompt, e.g. `Send "{{text}}" to {{contact}} on Telegram`. Defaults to `<App>: <title>` plus the parameters. |
| `completes` | no | `true` if the action finishes the task by itself (plays music, sends a message), `false` if it opens a screen where someone still has to act (a filled-in compose screen, ride options). Defaults to `true` for `tool` and `steps`, `false` for `intent` and `url`. Agents are told what to do next when it is `false`. |
| `android` | yes* | Exactly one recipe: `intent`, `url`, `steps` or `tool` (§6). |
| `examples` | no | Test cases (§7). Strongly encouraged; required for `status: stable`. |

\* An action without an `android` recipe is skipped on Android (for iOS-only actions later).
\** Omit `risk` and `scope` for `tool` recipes. They come from the built-in tool.

## 5. Parameters

```yaml
params:
  destination: {description: Address or place name, required: true, max_length: 300}
  mode:
    description: How to travel
    enum: [driving, walking, bicycling, transit]
    default: driving
  minutes: {type: integer, description: Timer length, minimum: 1, maximum: 1440}
```

| Field | Meaning |
|---|---|
| `type` | `string` (default), `integer`, `number` or `boolean`. |
| `description` | Required. Shown to the agent. |
| `required` | Default `false`. |
| `enum` | Allowed values (strings or numbers). |
| `default` | Used when the agent leaves the parameter out. |
| `min_length` / `max_length` | Strings. `max_length` defaults to 2000 and may not exceed 10000. |
| `minimum` / `maximum` | Numbers. |
| `pattern` | A regular expression the whole string must match, e.g. `^\+?[0-9]{6,15}$`. |

PhoneBridge turns `params` into the JSON Schema the agent sees, and checks every call against it
before anything runs. Unknown parameters are refused.

## 6. Android recipes

### 6.1 Templates

Recipes refer to parameters as `{{name}}`, optionally with spaces: `{{ name }}`. There is no other
syntax: no logic, filters or escapes. A stray `{{` or `}}` is an error, and so is a name that isn't
declared in `params`.

* **URL contexts** (`url`, `intent.data`, step `open_url`): every substituted value is
  percent-encoded as UTF-8, with everything except `A–Z a–z 0–9 - . _ ~` escaped. The scheme must be
  literal: the first `{{` must come after the first `:`. After substitution the URL must not use
  `file:`, `content:`, `javascript:`, `data:`, `intent:`, `android-app:`, `about:`, `blob:` or
  `vbscript:`.
* **Everything else** (extras, `confirm`, step text, tool args) is substituted as-is.
* **A missing optional parameter** without a `default` becomes an empty string. An extra or tool
  argument whose whole value is one placeholder for a missing parameter is left out entirely.
* **Typed values.** An extra or tool argument whose whole value is exactly one placeholder (`"{{minutes}}"`)
  takes the parameter's type: integer becomes an int extra or a JSON number, number becomes a double, boolean
  becomes a boolean.

### 6.2 `intent`: start an activity in the app

```yaml
android:
  intent:
    action: android.intent.action.SEND        # required, literal
    type: text/plain                           # optional MIME type, literal
    data: "geo:0,0?q={{query}}"               # optional, URL context
    categories: [android.intent.category.DEFAULT]
    class: .ShareActivity                      # optional; relative to the package, or a full class name in it
    extras:
      android.intent.extra.TEXT: "{{text}}"   # string template
      android.intent.extra.alarm.HOUR: {type: int, value: "{{hour}}"}
      show_ui: true                            # YAML literals keep their type
```

PhoneBridge **always pins the intent to the plugin's installed package**, so it can never open
another app. It refuses call actions (`CALL`, `CALL_PRIVILEGED`, `CALL_EMERGENCY`), and it never adds
URI-permission flags. Extra types are `string`, `int`, `long`, `double`, `boolean` and
`string_array` (a YAML list of templates).

### 6.3 `url`: open a link in the app

```yaml
android:
  url: "https://www.google.com/maps/dir/?api=1&destination={{destination}}&travelmode={{mode}}"
```

Opens `ACTION_VIEW` (with `CATEGORY_BROWSABLE`), pinned to the plugin's package. If that package
can't open the link, the call fails. It does not fall back to a browser.

### 6.4 `steps`: a screen recipe

For tasks with no intent or link, a recipe drives the app's UI through PhoneBridge's accessibility
service. It reuses the safety checks of the screen tools: unlock-to-continue, one screen action at a
time, no taps while an approval prompt is pending, and app-access checks.

```yaml
android:
  steps:
    - launch: true
    - tap: {desc: Search}
    - type: {into: {id: search_src_text}, text: "{{query}}", submit: true}
    - wait_for: {text_contains: "{{query}}", timeout_ms: 8000}
    - if_present: {text: "Not now"}
      then:
        - tap: {text: "Not now"}
    - tap: {id: result_title, index: 0}
    - expect: {id: now_playing_bar}
      fail: "Spotify did not start playing. Check the screen with screen_read."
```

| Step | Does |
|---|---|
| `launch: true` (or `{fresh: true}` to clear the app's back stack) | Opens the app's launcher screen. |
| `open_url: "<template>"` | Like the `url` recipe. |
| `intent: {…}` | Like the `intent` recipe. |
| `wait_for: <selector>` | Waits until a matching element is on screen. |
| `tap: <selector>` / `long_press: <selector>` | Waits for the element, then clicks it (or its clickable parent). |
| `type: {into: <selector>, text: "<template>", clear: true, submit: false}` | Sets the text of a field (`into` defaults to the focused field). `submit` presses the keyboard's action key. |
| `scroll: {direction: down, in: <selector>, until: <selector>, max: 5}` | Scrolls a list (all keys except `direction` optional). |
| `back: true` | Presses Back. |
| `sleep: 500` | Waits (≤ 3000 ms). |
| `expect: <selector>` + `fail: "<message>"` | Fails the action with the message if the element doesn't show up. |
| `if_present: <selector>` + `then: [steps]` | Runs the nested steps only if the element is on screen now (one level deep). |

Every step may also carry `optional: true` (failure is ignored) and `timeout_ms` (default 5000,
max 15000).

**Selectors** match elements in the plugin's app only:
`{id, text, text_contains, desc, desc_contains, class, clickable, index}`. `id` may be short
(`search_src_text`, which gets the app's package prefixed) or full (`com.spotify.music:id/search_src_text`).
`class` matches the simple class name (`EditText`). Text matches ignore case. `index` picks the nth
match (default 0). A plain string (`tap: Send`) matches an element whose text or description equals it.
Templates are allowed in `text`, `text_contains`, `desc` and `desc_contains`.

**Confinement:** before every tap, long-press, type or scroll, PhoneBridge checks that the plugin's
app is in the foreground. If another app is, the action stops with an error naming it. Recipes can't
press Home, open the notification shade, or touch other apps or PhoneBridge itself.

**Limits:** 40 steps in total (nested ones included), and 90 s per action.

### 6.5 `tool`: use a built-in PhoneBridge tool

```yaml
- id: send_message
  title: Send a message
  description: Sends a WhatsApp message to a contact or phone number.
  params:
    contact: {description: Contact name as saved on the phone}
    message: {description: Message text, required: true}
  android:
    tool:
      name: whatsapp_send
      args: {contact: "{{contact}}", message: "{{message}}"}
```

The call runs exactly as if the agent had called `whatsapp_send` itself, with that tool's scope, risk,
approval, rules and audit entry. This is how app-centric menus reuse the tested built-in tools
(`whatsapp_send`, `email_send`, `sms_send`, `phone_call`, `calendar_event_create`, `alarm_set`, …).

## 7. Examples (tests that run without a phone)

```yaml
examples:
  - params: {destination: Eiffel Tower}
    expect: {url: "https://www.google.com/maps/dir/?api=1&destination=Eiffel%20Tower&travelmode=driving"}
  - params: {}
    expect: {error: "destination"}              # validation must fail, and the message must contain this
```

`expect` holds exactly one of these:

* `intent`: `{action, package, data, type, categories, class, extras}`. `package` is the first entry
  of `android.packages`. Only the keys you list are compared.
* `url`: the full rendered URL.
* `tool`: `{name, args}`.
* `steps`: a list of the rendered steps (optional, compared in order).
* `error`: a substring of the validation error.

## 8. Discovered actions (no plugin needed)

On Android, PhoneBridge scans each launchable app. The scan is refreshed when apps are installed,
updated or removed.

| Action id | When | Params | Risk |
|---|---|---|---|
| `open` | always | none | action |
| `share_text` | the app accepts `ACTION_SEND` with `text/plain` | `text` | action (opens the app's share screen; nothing is posted until someone taps send) |
| `process_text` | the app handles `ACTION_PROCESS_TEXT` (e.g. "Translate") | `text` | action |
| `play_from_search` | the app handles `MEDIA_PLAY_FROM_SEARCH` | `query` | action |
| `web_search` | the app handles `ACTION_WEB_SEARCH` | `query` | action |
| `show_on_map` | the app handles `geo:` links | `query` | action |
| `shortcut_<id>` | each static launcher shortcut whose target is exported and not a call action | none | action |
| `<capability>` | each App Actions capability in the app's `shortcuts.xml` (e.g. `actions.intent.GET_THING` → `get_thing`), with its parameters | per capability | action; `sensitive` for messaging, calling, ordering, booking and payment capabilities |

When a plugin defines an action with the same id, the plugin's version wins.

## 9. Security rules

The validator, at build time and when a local plugin is imported, rejects:

* risk below `sensitive` for scope `messages.send` or `calls`, and for any action whose `confirm`, `title`
  or `id` says it sends, posts, pays, buys, orders, books, calls, deletes or transfers;
* risk `read` for any `steps` recipe that taps, types or scrolls;
* `risk` or `scope` on a `tool` recipe, or a `tool` name that doesn't exist (or is `intent_start` or a
  `ui_*` / `screen_*` tool);
* intents with call actions, a `class` outside the plugin's package, or templates in `action`,
  `type`, `categories`, `class` or extra keys;
* URL templates whose scheme isn't literal;
* `android.packages` containing PhoneBridge itself.

At run time:

* **App access applies.** If the user blocked the app for an agent (or it is on the never-allow
  list), its actions are hidden from that agent and refused.
* **Policy applies per action.** Each action is checked as its own tool, named
  `<package>/<action id>` (e.g. `com.spotify.music/play`), against the agent's scopes, risk cap
  and per-tool rules. It is approved according to its `risk`, rate-limited, and audited under that
  name.
* **Pinned and confined:** intents and links are pinned to the app's package, and recipes act only
  while that app is in the foreground.
* **Local plugins** (imported on the phone for testing, §10) are off unless the user turns on
  *Settings → Developer → Local plugins*. Their actions **always** need approval on the phone, and
  the prompt is marked "unreviewed plugin".

## 10. Developing and testing a plugin

1. `cp -r plugins/_template plugins/myapp` and edit it.
2. `./scripts/plugin-check.sh plugins/myapp` validates the plugin and runs its examples.
3. Try it on your phone without rebuilding the app. Turn on *Settings → Developer → Local plugins*,
   then **Import plugin** and pick your `plugin.yaml`. On a debug build connected over USB, run
   `./scripts/plugin-push.sh plugins/myapp` instead.
4. Ask your agent to use it, or call `app_action_run` directly, and check the Activity log.
5. Optionally, add yourself to `tested` with the app version you used. Then open a pull request.

To start from what an app already declares, use *Apps → (app) → Create plugin draft* in PhoneBridge
(this shares a YAML skeleton with the app's share targets, shortcuts and link schemes), or run
`./scripts/plugin-scaffold.sh <package>` over USB.
