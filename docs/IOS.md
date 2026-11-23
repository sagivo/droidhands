# DroidHands on iPhone

There are two ways to use DroidHands with an iPhone:

| | DroidHands for iPhone (App Store) | Mac host + runner |
|---|---|---|
| What it is | A native app on the phone, `clients/ios/app` | A process on your Mac driving the phone through XCUITest, `clients/ios/host` + `clients/ios/runner` |
| Install | From the App Store (or TestFlight) | `scripts/ios-host.sh`, with Xcode and a paired iPhone in Developer Mode |
| Agents can | Contacts, calendar, reminders, location, links, messages and calls (the user taps Send or Call), device status, flashlight, clipboard, speech, the user's shortcuts | Everything on screen: phone use in any app, the app list, what each app declares |
| Runs while | The DroidHands app is open on the phone | The Mac is on and the phone is reachable |

## DroidHands for iPhone (the App Store app)

A SwiftUI app with no dependencies. It speaks the same protocols as the Android app ([PROTOCOL.md](PROTOCOL.md)):
an outbound WebSocket to the relay, the same tokens, one-tap OAuth pairing and agent sign-in, the same grant
(Everything, or chosen abilities, plus "ask me before sensitive actions"), per-tool rules, active hours, expiry,
pause, stop, revoke, per-agent usage and an activity log with filters and search. The Kotlin core runs only on the JVM, so the app has a Swift port of the parts a phone needs:

| Swift (`clients/ios/app/DroidHands`) | Kotlin counterpart (`clients/android/core`) |
|---|---|
| `Core/Encoding.swift`, `Tokens.swift`: identity (secret in the Keychain, this device only), tokens (only SHA-256 stored) | `auth/DeviceIdentity`, `auth/TokenManager` |
| `Core/McpServer.swift`: auth, JSON-RPC, `tools/list`, the `tools/call` pipeline (scope, pause, BLOCK rule, risk cap, approval, timeout paused while asking, one audit entry per call, Stop/Revoke cancel running calls) | `mcp/McpServer`, `mcp/ToolCalls` |
| `Core/RelayClient.swift`, `Pairing.swift`: hello/welcome, tunneled requests, keepalive and heartbeat, close codes, backoff; pairing prompts with relay-clock expiry | `transport/RelayClient`, `transport/RelayPairings` |
| `Core/Access.swift`, `AccessSummary.swift`: scopes, abilities, the grant, refusal texts, `initialize` instructions | `auth/Grant`, `auth/GrantText`, `mcp/AccessSummary` |
| `Tools/`: the iPhone tools ([TOOLS.md](TOOLS.md#platforms)) on public APIs only: Contacts, EventKit, CoreLocation, MessageUI, CallKit's call observer, `UIApplication.open`, Shortcuts' x-callback-url | `clients/android/app/.../tools` |

The abilities are the iPhone's own set over the shared scope names: *Read contacts, calendar & reminders*
(`contacts.read`, `calendar.read`), *Location*, *Open links and apps* (`apps`), *Send messages* (`messages.send`),
*Make calls* (`calls`), *Change calendar & reminders* (`calendar.write`), *Device* (`device`) and *Run your
shortcuts* (`intents`). The test vectors of PROTOCOL.md §1 are in `DroidHandsTests`.

**When it works.** iOS runs an app only while it is on screen, plus about 30 seconds after the user leaves it.
DroidHands stays connected for that time, then closes the relay connection (code 1000), so agents get `503 Device
offline` at once instead of waiting for a timeout. Tools that need the screen (opening links, the message composer,
calls, shortcuts, the flashlight, speech, location) say so when the app is in the background. Waking the app with a
push notification when an agent calls is not built yet: it needs an APNs key, and the relay must send HTTP/2
requests to APNs.

**What it can't do, by iOS design.** No access to other apps' screens or notifications, SMS history, the call log,
the list of installed apps, volume, ringer or alarms. Messages and calls always need the user's tap (the message
composer's Send, iOS's Call prompt); `sms_send` waits for it and reports whether the user sent the message, and
`phone_call` watches CallKit for the call to start. The user's shortcuts are the escape hatch: a shortcut can send a
message without a tap or run another app's actions, and `shortcut_run` returns its output.

### Building and testing

```
./scripts/ios-app.sh test                          # unit tests on a simulator
./scripts/ios-app.sh run --relay http://127.0.0.1:8788   # Debug build against a local relay, with a test token
./scripts/ios-app.sh screenshots                   # every screen as a PNG (App Store screenshots on a 6.9" simulator)
./scripts/store-release.sh ios                     # signed build → App Store Connect (docs/RELEASING.md)
```

`run --relay` uses Debug-only hooks (`DebugHooks` in `DroidHandsApp.swift`, compiled out of Release builds): it
points the app at the relay and writes a Full-access token for an agent named "Test" to the app's
`Documents/test-token.txt`. With it, `scripts/e2e/pairing.mjs <connectUrl>` runs the OAuth pairing (approve on the
simulator). The App Store listing, review notes and privacy answers are in
[clients/ios/app/APPSTORE.md](../clients/ios/app/APPSTORE.md).

## The Mac host (full phone use)

DroidHands gives agents the same three layers on iPhone as on Android, from the same core, with the same tool
names ([TOOLS.md](TOOLS.md)):

1. **Every app on the phone**: `apps_list`, `app_open`, read from the phone's own app inventory.
2. **What each app declares it can do**: `app_actions_list` / `app_action_run`, built at run time from the
   installed app's `Info.plist`, entitlements and App Intents metadata, never from a list kept by DroidHands.
3. **Phone use** for everything else: `screen_observe` (a screenshot with numbered boxes plus the actionable
   elements), `ui_tap`, `ui_type`, `ui_scroll`, `ui_swipe`, `ui_global_action`. The agent looks and decides where
   to tap.

### Why it runs this way

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

### Discovered actions on iOS

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

### Runner protocol (host ⇄ runner)

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

### Running it

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
