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
| UI (`MainActivity`, Compose) | Onboarding wizard; Home (status, pending approvals and pairings, today's summary); Agents (per-agent usage chart, top tools, activity, access level, app access, tool rules, active hours, revoke); Activity (filterable audit); Settings (connection, permissions & access, security: never-allow apps, hide one-time codes, revoke all). Handles `phonebridge://configure` links. |

## Concurrency

Coroutines throughout. UI-automation tools take a shared `Mutex`, so only one gesture sequence
drives the screen at a time. The relay tunnel multiplexes requests by id.
