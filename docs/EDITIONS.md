# Editions

DroidHands ships as three apps, built from this repository and published by the release workflow on every `v*` tag
([RELEASING.md](RELEASING.md)):

| | Android **full** | Android **store** | iPhone |
|---|---|---|---|
| Where | GitHub Releases (`DroidHands.apk`), droidhands.com | Google Play | App Store |
| Built by | `:app:assembleFullRelease` | `:app:bundleStoreRelease` (`scripts/store-release.sh android`) | `scripts/store-release.sh ios` |
| Phone use (`screen_*`, `ui_*`, screen recipes) | yes, through the accessibility service | no | no |
| Messages, notifications, calls, contacts, calendar, location, device, app actions | yes | yes | public iOS APIs only ([IOS.md](IOS.md)) |
| Approvals | the user's choice per agent | the user's choice per agent | the user's choice per agent |
| In-app update check (GitHub) | yes | no (Play updates it) | no |

The two Android editions are Gradle product flavors (`full`, `store`) of the same app, with the same application id
`com.droidhands.android`, so a phone has one or the other. They are signed with different keys (Play re-signs with
its app signing key), so moving between them means uninstalling first.

## Why the store edition has no phone use

Google Play's [Accessibility API policy](https://support.google.com/googleplay/android-developer/answer/10964491)
says the API "cannot be requested for" an app that "autonomously initiate[s], plan[s], and execute[s] actions or
decisions", which is what an AI agent operating other apps is. Play rejected the earlier builds for this on
2026-11-23. The policy allows "deterministic, rule-based automation, where behavior follows a static, human-defined
script", which may allow approved screen recipes in the store edition later, as an update of its own.

Everything else works the same, without accessibility: replies through notifications in any messaging app, SMS and
calls, contacts, calendar, location, device settings, and the app actions discovered on the phone or defined by
plugins. Actions with `completes: false` open the right screen (a chat with the message written) and the user
finishes there.

The App Store has no API for one app to control another, so the iPhone app never had phone use; the Mac host with the
XCUITest runner ([IOS.md](IOS.md)) is the iPhone route to it, outside the App Store.

## Rules for the store apps

- Nothing in the store editions points users to the full edition or to installing an APK: not in the app, not in
  agent-facing text, not in the listing. Both stores refuse apps that steer users to software installed outside
  them, and Play counts every rejection against the developer account.
- No hidden switches. A store build never turns on behavior its review didn't see (remote flags, unlock codes, a
  companion APK that adds phone use).
- Agent-facing text matches the edition: without phone use, hints send the agent to the user ("ask the user to tap
  Send") instead of to screen tools it doesn't have.
- The iPhone app asks before data reaches a third-party AI (App Store guideline 5.1.2(i)): the pairing, new-token and
  access screens name the agent and the data it can get, and Allow is the consent
  ([clients/ios/app/APPSTORE.md](../clients/ios/app/APPSTORE.md)).

## In the code

- `clients/android/app/build.gradle.kts`: the flavors and their `BuildConfig.PHONE_USE` and `GITHUB_REPO`.
- `clients/android/app/src/full/`: the accessibility service's manifest entry, config and strings (full only).
- `Edition` (`com.droidhands.android.Edition`): `PHONE_USE` gates the screen tools in `AndroidToolCatalog`, screen
  recipes, the accessibility setup step and the edition-specific agent text (`AgentInstructions.FULL` / `STORE`).
- Core: `ToolNames.PHONE_USE` (the screen tools), `Scopes.PHONE_USE` and `Scopes.hasPhoneUse(registry)`,
  `AppActionsPlatform.phoneUse` (hints of app actions), and `Ability.offered(phoneUse)` (the grant screen and the
  access summary leave out "See the screen" without phone use). Tokens keep the same scopes in both editions.
- Tests run for both flavors: `./gradlew :app:testFullDebugUnitTest :app:testStoreDebugUnitTest`.
