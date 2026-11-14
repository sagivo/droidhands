# PhoneBridge architecture

```
┌──────────────┐  MCP (Streamable HTTP)   ┌───────────────┐  WebSocket tunnel   ┌──────────────────────────────────────────┐
│ AI agent     │ ───────────────────────► │ relay/        │ ◄─────────────────  │ Phone app (clients/android/app)          │
│ Claude Code, │  Bearer pb1.<dev>.<id>.… │ Node/TS,      │  outbound from the  │  BridgeService (foreground)              │
│ Desktop, any │                          │ stateless,    │  phone, so works    │   ├─ RelayClient ─┐                      │
│ MCP client   │ ── or, local mode ─────► │ self-hosted   │  behind NAT/LTE     │   ├─ LocalHttpServer (127.0.0.1:8765)    │
└──────────────┘    adb forward/Tailscale └───────────────┘                     │   └─► McpServer (core) ─► auth ─► policy │
                                                                                │        ─► confirm ─► Tool ─► audit       │
                                                                                │  Tools: contacts, sms, whatsapp, calls,  │
                                                                                │  notifications, calendar, location,      │
                                                                                │  device, apps, intents, screen/UI        │
                                                                                │  (AccessibilityService, NotificationLis- │
                                                                                │   tenerService, Android providers)       │
                                                                                └──────────────────────────────────────────┘
```

## Layers

| Layer | Location | Platform-specific? |
|---|---|---|
| Wire protocols, token format, tool catalog | `docs/PROTOCOL.md`, `docs/TOOLS.md` | No. This is the contract for all platforms. |
| Relay | `relay/` (TypeScript, Node ≥ 20, `ws`) | No. It tunnels opaque HTTP to any device. |
| Bridge core: MCP server, tokens, device identity, policy, rate limits, audit, confirmations API, relay client, local HTTP server | `clients/android/core` (pure Kotlin/JVM; no Android imports) | No. Designed to become a Kotlin Multiplatform module for iOS. An iOS app could also re-implement it from the docs. |
| App plugins: format, validator, templates, rendering, catalog, `app_actions_list` / `app_action_run` | `plugins/` (YAML, one folder per app) and `clients/android/core/.../plugins` | No. Each plugin has an `android:` section today; the platform only runs rendered recipes. |
| Android app: services, permissions UI, tool implementations | `clients/android/app` | Yes |

Adding a platform means implementing the tools from `docs/TOOLS.md` with that platform's APIs,
plus a device that speaks the tunnel protocol. The relay and the agents don't change.

## Request lifecycle (device side)

1. A transport (`RelayClient` or `LocalHttpServer`) produces a `TransportRequest`
   (authorization header, raw body, metadata) and calls `McpServer.handle()`.
