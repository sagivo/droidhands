# DroidHands for Android: Google Play listing

What to enter in the [Play Console](https://play.google.com/console) for `com.droidhands.android`
(account: hello@droidhands.com). Build and upload with `scripts/store-release.sh android --draft`
([docs/RELEASING.md](../../docs/RELEASING.md)).

## Create app

| Field | Value |
|---|---|
| App name (30) | DroidHands |
| Default language | English (United States) |
| App or game | App |
| Free or paid | Free |
| Declarations | Developer Program Policies and US export laws: tick both |

## Main store listing

**Short description (80)**

> Let your AI agents (Claude, ChatGPT, Grok…) use your phone. You set what they may do.

**Full description (4000)**

> DroidHands lets the AI agents you already use (Claude, ChatGPT, Grok, Muse, or any app that supports MCP, the
> Model Context Protocol) use your Android phone for you.
>
> Ask your agent to:
> • text a friend, or read you your latest messages
> • call someone from your contacts
> • add a meeting to your calendar, or tell you what's next
> • set an alarm or a timer
> • find where you are, or open directions
> • open any app and do something in it, such as sending a WhatsApp message, by looking at the screen and tapping
>   like you would
>
> SET UP IN A MINUTE
> Paste your DroidHands connection URL into your agent as a custom connector, then tap Allow on your phone. That's
> it. No account, no sign-up.
>
> YOU STAY IN CONTROL
> • When an agent connects, you choose once what it may do: everything, or only the apps and abilities you pick.
>   After that it works without asking. Anything outside that choice is refused, never silently allowed.
> • One switch pauses every agent at once.
> • Revoke an agent any time; its access ends immediately.
> • A notification with a Stop button shows whenever an agent is working.
> • Every action is in the activity log, allowed or refused.
> • Never-allow list, per-tool rules, active hours, and hiding one-time codes.
>
> PRIVATE BY DESIGN
> No account, no analytics, no ads, no tracking. Requests travel from your agent to your phone through a relay that
> stores nothing, or through your own relay or Tailscale. DroidHands is open source (Apache 2.0):
> https://github.com/sagivo/droidhands
>
> ABOUT ACCESSIBILITY
> To let an agent use other apps on your behalf (read the screen, tap, type, scroll), DroidHands uses Android's
> Accessibility Service API, only if you turn it on, and only for agents you have connected and allowed. It is not
> used to collect data, and nothing it reads is stored or sent anywhere except back to the agent you allowed, in
> answer to its request.

## Store settings

| Field | Value |
|---|---|
| App category | Productivity |
| Tags | Productivity, Automation (pick what Play offers) |
| Email | hello@droidhands.com |
| Website | https://droidhands.com |
| Privacy policy URL | https://github.com/sagivo/droidhands/blob/main/docs/PRIVACY.md |

## Graphics

- App icon 512 × 512 PNG: render from `app/src/main/res/drawable/ic_launcher_foreground.xml` on the indigo
  background of `mipmap-anydpi-v26/ic_launcher.xml`.
- Feature graphic 1024 × 500 PNG.
- Phone screenshots: at least 2, 16:9 to 9:16 (for example 1080 × 2400), from a signed-off build: Home, Connect,
  Agents, Access, Activity. The iPhone ones in `dist/ios-screenshots` are a visual reference only; Play wants
  Android screenshots.

## App content

| Section | Answer |
|---|---|
| Privacy policy | URL above |
| Ads | No, the app doesn't contain ads |
| App access | All functionality is available without special access. *(Reviewer note below)* |
| Content rating | IARC questionnaire, category "Utility, Productivity, Communication or Other": no violence, sex, language, drugs, gambling; no user-generated content; no location sharing with other users. Expected: Everyone / 3+ |
| Target audience | 18 and over (not directed at children) |
| News app | No |
| Government app | No |
| Financial features | None |
| Health | None |
| Advertising ID | No (the app doesn't use it) |

**App access reviewer note**

> DroidHands has no account. To test: open the app, tap Get started and grant the permissions you want to try. Tap
> "Connect an AI agent", choose ChatGPT or Claude, and add the copied URL as a custom MCP connector (ChatGPT:
> chatgpt.com/plugins › Add › Add custom MCP server; a free account works). DroidHands shows a prompt with a code;
> tap Allow. Then ask the agent: "What's on my calendar tomorrow?" or "Set a 5 minute timer." Agents › (agent) ›
> Revoke access ends access at once.

## Data safety

**Does the app collect or share user data?** The app handles user data only to answer requests from the agent the
user connected, in real time. It is not stored by the developer.

Recommended answers (they match docs/PRIVACY.md):

- Collects data: **Yes**, because data leaves the device to the user's own agent. Mark each type below as
  **Collected: not retained**, purpose **App functionality**, **processed ephemerally**, **not required**
  (the user chooses), and shared **No** (the user's own agent is a user-directed transfer; select "Transferring data
  to a third party at the user's request" where Play offers it).
  - Contacts, Calendar events, Approximate and precise location, SMS or MMS messages, Call log, Phone number, Device
    or other IDs (the device id used for routing).
- Data encrypted in transit: **Yes** (TLS).
- Users can request deletion: **Yes**. The app keeps its data only on the phone (clear the log, revoke agents, or
  uninstall).
- Independent security review: No.

## Permissions declarations (these decide whether it is approved)

The Play review of these is where DroidHands is most likely to be rejected, so decide before you submit.

1. **SMS and call log** (`SEND_SMS`, `READ_SMS`, `READ_CALL_LOG`): Play restricts them to apps that are the default
   SMS, phone or assistant handler, or that match a listed exception. DroidHands is neither. Either request the
   declaration form with the core-functionality justification "lets the user's own AI agent send and read their
   texts and calls on their request" (expect a rejection), or remove these permissions and the `sms_*` and
   `call_log` tools from the Play build.
2. **AccessibilityService**: the form asks whether the app is an accessibility tool. DroidHands isn't; it uses the
   API to act for the user on their request. State this plainly. Complete the in-app *prominent disclosure* and
   consent screen before the service is turned on (it must say what is read and why, and have an explicit Agree).
   Play also asks for a short **video** showing the disclosure and enabling the service.
3. **Foreground service, special use** (`BridgeService`): purpose "keeps the connection to the user's AI agent
   open while it is working". Provide the video of the user-visible notification.
4. **Background location** (`ACCESS_BACKGROUND_LOCATION`): needs its own form and video; drop it from the Play
   build unless an agent really must read the location while the app is closed.
5. `SYSTEM_ALERT_WINDOW`, `USE_FULL_SCREEN_INTENT` and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` each need a short
   justification (approval prompts shown over other apps; keeping the connection alive).
6. Notification listener: declare in the data safety form that notifications are read on the user's request.

## Release

- Testing: the first release goes to Internal testing (`--draft`). New personal developer accounts must run a
  closed test with at least 12 testers for 14 days before they can apply for production access.
- Play App Signing: import the release key, so GitHub and Play builds can update each other
  ([docs/RELEASING.md](../../docs/RELEASING.md#google-play-one-time-setup)).
