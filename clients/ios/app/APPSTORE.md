# DroidHands for iPhone: App Store Connect

What to enter in App Store Connect for `com.droidhands.ios`. Build and upload with `scripts/store-release.sh ios`
([docs/RELEASING.md](../../../docs/RELEASING.md)); screenshots with `scripts/ios-app.sh screenshots` on a 6.9"
iPhone simulator (1320 × 2868).

## App information

| Field | Value |
|---|---|
| Name | Droid Hands (also the home-screen name) *(see "Name" below)* |
| Icon | The robot logo, `DroidHands/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png` (1024 × 1024, no alpha); App Store Connect takes it from the uploaded build |
| Subtitle (30) | Let AI agents use your iPhone |
| Bundle ID | com.droidhands.ios |
| Primary category | Productivity |
| Secondary category | Utilities |
| Age rating | 4+ (no objectionable content, no web view, no user-generated content) |
| Privacy policy URL | https://github.com/sagivo/droidhands/blob/main/docs/PRIVACY.md |
| Support URL | https://github.com/sagivo/droidhands/issues |
| Marketing URL | https://droidhands.com |
| Copyright | 2026 DroidHands contributors |
| Price | Free |

**Name.** "Droid" is a registered trademark (Lucasfilm, licensed to Verizon), and App Review rejects metadata that
names other mobile platforms (guideline 2.3.10), which "Droid" can be read as. If the name is refused, a variant
such as "Hands for AI agents" can be used for the iPhone listing only. The bundle id and the code don't depend on it.

## Version information

**Promotional text (170)**

> Connect Claude, ChatGPT or any MCP agent to your iPhone. You choose what it may do, once; DroidHands does the rest
> while the app is open.

**Description**

> DroidHands lets the AI agents you use (Claude, ChatGPT, Grok, Muse, or any app that supports MCP, the Model
> Context Protocol) use your iPhone for you.
>
> Ask your agent to:
> • add a meeting to your calendar, or tell you what's next
> • remind you to call someone tomorrow at 9
> • look up a friend's number and start a call or a message (you tap Send or Call)
> • find where you are, or open directions in Maps
> • run one of your Shortcuts and use its result
> • copy something to your clipboard, turn on the flashlight, or say something out loud
>
> HOW IT WORKS
> Add your DroidHands connection URL to your agent as a custom connector. When the agent connects, DroidHands asks
> you once what it may do: everything, or only what you choose. Then it works without asking, unless you want it to
> ask before messages, calls and shortcuts.
>
> YOU STAY IN CONTROL
> • One switch pauses every agent at once.
> • Revoke an agent any time; its access ends immediately.
> • Every action is in the activity log, allowed or refused.
> • Per-tool rules: block a tool, or ask every time.
>
> PRIVATE BY DESIGN
> No account, no analytics, no ads. Requests travel from your agent to your iPhone through a relay that stores
> nothing, or through your own relay. DroidHands is open source (Apache 2.0).
>
> iPhone lets DroidHands act only while the app is open, so keep it on screen while your agent works.

**Keywords (100)**

> ai,agent,mcp,claude,chatgpt,assistant,automation,shortcuts,calendar,reminders,connector,llm

**What's new (first release)**

> First release of DroidHands for iPhone.

## App Privacy

**Data collection: "No, we do not collect data from this app."**

Why that is accurate under Apple's definition (data sent off the device in a way that lets the developer or a third
party access it longer than needed to answer the request in real time):

- Tool results go from the phone to the agent the user connected, through the relay, in real time. The relay keeps no
  bodies, tokens or secrets ([relay/CLOUDFLARE.md](../../../relay/CLOUDFLARE.md#costs-and-privacy)).
- Pairing state (with the connecting client's IP address, for rate limits) lives at most about an hour, and only
  to service the pairing.
- No analytics, crash-reporting or advertising SDKs; no account.

Tracking: no. The privacy manifest (`DroidHands/Resources/PrivacyInfo.xcprivacy`) declares no collected data, no
tracking domains, and UserDefaults (reason CA92.1, the app's own settings).

## Export compliance

`ITSAppUsesNonExemptEncryption` is `NO` in the build: the app uses only Apple's TLS (HTTPS/WSS) and SHA-256
hashing, which are exempt. No questionnaire appears on upload.

## App Review information

**Sign-in required:** no (the app has no account).

**Notes for the reviewer** (paste, then adjust the demo link):

> DroidHands is a companion app for AI agents that support MCP (Model Context Protocol) custom connectors, such as
> Claude and ChatGPT. The agent sends requests to the phone through a relay; the phone runs them with public iOS
> APIs (EventKit, Contacts, CoreLocation, MessageUI, CallKit's call observer, URL opening, Shortcuts x-callback-url)
> and only within the access the user chose when the agent connected.
>
> To test (about 3 minutes):
> 1. Open DroidHands, tap Get started, and allow Calendar and Reminders.
> 2. On Home, tap "Connect an AI agent", tap "Copy message" and send the message to Claude or ChatGPT (a free
>    account works). The agent explains where to add the connector in its settings: on chatgpt.com/plugins, Add ›
>    Add custom MCP server, name it DroidHands, paste the URL, keep OAuth and tap Create; on claude.ai, Settings ›
>    Connectors › Add custom connector.
> 3. DroidHands shows "Claude wants to use this iPhone" with a code that matches the one in the browser. Tap Allow.
> 4. Keep DroidHands open and ask the agent: "Add a meeting with Alex tomorrow at 3 pm to my calendar" or "Remind me
>    to buy milk at 6 pm". The event or reminder appears in Calendar / Reminders, and in DroidHands › Activity.
> 5. Agents › (the agent) › Revoke access ends its access at once.
>
> Third-party AI (guideline 5.1.2(i)): before any data can reach an agent, the screen that grants access (step 3's
> "Connect agent" sheet, and also "New token" and an agent's Access page) has a "Shared with a third-party AI"
> section. It names the agent and its website (e.g. "Claude (claude.ai)"), lists the data its access lets it read
> (contacts, calendar events and reminders, location, device status, shortcut results), says it is a third-party AI
> service that handles the data under its own privacy policy, and that tapping Allow (Create, Save) means the user
> agrees. Nothing is pre-selected; Deny or Cancel shares nothing.
>
> Messages and calls always need the user's tap: DroidHands shows the system message composer or iOS's call prompt.
> The app does not run in the background; it works while it is open, as the Home screen says.
>
> Demo video: <link>

**Contact:** the account holder's name, phone and email.

## Screenshots

On an iPhone 6.9" simulator (iPhone 17 Pro Max, 1320 × 2868) connected to the default relay, without accounts like
the store build:

1. `DROIDHANDS_API_URL= scripts/ios-app.sh run --simulator <UDID>`, grant Calendar, Reminders, Contacts and Location
   (`xcrun simctl privacy <UDID> grant calendar com.droidhands.ios`, …), and set the status bar
   (`xcrun simctl status_bar <UDID> override --time 9:41 --batteryState charged --batteryLevel 100`).
2. Relaunch with `SIMCTL_CHILD_DH_SKIP_ONBOARDING=1 SIMCTL_CHILD_DH_TEST_TOKEN_NAME=Claude` (then `ChatGPT`) to make
   two agents; each token lands in the app's `Documents/test-token.txt`. Make a few calls with them (device status,
   add a calendar event and a reminder, list them, location) so the lists aren't empty.
3. `DROIDHANDS_API_URL= scripts/ios-app.sh screenshots --simulator <UDID>`.

The listing uses `1-home`, `2-connect`, `3-agents`, `4-agent`, `5-access` and `6-activity`.
