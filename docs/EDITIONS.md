# Editions

DroidHands ships as three apps, built from this repository and published by the release workflow on every `v*` tag
([RELEASING.md](RELEASING.md)):

| | Android **full** | Android **store** | iPhone |
|---|---|---|---|
| Where | GitHub Releases (`DroidHands.apk`), droidhands.com | Google Play | App Store |
| Built by | `:app:assembleFullRelease` | `:app:bundleStoreRelease` (`scripts/store-release.sh android`) | `scripts/store-release.sh ios` |
| Phone use (`screen_*`, `ui_*`, screen recipes) | yes, through the accessibility service | no | no |
| Notifications, calls, contacts, calendar, device, app actions | yes | yes | public iOS APIs only ([IOS.md](IOS.md)) |
| SMS | read (`sms_list`) and send through the SIM | `sms_send` opens the SMS app with the message written; the user taps Send | like the store edition |
| Call history (`call_log_list`), caller number in `call_status` | yes | no | no |
| Location (`location_get`) | any time, with "Allow all the time" | only while DroidHands is on screen | while the app is open |
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

## Why the store edition has no SMS, call log or background location

Play rejected the store edition on 2026-11-25 under its
[Permissions and APIs that Access Sensitive Information](https://support.google.com/googleplay/android-developer/answer/16558241)
policy: the SMS and call-log declaration ("Device Automation") didn't match the app's core functionality, and the
background-location feature didn't meet the location requirements. SMS and call-log permissions are for default
SMS, phone and assistant handlers and a short list of exceptions, and background location is for features that are
broken without it; Google's fix for both is to remove the permissions. So the store edition's manifest has no
`READ_SMS`, `SEND_SMS`, `READ_CALL_LOG` or `ACCESS_BACKGROUND_LOCATION`; they are in `src/full/AndroidManifest.xml`.

What changes for agents on the store edition:

- `sms_list` and `call_log_list` don't exist. `sms_send` opens the SMS app with the recipient and text filled in
  (`sent: false`), and the user taps Send, like on the iPhone. SMS replies through `notification_reply` still work.
- `call_status` can't tell who is calling (Android reveals the number only with `READ_CALL_LOG`).
- `location_get` works while the DroidHands app is open on screen. Otherwise it says so at once, and the agent asks
  the user to open the app. (Android doesn't give location to a background app without background location, and
  the bridge's `specialUse` foreground service doesn't count.)

In the Play Console, remove the SMS/Call log and background location declarations once the new bundle is live:
every track's bundles must be free of those permissions.

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
- `Edition.SMS_AND_CALL_LOG` gates `sms_list`, `call_log_list` and the SIM `sms_send` (`messagingTools`), the SMS
  and call-log Setup rows (`PermissionCatalog.runtime`) and the agent texts that mention them.
  `Edition.BACKGROUND_LOCATION` gates the "Location while in background" Setup row and `location_get`'s
  only-while-open check. Core: `ToolNames.SMS_AND_CALL_LOG_READ`.
- `Edition` (`com.droidhands.android.Edition`): `PHONE_USE` gates the screen tools in `AndroidToolCatalog`, screen
  recipes, the accessibility setup step and the edition-specific agent text (`AgentInstructions.FULL` / `STORE`).
- Core: `ToolNames.PHONE_USE` (the screen tools), `Scopes.PHONE_USE` and `Scopes.hasPhoneUse(registry)`,
  `AppActionsPlatform.phoneUse` (hints of app actions), and `Ability.offered(phoneUse)` (the grant screen and the
  access summary leave out "See the screen" without phone use). Tokens keep the same scopes in both editions.
- Tests run for both flavors: `./gradlew :app:testFullDebugUnitTest :app:testStoreDebugUnitTest`.
