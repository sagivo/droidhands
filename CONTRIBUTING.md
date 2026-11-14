# Contributing to PhoneBridge

Thanks for helping! There are two ways in:

* **Teach PhoneBridge an app** (no code): one small YAML file per app gives every AI agent ready-made actions
  for it, such as "play this song" or "navigate home". This is the most useful contribution, and the
  quickest.
* **Work on the code**: the Android app, the platform-neutral Kotlin core and the relay.

Security problems are not reported as issues: see [Reporting security issues](#reporting-security-issues).

## Add an app in 5 minutes

Agents pick an app and one of its actions (`apps_list` → `app_actions_list` → `app_action_run`). PhoneBridge
already discovers the basics of every app by itself (open it, share text into it, its launcher shortcuts); a
**plugin** adds the actions written for that app. The full format is in [docs/PLUGINS.md](docs/PLUGINS.md); this
is the short way.

**1. Start the file.** Pick an id (lowercase, e.g. `spotify`) and do one of these:

```bash
cp -r plugins/_template plugins/spotify                       # from the template
./scripts/plugin-scaffold.sh --write --name Spotify com.spotify.music   # from what the app declares (phone over USB)
```

or, on the phone, open PhoneBridge → **Apps** → the app → **Create plugin draft** and share the file to your
computer. The scaffold and the draft fill in what PhoneBridge can see (share targets, text actions, media and
web search, maps links, shortcuts) and mark everything to check with `TODO`. The app's package name is on
its page in the Apps tab.

**2. Fill it in.** For each action: a short `title`, a `description` for the agent (what it does, and what it
doesn't), `risk`, `params` and one recipe:

```yaml
  - id: search
    title: Search
    description: Opens Spotify's search results for a query, without playing anything.
    risk: action                          # read | action | sensitive
    params:
      query: {description: Search text, required: true, max_length: 200}
    android:
      url: "spotify:search:{{query}}"
    examples:
      - params: {query: daft punk}
        expect: {url: "spotify:search:daft%20punk"}
```

Prefer, in this order: a `tool` (reuse a built-in tool such as `whatsapp_send`), an `intent`, a `url` deep
link, and only then `steps` (a screen recipe). Anything that sends, posts, pays, orders, books, calls or
deletes must be `risk: sensitive`, so the user approves it on the phone; the checker enforces that.

**3. Check it** on your computer (needs a JDK 17, no phone and no Android SDK):

```bash
./scripts/plugin-check.sh plugins/spotify
```

It validates the file, runs its `examples` and points at the exact field when something is wrong.

**4. Try it on your phone**, without rebuilding the app. In PhoneBridge, turn on **Settings → Developer →
Local plugins**, tap **Import plugin** and pick your `plugin.yaml`. With a debug build connected over USB, this
does the same:

```bash
./scripts/plugin-push.sh plugins/spotify
```

Then ask your agent to use it (or call `app_action_run` directly) and look at the **Activity** tab. Local
plugins always ask for approval on the phone.

**5. Open a pull request.** Run `./scripts/plugin-table.sh` first, so [plugins/README.md](plugins/README.md)
lists your plugin (CI checks it), and fill in the pull request checklist. New plugins start as `experimental`;
add a `tested` record once you ran every action on a real device.

Not up for writing it? [Request an integration](../../issues/new?template=app-integration.yml) (the app's
**Request a better integration** button opens the same form with the app filled in).

### Tips

* **Finding deep links**: `./scripts/plugin-scaffold.sh <package>` lists the link schemes and hosts the app
  opens. The app's developer documentation, its web URLs (many apps open their own `https://` links) and
  `adb shell dumpsys package <package>` help too.
* **Try a link before writing it down**: `adb shell am start -a android.intent.action.VIEW -d "spotify:search:daft%20punk" com.spotify.music`.
* **`completes: false`** for actions that open a screen where someone still has to tap (a filled-in compose
  screen): agents are then told to finish with the screen tools or ask the user.
* Plugins can't do anything PhoneBridge can't already do: intents and links are pinned to the plugin's app,
  screen recipes act only inside it, and every action is approved, rate-limited and audited like any tool
  ([docs/PLUGINS.md §9](docs/PLUGINS.md#9-security-rules)).

## Contributing code

| Path | What | Tests |
|---|---|---|
| `clients/android/core` | Platform-neutral Kotlin: MCP server, tokens, policy, audit, transports, the plugin engine | `./gradlew :core:test` |
| `clients/android/app` | The Android app: services, tools, accessibility, Compose UI | `./gradlew :app:testDebugUnitTest :app:assembleDebug` |
| `relay/` | The relay (TypeScript, Node 22) | `npm ci && npm test` |
| `scripts/` | Setup, plugin and release scripts | `./scripts/setup-phone.test.sh`; plugin scripts run in `:core:test` |
| `scripts/e2e/` | End-to-end scenarios against a device or emulator | see the file headers |

Gradle runs from `clients/android` and needs a JDK 17 and the Android SDK (`JAVA_HOME`, `ANDROID_HOME`):

```bash
cd clients/android
./gradlew :core:test :core:checkPlugins :app:testDebugUnitTest :app:assembleDebug
```

CI runs the same commands, plus the relay's tests and build, on every push and pull request. The developer
setup over USB (install, permissions, relay) is [`scripts/setup-phone.sh`](scripts/setup-phone.sh), described
in [docs/GETTING_STARTED.md](docs/GETTING_STARTED.md). How the pieces fit: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

### Code style

* **Match the code around you**: Kotlin official style, small pure objects for logic (unit-tested) and thin
  Android or Compose layers on top, KDoc on every public type and on anything non-obvious.
* **Agent-facing text tells the agent what to do next** ("Pass 'app', e.g. Spotify", "Use apps_list to see
  installed apps"), and user-facing text is short and plain.
* **Security fails closed.** Scopes, per-agent app access, the never-allow list, approvals (never approvable
  by an agent), pause and revoke stop checks, and the audit log must hold for every new path. Read
  [docs/SECURITY.md](docs/SECURITY.md) before touching tools, policy or transports.
* **Test pure logic** and keep the whole suite green. New dependencies need a good reason.
* Change the contract docs (`docs/PROTOCOL.md`, `docs/TOOLS.md`, `docs/PLUGINS.md`) in the same pull request as
  the behavior.

### Pull requests

Keep them focused, explain the why in the description, and fill in the checklist. Contributions are licensed
under the project's [Apache License 2.0](LICENSE).

## Reporting security issues

Please don't open a public issue for a vulnerability. Report it privately as described in
[docs/SECURITY.md](docs/SECURITY.md#reporting-a-vulnerability), so it can be fixed before it is public.
