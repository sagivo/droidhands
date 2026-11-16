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
