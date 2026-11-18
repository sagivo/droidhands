# Releasing

Pushing a version tag publishes a release. [`.github/workflows/release.yml`](../.github/workflows/release.yml)
then builds and uploads:

| What | Where |
|---|---|
| Signed APK | GitHub Release `vX.Y.Z`: `DroidHands-X.Y.Z.apk` and the same file as `DroidHands.apk`, so `https://github.com/sagivo/droidhands/releases/latest/download/DroidHands.apk` always serves the newest release (the in-app update check and the relay's `APK_URL` use it) |
| Checksums | `SHA256SUMS` on the same release |
| Relay image | `ghcr.io/sagivo/droidhands-relay:X.Y.Z` and `:latest` (amd64 and arm64) |

The release runs the unit tests and plugin checks first. [`ci.yml`](../.github/workflows/ci.yml) runs
the same tests, the debug build and the relay tests on every push and pull request, without secrets.

## One-time setup

1. **Create the signing key** on a trusted computer:

   ```sh
   ./scripts/make-release-key.sh --repo sagivo/droidhands
   ```

   It writes `~/.droidhands/droidhands-release.jks` and its password file (both readable only by
   you), and prints the `gh` commands for the next step without running them. **Back up both files
   offline.** Android installs an update only if it is signed with the same key: if the key is lost,
   every user has to uninstall DroidHands (losing their agents and settings) to get a newer version.

2. **Add the secrets** (*Settings › Secrets and variables › Actions › Secrets*), or run the printed
   commands:

   | Secret | Value |
   |---|---|
   | `ANDROID_KEYSTORE_BASE64` | The keystore, base64-encoded on one line |
   | `ANDROID_KEYSTORE_PASSWORD` | The keystore password |
   | `ANDROID_KEY_ALIAS` | The key alias (`droidhands` by default) |
   | `ANDROID_KEY_PASSWORD` | The key password (the same as the keystore password for PKCS12) |

   If any is missing, the workflow stops with an error. A release is never signed with a debug key:
   the build also runs with `-Pdroidhands.requireReleaseSigning=true`, and the workflow refuses an
   APK whose certificate is a debug one.

3. **Optional: the default relay** (*Settings › Secrets and variables › Actions › Variables*):

   | Variable | Value |
   |---|---|
   | `DROIDHANDS_RELAY_URL` | Public URL of the relay new installs connect to on first run, e.g. `https://relay.example.com` |

   Without it, releases have no default relay and users connect their own. Run that relay first:
   [relay/README.md, "Run the default relay for your build"](../relay/README.md#run-the-default-relay-for-your-build).
   The app never replaces a relay that is already configured, so changing the variable only
   affects new installs.

4. **After the first release**, make the relay image public if others should pull it: *your profile
   (or organization) › Packages › droidhands-relay › Package settings › Change visibility*.

The workflow asks for the permissions it needs (`contents: write` for the release, `packages:
write` for the image). If your organization limits `GITHUB_TOKEN` to read-only, allow these under
*Settings › Actions › General › Workflow permissions*.

## Cutting a release

```sh
git checkout main && git pull
git tag v1.2.3
git push origin v1.2.3
```

* Tags look like `vMAJOR.MINOR.PATCH`, with MINOR and PATCH at most 99. Pre-release suffixes are
  not supported.
* The version comes only from the tag: `versionName` is `1.2.3` and `versionCode` is
  `MAJOR*10000 + MINOR*100 + PATCH` (`10203`). There is no version file to bump.
* Each tag must be higher than the previous one: Android refuses to install an older
  `versionCode` over a newer one.

Then, in the workflow run and on the release page:

1. Check that the "Default relay" step names the relay you expect.
2. Check the signer printed by "Verify the signature": its certificate SHA-256 digest should be the
   same digits as the SHA-256 `make-release-key.sh` printed. Consider adding it to the release
   notes, so users can check the APK with `apksigner verify --print-certs DroidHands.apk`.
3. Install the new APK over the previous release on a phone: it should update in place, keeping
   agents and settings. Phones on the previous release show "Update available" within a day.

## App stores

[`scripts/store-release.sh`](../scripts/store-release.sh) builds the signed release on your computer and
uploads it to Google Play and App Store Connect. The GitHub workflow above is separate: it still publishes the
APK. Both use the same version rule, so `v1.2.3` is `versionCode` 10203 everywhere.

```sh
git tag v1.2.3
./scripts/store-release.sh android                  # signed .aab → Google Play, internal track
./scripts/store-release.sh android --track beta --notes "What's new"
./scripts/store-release.sh ios                      # signed .ipa → App Store Connect / TestFlight
./scripts/store-release.sh all --build-only         # build and sign both, upload nothing
```

Settings come from environment variables or `~/.droidhands/store.env` (`KEY=VALUE` lines, `chmod 600`; the
file is parsed, never run, and environment variables win):

```sh
# Android signing: defaults to the key scripts/make-release-key.sh wrote
PB_KEYSTORE_PATH=~/.droidhands/droidhands-release.jks
DROIDHANDS_DEFAULT_RELAY_URL=https://connect.droidhands.com
# Google Play
PLAY_SERVICE_ACCOUNT_JSON=~/.droidhands/play-service-account.json
# App Store Connect
ASC_KEY_ID=ABC123DEFG
ASC_ISSUER_ID=00000000-0000-0000-0000-000000000000
ASC_KEY_PATH=~/.appstoreconnect/private_keys/AuthKey_ABC123DEFG.p8
APPLE_TEAM_ID=ABCDE12345
```

### Google Play, one-time setup

1. In the [Play Console](https://play.google.com/console), create the app (`com.droidhands.android`) and
   complete its store listing, content rating, data safety and app access forms. The API can't do this.
2. Make the first release by hand, or with `--draft` (an app that was never published only accepts drafts), and
   finish it in the console. After that the script can roll out to any track.
3. *Setup › API access*: link a Google Cloud project, create a service account there, create a JSON key for it,
   and invite its e-mail address under *Users and permissions* with permission to release the app.
4. **Signing key.** Play re-signs what you upload with an app signing key it holds. If you let Play create one,
   the Play build is signed differently from the GitHub APK, and a phone can't update from one to the other
   without uninstalling. To keep one identity, choose *Use a key exported from Java Keystore* when you enable
   Play App Signing and upload the release key's export (Play Console shows the PEPK command).
5. The script builds with the GitHub update check switched off (Play installs update through Play).

### App Store Connect, one-time setup

1. *Users and Access › Integrations › App Store Connect API*: create a key with the *App Manager* role,
   download `AuthKey_<id>.p8` (Apple lets you download it once), and note the key id and the issuer id.
2. Register the bundle id `com.droidhands.ios` (or your own, via `IOS_APP_BUNDLE_ID`) under *Certificates,
   Identifiers & Profiles › Identifiers*, then create the app in App Store Connect (*Apps › +*) with it. Automatic
   signing creates the distribution certificate and profile through the API key.
3. Fill in the listing, the App Privacy answers and the review notes from
   [clients/ios/app/APPSTORE.md](../clients/ios/app/APPSTORE.md). The privacy policy URL is
   `https://github.com/sagivo/droidhands/blob/main/docs/PRIVACY.md`. Screenshots: `scripts/ios-app.sh screenshots`
   on a 6.9" iPhone simulator.
4. After an upload, the build appears in TestFlight once Apple has processed it. Adding it to a version and
   submitting for review is done in App Store Connect.

The iOS lane builds DroidHands for iPhone (`clients/ios/app`, [docs/IOS.md](IOS.md)). It bakes in
`DROIDHANDS_DEFAULT_RELAY_URL` when set, otherwise `project.yml`'s default (`https://connect.droidhands.com`).
Unit tests run with `scripts/ios-app.sh test` (and in CI), not in this lane.

## Build inputs

The Android build reads these Gradle properties (`-Pname=value`) or environment variables. All are
optional; blank counts as unset.

| Property | Environment | Default | Meaning |
|---|---|---|---|
| `droidhands.versionName` | | `0.1.0` | `versionName`, e.g. `1.2.3` |
| `droidhands.versionCode` | | `1` | `versionCode` |
| `droidhands.defaultRelayUrl` | `DROIDHANDS_DEFAULT_RELAY_URL` | empty | `BuildConfig.DEFAULT_RELAY_URL`: the relay a fresh install connects to on first run |
| `droidhands.githubRepo` | `GITHUB_REPOSITORY` | empty | `BuildConfig.GITHUB_REPO` (`owner/name`): the repository whose releases the daily update check reads. Empty turns the check off. |
| `PB_KEYSTORE_PATH`, `PB_KEYSTORE_PASSWORD`, `PB_KEY_ALIAS`, `PB_KEY_PASSWORD` | same names | unset | Release signing key. All four or none. |
| `droidhands.requireReleaseSigning` | | `false` | `true`: fail instead of signing a release with the debug key |

Release APKs are named `DroidHands-<versionName>.apk`
(`clients/android/app/build/outputs/apk/release/`).

## Local release builds

Without a key, `./gradlew :app:assembleRelease` signs with your debug key and prints a warning.
That APK is fine for your own phone, but it can't update, or be updated by, a published release:
Android would ask to uninstall first. To sign with the release key on your computer, export the
four `PB_*` variables (`make-release-key.sh` prints the commands).

## If the key leaks or is lost

* **Leaked**: remove the GitHub secrets, tell users, and create a new key. Android's APK Signature
  Scheme v3 can rotate to a new key so that installed apps still accept updates
  (`apksigner rotate`, then sign with the lineage); this repository's workflow doesn't automate it.
* **Lost**: create a new key and release with it. Users must uninstall the old app and install the
  new one, then connect their agents again.
