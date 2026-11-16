# DroidHands on iPhone

DroidHands gives agents the same three layers on iPhone as on Android, from the same core, with the same tool
names ([TOOLS.md](TOOLS.md)):

1. **Every app on the phone**: `apps_list`, `app_open`, read from the phone's own app inventory.
2. **What each app declares it can do**: `app_actions_list` / `app_action_run`, built at run time from the
   installed app's `Info.plist`, entitlements and App Intents metadata, never from a list kept by DroidHands.
3. **Phone use** for everything else: `screen_observe` (a screenshot with numbered boxes plus the actionable
   elements), `ui_tap`, `ui_type`, `ui_scroll`, `ui_swipe`, `ui_global_action`. The agent looks and decides where
   to tap.

## Why it runs this way

iOS gives an App Store app no way to list other apps, read their declarations, see their screens or tap in them.
The one channel Apple provides for all four is UI testing: an XCUITest runner can screenshot the screen, read the
accessibility tree of any app, tap, type, swipe, launch apps and open URLs. Apple's developer tools on a Mac can list
installed apps with their `Info.plist`. Every iPhone automation tool (Xcode UI tests, Appium, Maestro) uses this
channel. DroidHands uses it too:

```
┌──────────┐  MCP   ┌───────┐  tunnel  ┌──────────── Mac: DroidHands iOS host (clients/ios/host) ────────────┐
│ AI agent │ ─────► │ relay │ ◄─────── │ the same core as Android: MCP server, tokens, grant, policy, audit,  │
└──────────┘        └───────┘          │ relay client, local server, plugins, app actions, phone-use tools   │
                                       │                                                                       │
                                       │ IosAppActions ── app inventory (simctl / devicectl / pymobiledevice3) │
                                       │                  └ Info.plist, entitlements, App Intents metadata     │
                                       │ RunnerScreenDriver ── HTTP + secret ──┐                               │
                                       └───────────────────────────────────────┼───────────────────────────────┘
                                                                               ▼
                                       ┌──────── iPhone: DroidHands runner (clients/ios/runner) ──────┐
                                       │ an XCUITest bundle: screenshot, accessibility tree of the app  │
                                       │ in front, tap, type, swipe, Home, launch app, open URL         │
                                       └────────────────────────────────────────────────────────────────┘
```

What is shared and what is per platform:

| | Android | iOS |
|---|---|---|
| MCP, tokens, grant, policy, audit, transports, plugins, `app_actions_*` | core | core (same code) |
| Phone-use tools | Android app (`tools/screen`) | core `com.droidhands.core.screen` over a `ScreenDriver` |
| Screen access | AccessibilityService | runner (XCUITest) |
| App list | PackageManager | simctl / devicectl / pymobiledevice3 |
| What apps declare | `AndroidManifest.xml`, `shortcuts.xml` | `Info.plist`, entitlements, `Metadata.appintents` |
| Approvals and pairing | notification on the phone | dialog on the Mac |

`InstalledApp.packageName` holds the bundle id on iOS, so app access, tool rules (`com.spotify.client/open_link`),
the never-allow list and the audit log work unchanged.

## Discovered actions on iOS

Everything below is read from the installed version of each app, every time it changes:

| Action id / `declares` key | Read from | Runs as |
|---|---|---|
| `open` | always | launch the app |
| `open_link` | `CFBundleURLTypes` schemes, and `applinks:` domains in the `com.apple.developer.associated-domains` entitlement | `url_open` pinned to the app: only links with a scheme the app declares, or `https` on its declared domains. The result says which app came to the front, since iOS, not DroidHands, picks the app for a link |
| `declares.app_intents` | `Metadata.appintents/extract.actionsdata` in the app bundle (when the inventory can read the bundle) | not runnable by other apps on iOS: Siri, Spotlight and Shortcuts run them. Listed with their titles, parameters and App Shortcut phrases so the agent knows the app can do it, and does it with phone use |
| `declares.quick_actions` | `UIApplicationShortcutItems` | Home Screen icon menu (phone use: long-press the icon) |
| `declares.document_types` | `CFBundleDocumentTypes` | informational |