2. `McpServer` authenticates with `TokenManager` (401 on failure), parses JSON-RPC, and dispatches.
3. `tools/call`: registry lookup and scope check, then the kill switch (`isAccessEnabled`), then the
   global security settings (the call is refused if they can't be read), then `PolicyEngine`: the
   per-tool BLOCK rule, then active hours, then rate limits, then whether confirmation is needed (tool rule
   ASK/ALLOW, else the token's policy). Then confirmation:
   * `pre`-confirm tools: `ConfirmationProvider` is asked before the call, with `tool.describeCall(args)`.
   * `internal`-confirm tools (`confirmsInternally = true`): the tool resolves its targets and then
     calls `ctx.requireConfirmation(summary)`, which prompts only if policy requires it and throws
     `ToolException` on deny or timeout.

   Tools with `alwaysConfirm` (actions of unreviewed local plugins) are always confirmed, whatever the
   rule and policy say.

   **Dispatch.** A `DispatchTool` (`app_action_run`) stands for other tools. After its own scope check,
   the kill switch, the security settings and its own BLOCK rule, risk cap and active hours
   (`PolicyEngine.precheck`), its `resolve()` picks the tool that really runs: the app action's
   virtual tool `<package>/<action id>`, or a built-in tool such as `whatsapp_send` for `tool` recipes.
   Resolution checks app access (a blocked app is audited `DENIED_APP`) and the parameters, and has no
   side effects. The resolved tool then goes through everything above under its own name: its scope,
   `PolicyEngine.evaluate` (rules keyed by its name, rate limits counted once), confirmation by its
   risk, its timeout and stop checks (which also require the dispatching tool to stay allowed). Its
   audit entry has its name and arguments and notes "via app_action_run". Dispatch never nests.
4. The tool runs with a timeout (`tool.timeoutMs`). `ToolException` and other errors become
   `isError` results.
5. `AuditLog.record()` is called for every call (allowed, denied or failed) and every auth failure.
   Outcomes include `DENIED_POLICY` (tool rule, active hours or the token's `maxRisk`), `DENIED_APP`
   (app access) and `CANCELLED` (stopped by Pause, Revoke or Delete). An `AuditThrottle` summarizes floods of
   pre-execution refusals. In-flight calls are tracked per token (`InFlightCalls`), so
   `McpServer.cancelCalls()` can stop them, and `CallContext.ensureStillAllowed()` re-checks pause and
   revocation inside long-running tools. `ApprovalGuard` (app) pauses all screen control while any
   approval UI exists.
   `TrackingAuditLog` also feeds `UsageTracker`, which holds the per-agent counters behind the usage
   screens. `CallContext` carries the call's `appAccess` and `redactOneTimeCodes`, which every
   app-facing tool enforces.

## App actions and plugins

Every installed app gets a menu of actions ([PLUGINS.md](PLUGINS.md)). The plugin engine lives in
core (`dev.phonebridge.core.plugins`) and knows no Android APIs:

| Piece | Does |
|---|---|
| `PluginYaml` | Safe YAML 1.2 loading (snakeyaml-engine: Core schema only, no custom tags, alias and size limits). |
| `PluginParser`, `PluginValidator`, `PluginExamples`, `PluginLoader` | Turn a `plugin.yaml` into an `AppPlugin`, enforce every rule of PLUGINS.md §3-§9 with path-precise errors, and run its examples. `:core:test` and `:core:checkPlugins` run them over `plugins/`. |
| `Template`, `ParamValues`, `ParamSchemas`, `RecipeRenderer` | `{{param}}` templates (URL encoding, typed values), parameter checks and JSON Schemas, and rendering into `RenderedIntent` / `RenderedUrl` / `RenderedSteps` / `RenderedToolCall`, pinned to the installed package. |
| `PluginCatalog` | Bundled plugins plus local ones (which replace bundled plugins with the same id), by id and by package. |
| `AppActionsPlatform` (interface) | What the platform provides: installed apps, app-name resolution under app access, discovered actions (§8), availability, and running a rendered recipe. The Android app implements it. |
| `AppActionCatalog`, `appActionTools()` | Per app: plugin actions plus discovered ones; each action's tool; `app_actions_list` and `app_action_run` (a `DispatchTool`). |
| `PluginDraft` | A `plugin.yaml` skeleton from an app's discovered actions, with TODO markers and examples computed by rendering each recipe (*Apps → (app) → Create plugin draft*). |

## Android components

| Component | Purpose |
|---|---|
| `PhoneBridgeApp` / `BridgeGraph` | Manual DI: settings, identity, `TokenManager`, `AuditLog`, `ToolRegistry`, `McpServer`, confirmation provider, transports. |
| `BridgeService` | Foreground service (`specialUse`). Runs `RelayClient` and/or `LocalHttpServer`. A persistent notification shows status, with a Pause/Resume action. |
| `BridgeAccessibilityService` | Reads the UI tree of any app, performs clicks, text entry, gestures, global actions and screenshots. Also exempts the app from background-activity-launch limits. |
| `BridgeNotificationListener` | Mirrors active notifications. Replies through `RemoteInput`, triggers actions, dismisses. |
| `NotificationConfirmationProvider` | Shows Allow/Deny prompts, plus `ConfirmationActivity` for full details. |
| `PairingCoordinator` / `PairingActivity` | One-tap pairing: shows the agent's name, origin and code, lets the user choose the access level, and issues the token (`origin = "pairing"`). |
| `UnlockPrompt` | "Unlock to continue": asks the user to unlock when a screen-based action arrives while the phone is locked. |
| `PluginRepository` (`plugins/`) | App plugins: the repository's `plugins/<id>/plugin.yaml`, bundled at build time as `assets/plugins/<id>.yaml` and loaded lazily, plus local plugins (`files/local-plugins/<id>.yaml`, imported in Settings or pushed with `scripts/plugin-push.sh`) while *Settings → Developer → Local plugins* is on. |
| `AndroidAppActions` (`tools/actions/`) | Core's `AppActionsPlatform`: `AppScanner` discovers what apps declare (standard intents, `shortcuts.xml` shortcuts and App Actions capabilities; cached per app version, rescanned when packages change); `ActionIntents` starts intent and link recipes in an exported activity of the app itself, after app-access checks; `StepRunner` runs screen recipes on the screen tools' safety checks, confined to the app. |
| UI (`MainActivity`, Compose) | Onboarding wizard; Home (status, pending approvals and pairings, today's summary); Agents (per-agent usage chart, top tools, activity, access level, app access, tool rules, app action rules grouped by app, active hours, revoke); Apps (every app with its integration and actions; per app: risks and parameters, *Block for all agents*, *Request a better integration*, *Create plugin draft*); Activity (filterable audit); Settings (connection, permissions & access, security: never-allow apps, hide one-time codes, revoke all; developer: local plugins). Handles `phonebridge://configure` links. |
| `AppDirectory` (`ui/apps/`) | The installed apps and their actions as the UI shows them (`AppListings`, built from `AppActionCatalog`), loaded once in the background and shared by the Apps tab, the app action rules and the readable names in the activity log and usage ("Spotify – Play music" for `com.spotify.music/play`). Never reachable by agents. |

## Concurrency

Coroutines throughout. UI-automation tools take a shared `Mutex`, so only one gesture sequence
drives the screen at a time. The relay tunnel multiplexes requests by id.
