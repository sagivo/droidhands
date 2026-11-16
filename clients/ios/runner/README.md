# PhoneBridge iOS runner

The part of PhoneBridge that runs on the iPhone (or simulator). It is an XCUITest bundle: while its one test,
`PhoneBridgeRunnerTests/testRunBridge`, runs, it serves the runner protocol over HTTP on the phone and drives
any app with Apple's UI-testing API: screenshot, accessibility tree of the app in front, tap, long press, swipe,
type, Home and volume buttons, lock, launch an app, open a URL. The PhoneBridge iOS host on the Mac is its only
client. The protocol is in [docs/IOS.md](../../../docs/IOS.md#runner-protocol-host--runner).

`PhoneBridgeRunnerHost` is an empty app the UI test is attached to (Xcode requires one). It only says
"PhoneBridge runner — keep this running"; the runner works whichever app is in front.

## Build and run

Needs Xcode and [xcodegen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`). The Xcode project
is generated from `project.yml` and not checked in.

```
PB_RUNNER_SECRET=<secret> scripts/ios-runner.sh --simulator <udid>
PB_RUNNER_SECRET=<secret> scripts/ios-runner.sh --device <udid> --team <TEAMID>   # iPhone in Developer Mode
```

The script generates the project, runs `xcodebuild build-for-testing`, then `xcodebuild test-without-building`
for the one test, in the foreground until you press Ctrl-C. `--port` changes the port (default 8101). Simulator
builds are signed ad hoc; device builds use automatic signing with your team, and the bundle id gets your team id
appended so it doesn't collide with anyone else's. Normally the host (`scripts/ios-host.sh`) starts the runner for
you with a fresh secret.

By hand, in Xcode: `xcodegen generate`, open `PhoneBridgeRunner.xcodeproj`, and pass `TEST_RUNNER_PB_RUNNER_SECRET`
(and optionally `TEST_RUNNER_PB_RUNNER_PORT`) in the environment of `xcodebuild test`.

When the server is up the runner logs `PHONEBRIDGE_RUNNER_READY port=<port>`.

## Security

- The runner refuses to start without `PB_RUNNER_SECRET`, and answers `401` to any request without
  `Authorization: Bearer <secret>` (compared in constant time). The secret comes from the environment, never from
  the command line, and is never logged.
- It listens on every interface of the phone (a simulator shares the Mac's network), so anyone on the same network
  who learns the secret could control the phone. The host generates a long random secret per run; keep it that way.
- The runner does no policy. It does what an authorized request asks. The host checks the agent's grant, app
  access, pause and approvals before each call, as the Android app does before its accessibility service acts.
- It never reports the contents of secure text fields.

## Notes

- The foreground app comes from XCTest's own list of active applications (private API, looked up and checked at
  run time, as WebDriverAgent does). If that route is missing, the runner checks the `candidates` the host sends,
  then falls back to `com.apple.springboard`.
- XCTest has no public way to tell whether the screen is locked, so `/status` usually leaves `locked` out.
- Volume buttons don't exist on the simulator (`501`). `/lock` uses XCTest's private `pressLockButton`.
- Test issues (a tap XCTest couldn't perform) become `500` answers instead of failing the test.