Plugins add `ios:` recipes next to `android:` ones ([PLUGINS.md](PLUGINS.md)): `url`, `steps` and `tool`. A `url`
recipe is offered only when the installed app declares the link's scheme (or domain), the iOS form of checking a
recipe against the installed app.

## Runner protocol (host ⇄ runner)

The runner is an HTTP/1.1 server inside the XCUITest process on the phone, port `8101` by default
(`PB_RUNNER_PORT`). Every request carries `Authorization: Bearer <secret>`; the host generates the secret and passes
it at launch (`TEST_RUNNER_PB_RUNNER_SECRET`, which the test runner sees as `PB_RUNNER_SECRET`). The runner refuses
to start without one and answers `401` to anything else. Bodies are JSON (`Content-Type: application/json`).
Errors are `4xx/5xx` with `{"error": "<plain sentence for the agent>"}`. Coordinates are screen **points**.

| Method, path | Body → response |
|---|---|
| `GET /status` | → `{runner_version, os: "ios", os_version, model, name, screen: {width, height, scale}, locked?}`. XCTest has no lock-state API, so `locked` is usually absent. |
| `POST /foreground` | `{candidates?: [bundle ids]}` → `{bundle_id}`: the app in front (`com.apple.springboard` for the Home Screen). The runner may ignore `candidates` if it can tell otherwise. |
| `POST /snapshot` | `{bundle_id?, max_depth?: 60}` (default: the app in front) → `{bundle_id, screen: {width, height}, keyboard: bool, root: Element\|null, overlays: [{owner, root: Element}]}`. `overlays` holds system alerts drawn over the app. |
| `POST /screenshot` | `{format?: "png"\|"jpeg", quality?: 0.8}` → `{mime, width, height, data}` (pixels; `data` is base64) |
| `POST /tap` | `{x, y, duration_ms?: 0}` → `{}`. A duration above 0 is a long press. |
| `POST /swipe` | `{from_x, from_y, to_x, to_y, duration_ms?: 300}` → `{}` |
| `POST /type` | `{text}` → `{}`: types into the focused element. `409` when no element has keyboard focus |
| `POST /clear` | `{}` → `{}`: clears the focused text field |
| `POST /key` | `{key: "return"\|"delete"}` → `{}`. `409` when no element has keyboard focus |
| `POST /button` | `{name: "home"\|"volume_up"\|"volume_down"}` → `{}` |
| `POST /lock` | `{}` → `{}`. `501` if this XCTest lacks the lock button |
| `POST /launch` | `{bundle_id}` → `{state}`: activates the app, launching it if needed |
| `POST /open_url` | `{url}` → `{}`: the system opens the URL (iOS picks the app) |
| `POST /app_state` | `{bundle_ids: [..]}` → `{states: {<id>: "not_running"\|"background"\|"foreground"\|"unknown"}}` |

`Element` = `{type, label?, value?, identifier?, placeholder?, frame: [x, y, w, h], enabled, selected, focused,
secure?, children: [Element]}`. `type` is the `XCUIElement.ElementType` name without a prefix (`button`,
`textField`, `cell`, …). A secure text field never reports `value`.

The runner does no policy: the host checks the agent's grant, app access, pause and approvals before every
call, as the Android app does before its accessibility service acts.

## Running it

```
scripts/ios-host.sh --simulator          # boots/uses a simulator, builds and starts the runner, starts the host
scripts/ios-host.sh --device <udid>      # a USB or Wi-Fi paired iPhone in Developer Mode (needs a signing team)
```

The host prints the connection URL; pairing and approvals appear as dialogs on the Mac. State (device identity,
tokens, audit log) lives in `~/.droidhands/ios/<device>/`.

Limits on iPhone, each reported to the agent in plain words when it applies: the Mac must be on and the phone
reachable (USB, or the same Wi-Fi); a locked iPhone can't be operated; screens that block capture (passwords, some
banking apps) come back black, so the agent works from the element list; there is no Back button (use the
app's own back control, or swipe from the left edge).

Not yet handled: XCUITest reports no occlusion, so elements hidden behind another view of the same app or under the
keyboard can still be listed (their boxes then sit on what covers them); the screenshot shows what is really there.
