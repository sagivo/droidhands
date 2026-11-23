#!/usr/bin/env bash
# Tests scripts/store-release.sh without Gradle, Xcode or the network: a fake gradlew, curl, jarsigner, xcodegen,
# xcodebuild and xcrun log their arguments, so version mapping, signing checks, the Google Play edit sequence
# and the App Store Connect upload are checked without building or uploading anything.
#
#   ./scripts/store-release.test.sh
#   SHOW_OUTPUT=1 ./scripts/store-release.test.sh     # also print each run's output
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUT="$SCRIPT_DIR/store-release.sh"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/store-release-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

PASSED=0
FAILED=0

command -v jq >/dev/null 2>&1 || { echo "jq is needed to run these tests"; exit 1; }
command -v openssl >/dev/null 2>&1 || { echo "openssl is needed to run these tests"; exit 1; }

mkdir -p "$WORK/bin" "$WORK/android" "$WORK/iosapp" "$WORK/home"
printf 'fake keystore' >"$WORK/release.jks"
printf 'name: DroidHands\n' >"$WORK/iosapp/project.yml"
printf 'fake p8' >"$WORK/AuthKey_KEY123.p8"
openssl genrsa -out "$WORK/sa.pem" 2048 2>/dev/null
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$WORK/dist.key" -subj "/CN=Apple Distribution: Test (ABCDE12345)" \
  -days 1 -set_serial 0x1A2B3C -out "$WORK/dist.pem" 2>/dev/null
printf 'fake p12' >"$WORK/dist.p12"
jq -n --rawfile key "$WORK/sa.pem" '{client_email: "uploader@example.iam.gserviceaccount.com", private_key: $key, token_uri: "https://oauth.test/token"}' \
  >"$WORK/service-account.json"

cat >"$WORK/android/gradlew" <<'EOF'
#!/usr/bin/env bash
printf 'gradlew%s\n' "$(printf ' [%s]' "$@")" >>"$FAKE_LOG"
printf 'env github_repository=[%s] keystore_password=[%s]\n' "${GITHUB_REPOSITORY:-}" "${PB_KEYSTORE_PASSWORD:-}" >>"$FAKE_LOG"
mkdir -p app/build/outputs/bundle/release
printf 'fake aab' >app/build/outputs/bundle/release/app-release.aab
EOF
cat >"$WORK/bin/jarsigner" <<'EOF'
#!/usr/bin/env bash
printf 'jarsigner%s\n' "$(printf ' [%s]' "$@")" >>"$FAKE_LOG"
echo "jar verified."
signer="${FAKE_SIGNER:-CN=DroidHands release}"
echo "  X.509, ${signer//_/ }"
EOF
cat >"$WORK/bin/curl" <<'EOF'
#!/usr/bin/env bash
# Logs the call, writes a canned answer to the -o file and prints the status for -w.
printf 'curl%s\n' "$(printf ' [%s]' "$@")" >>"$FAKE_LOG"
out="" url="" method=GET
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift 2 ;;
    -X) method="$2"; shift 2 ;;
    -w | -H | -d | --data-binary | --data-urlencode | --max-time) shift 2 ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
body='{}' code=200
case "$url" in
  */token) body='{"access_token":"tok-123"}' ;;
  */edits/edit-1/tracks) body="${FAKE_TRACKS:-"{}"}" ;;
  */edits/edit-1/bundles) [ "$method" = GET ] && body="${FAKE_BUNDLES:-"{}"}" ;;
  */bundles*) body='{"versionCode":10203}' ;;
  */edits) body='{"id":"edit-1"}' ;;
esac
if [ -n "${FAKE_PLAY_FAIL_ON:-}" ] && [[ "$url" == *"$FAKE_PLAY_FAIL_ON"* ]]; then
  body='{"error":{"message":"Only releases with status draft may be created on draft app."}}' code=400
fi
[ -z "$out" ] || printf '%s' "$body" >"$out"
printf '%s' "$code"
EOF
cat >"$WORK/bin/xcodegen" <<'EOF'
#!/usr/bin/env bash
printf 'xcodegen%s\n' "$(printf ' [%s]' "$@")" >>"$FAKE_LOG"
EOF
cat >"$WORK/bin/xcodebuild" <<'EOF'
#!/usr/bin/env bash
printf 'xcodebuild%s\n' "$(printf ' [%s]' "$@")" >>"$FAKE_LOG"
if [ "$1" = "-exportArchive" ]; then
  while [ $# -gt 0 ]; do
    [ "$1" = "-exportPath" ] && { mkdir -p "$2"; printf 'ipa' >"$2/DroidHands.ipa"; }
    shift
  done
fi
EOF
cat >"$WORK/bin/xcrun" <<'EOF'
#!/usr/bin/env bash
printf 'xcrun%s\n' "$(printf ' [%s]' "$@")" >>"$FAKE_LOG"
EOF
cat >"$WORK/bin/node" <<'EOF'
#!/usr/bin/env bash
# Stands in for node scripts/app-store-connect.mjs.
shift
printf 'asc%s\n' "$(printf ' [%s]' "$@")" >>"$FAKE_LOG"
printf 'asc-env key=[%s] issuer=[%s] path=[%s]\n' "$ASC_KEY_ID" "$ASC_ISSUER_ID" "$ASC_KEY_PATH" >>"$FAKE_LOG"
case "$1" in
  has-build) echo "${FAKE_HAS_BUILD:-no}" ;;
  profile)
    while [ $# -gt 0 ]; do [ "$1" = --out ] && printf 'profile' >"$2"; shift; done
    echo '{"name":"com.droidhands.ios App Store (CI)","uuid":"UUID-1"}'
    ;;
  submit)
    while [ $# -gt 0 ]; do [ "$1" = --notes-file ] && printf 'notes=[%s]\n' "$(cat "$2")" >>"$FAKE_LOG"; shift; done
    ;;
esac
EOF
cat >"$WORK/bin/security" <<'EOF'
#!/usr/bin/env bash
printf 'security%s\n' "$(printf ' [%s]' "$@")" >>"$FAKE_LOG"
case "$1" in
  list-keychains) [ $# -eq 3 ] && echo '    "/Users/me/Library/Keychains/login.keychain-db"' ;;
  find-certificate) cat "$FAKE_DIST_PEM" ;;
esac
exit 0
EOF
chmod +x "$WORK/android/gradlew" "$WORK/bin/"*

# run EXPECTED_STATUS ARGS...   (environment: EXTRA_ENV, extra VAR=value words for the run)
run() {
  local expected="$1"
  shift
  : >"$WORK/log"
  rm -rf "$WORK/out"
  local status=0
  # shellcheck disable=SC2086
  OUTPUT="$(env -i PATH="$PATH" HOME="$WORK/home" TMPDIR="${TMPDIR:-/tmp}" FAKE_LOG="$WORK/log" NO_COLOR=1 \
    DROIDHANDS_ANDROID_DIR="$WORK/android" DROIDHANDS_IOS_APP_DIR="$WORK/iosapp" DROIDHANDS_STORE_OUT="$WORK/out" \
    DROIDHANDS_STORE_ENV="$WORK/store.env" JAVA_HOME="" ANDROID_HOME="$WORK" JARSIGNER="$WORK/bin/jarsigner" CURL="$WORK/bin/curl" \
    XCODEGEN="$WORK/bin/xcodegen" XCODEBUILD="$WORK/bin/xcodebuild" XCRUN="$WORK/bin/xcrun" \
    NODE="$WORK/bin/node" SECURITY="$WORK/bin/security" FAKE_DIST_PEM="$WORK/dist.pem" \
    PB_KEYSTORE_PATH="$WORK/release.jks" PB_KEYSTORE_PASSWORD="ks-secret" \
    ${EXTRA_ENV:-} \
    "$SUT" "$@" 2>&1)" || status=$?
  LOG="$(cat "$WORK/log")"
  if [ -n "${SHOW_OUTPUT:-}" ]; then printf '%s\n' "$OUTPUT"; fi
  if [ "$status" != "$expected" ]; then
    fail "exit status $status, expected $expected for: $*"$'\n'"$OUTPUT"
    return 1
  fi
}

pass() {
  PASSED=$((PASSED + 1))
  printf 'ok   %s\n' "$1"
}
fail() {
  FAILED=$((FAILED + 1))
  printf 'FAIL %s\n' "$1"
}
expect_in() { # NAME HAYSTACK NEEDLE
  case "$2" in
    *"$3"*) pass "$1" ;;
    *) fail "$1: missing '$3' in:"$'\n'"$2" ;;
  esac
}
expect_not_in() {
  case "$2" in
    *"$3"*) fail "$1: unexpected '$3' in:"$'\n'"$2" ;;
    *) pass "$1" ;;
  esac
}

PLAY="PLAY_SERVICE_ACCOUNT_JSON=$WORK/service-account.json"
ASC="ASC_KEY_ID=KEY123 ASC_ISSUER_ID=issuer-uuid ASC_KEY_PATH=$WORK/AuthKey_KEY123.p8 APPLE_TEAM_ID=ABCDE12345"

# ----------------------------------------------------------------------------------- arguments
EXTRA_ENV=""
if run 0 --help; then expect_in "prints usage" "$OUTPUT" "Usage:"; fi
if run 1; then expect_in "needs a lane" "$OUTPUT" "android, ios or all"; fi
if run 1 android --bogus; then expect_in "rejects unknown options" "$OUTPUT" "Unknown option: --bogus"; fi
if run 1 android --track beta2 --version 1.2.3; then expect_in "rejects a bad track" "$OUTPUT" "--track must be"; fi
if run 1 android --version 1.2; then expect_in "rejects a bad version" "$OUTPUT" "must look like 1.2.3"; fi
if run 1 android --version 1.100.0; then expect_in "minor above 99" "$OUTPUT" "minor and patch at most 99"; fi
if run 1 android --version 0.0.0; then expect_in "rejects 0.0.0" "$OUTPUT" "can't be released"; fi
if run 1 android --build-only --notes a --notes-file /nonexistent; then expect_in "notes and file conflict" "$OUTPUT" "not both"; fi
if run 1 android --build-only; then expect_in "needs a version" "$OUTPUT" "no version tag"; fi

# --------------------------------------------------------------------------- android build only
EXTRA_ENV=""
if run 0 android --version 1.2.3 --build-only --relay-url https://relay.example.com; then
  expect_in "versionName" "$LOG" "[-Pdroidhands.versionName=1.2.3]"
  expect_in "versionCode is MAJOR*10000+MINOR*100+PATCH" "$LOG" "[-Pdroidhands.versionCode=10203]"
  expect_in "builds the bundle" "$LOG" "[:app:bundleRelease]"
  expect_in "runs the tests first" "$LOG" "[:core:test] [:core:checkPlugins] [:app:testDebugUnitTest] [:app:bundleRelease]"
  expect_in "requires release signing" "$LOG" "[-Pdroidhands.requireReleaseSigning=true]"
  expect_in "turns the GitHub update check off" "$LOG" "[-Pdroidhands.githubRepo=]"
  expect_in "default relay" "$LOG" "[-Pdroidhands.defaultRelayUrl=https://relay.example.com]"
  expect_in "keystore password reaches Gradle by environment" "$LOG" "keystore_password=[ks-secret]"
  expect_not_in "keystore password is not an argument" "$(grep -v '^env ' <<<"$LOG")" "ks-secret"
  expect_not_in "keystore password is not printed" "$OUTPUT" "ks-secret"
  expect_in "verifies the signature" "$LOG" "jarsigner [-verify] [-certs]"
  expect_in "names the artifact" "$OUTPUT" "DroidHands-1.2.3.aab"
  expect_in "build only uploads nothing" "$OUTPUT" "nothing was uploaded"
  expect_not_in "build only never calls curl" "$LOG" "curl "
  [ -f "$WORK/out/DroidHands-1.2.3.aab" ] && pass "copies the bundle to dist/store" || fail "bundle not copied"
fi
EXTRA_ENV="GITHUB_REPOSITORY=someone/else"
if run 0 android --version 1.2.3 --build-only --skip-tests; then
  expect_in "skip-tests drops the test tasks" "$LOG" "gradlew [:app:bundleRelease]"
  expect_in "GITHUB_REPOSITORY is removed from Gradle's environment" "$LOG" "github_repository=[]"
  expect_in "warns about a missing relay" "$OUTPUT" "no default relay"
fi
EXTRA_ENV="FAKE_SIGNER=CN=Android_Debug"
if run 1 android --version 1.2.3 --build-only --skip-tests; then expect_in "refuses a debug-signed bundle" "$OUTPUT" "signed with a debug key"; fi
EXTRA_ENV="PB_KEYSTORE_PATH=$WORK/missing.jks"
if run 1 android --version 1.2.3 --build-only; then expect_in "missing keystore" "$OUTPUT" "No release keystore"; fi

# ------------------------------------------------------------------------------- google play
EXTRA_ENV=""
if run 1 android --version 1.2.3 --allow-dirty; then expect_in "upload needs the service account" "$OUTPUT" "PLAY_SERVICE_ACCOUNT_JSON"; fi
EXTRA_ENV="$PLAY"
if run 0 android --version 1.2.3 --allow-dirty --notes "First test build" --relay-url https://relay.example.com; then
  expect_in "logs in with a signed JWT" "$LOG" "[--data-urlencode] [grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer]"
  expect_in "opens an edit" "$LOG" "/applications/com.droidhands.android/edits]"
  expect_in "uploads the bundle" "$LOG" "/upload/androidpublisher/v3/applications/com.droidhands.android/edits/edit-1/bundles?uploadType=media]"
  expect_in "uploads the bundle file" "$LOG" "[--data-binary] [@$WORK/out/DroidHands-1.2.3.aab]"
  expect_in "sets the internal track" "$LOG" "/edits/edit-1/tracks/internal]"
  expect_in "track release body" "$LOG" '"status":"completed"'
  expect_in "release notes" "$LOG" '"releaseNotes":[{"language":"en-US","text":"First test build"}]'
  expect_in "commits the edit" "$LOG" "/edits/edit-1:commit]"
  expect_not_in "no edit is deleted after a commit" "$LOG" "[DELETE]"
  expect_not_in "the access token is not an argument" "$LOG" "tok-123"
  expect_in "reports the track" "$OUTPUT" "is on the internal track"
fi
if run 0 android --version 1.2.3 --allow-dirty --draft --track beta --relay-url https://relay.example.com; then
  expect_in "draft status" "$LOG" '"status":"draft"'
  expect_in "chosen track" "$LOG" "/tracks/beta]"
  expect_in "draft guidance" "$OUTPUT" "Finish it in the Play Console"
fi
EXTRA_ENV="$PLAY FAKE_TRACKS={\"tracks\":[{\"track\":\"internal\",\"releases\":[{\"versionCodes\":[\"10203\"]}]}]}"
if run 0 android --version 1.2.3 --allow-dirty --relay-url https://relay.example.com; then
  expect_in "a release already on the track is left alone" "$OUTPUT" "already on the internal track"
  expect_not_in "nothing is uploaded again" "$LOG" "uploadType=media"
  expect_not_in "nothing is committed" "$LOG" ":commit]"
  expect_in "the unused edit is deleted" "$LOG" "[DELETE]"
fi
EXTRA_ENV="$PLAY FAKE_BUNDLES={\"bundles\":[{\"versionCode\":10203}]}"
if run 0 android --version 1.2.3 --allow-dirty --relay-url https://relay.example.com; then
  expect_in "an uploaded bundle is reused" "$OUTPUT" "reusing it"
  expect_not_in "the bundle isn't uploaded twice" "$LOG" "uploadType=media"
  expect_in "it still goes on the track" "$LOG" "/edits/edit-1/tracks/internal]"
  expect_in "and the edit is committed" "$LOG" "/edits/edit-1:commit]"
fi
EXTRA_ENV="$PLAY"
if run 1 android --version 1.2.3 --allow-dirty --track production --relay-url https://relay.example.com; then
  expect_in "production needs --yes without a terminal" "$OUTPUT" "needs --yes"
fi
if run 0 android --version 1.2.3 --allow-dirty --track production --yes --relay-url https://relay.example.com; then
  expect_in "production with --yes" "$LOG" "/tracks/production]"
fi
EXTRA_ENV="$PLAY FAKE_PLAY_FAIL_ON=/tracks/"
if run 1 android --version 1.2.3 --allow-dirty --relay-url https://relay.example.com; then
  expect_in "shows Google's error" "$OUTPUT" "Only releases with status draft may be created on draft app."
  expect_in "deletes the uncommitted edit" "$LOG" "[DELETE]"
  expect_not_in "no commit after an error" "$LOG" ":commit]"
fi
printf '{"not":"a key"}' >"$WORK/bad.json"
EXTRA_ENV="PLAY_SERVICE_ACCOUNT_JSON=$WORK/bad.json"
if run 1 android --version 1.2.3 --allow-dirty; then expect_in "rejects a file that is not a service account key" "$OUTPUT" "not a service account key"; fi

# ------------------------------------------------------------------------------ settings file
printf '# comment\nPLAY_SERVICE_ACCOUNT_JSON="%s"\nPB_KEY_ALIAS = ignored-spaces\nUNKNOWN_KEY=1\n' "$WORK/service-account.json" >"$WORK/store.env"
chmod 600 "$WORK/store.env"
EXTRA_ENV=""
if run 0 android --version 1.2.3 --allow-dirty --relay-url https://relay.example.com; then
  expect_in "settings file supplies the Play key" "$LOG" "/edits/edit-1:commit]"
fi
EXTRA_ENV="PLAY_SERVICE_ACCOUNT_JSON=$WORK/bad.json"
if run 1 android --version 1.2.3 --allow-dirty; then expect_in "environment beats the settings file" "$OUTPUT" "not a service account key"; fi
chmod 644 "$WORK/store.env"
EXTRA_ENV=""
if run 0 android --version 1.2.3 --allow-dirty --relay-url https://relay.example.com; then
  expect_in "warns about a readable settings file" "$OUTPUT" "readable by others"
fi
rm -f "$WORK/store.env"

# ------------------------------------------------------------------------------------- iOS
EXTRA_ENV=""
if run 1 ios --version 1.2.3 --build-only; then expect_in "ios needs a team" "$OUTPUT" "APPLE_TEAM_ID"; fi
EXTRA_ENV="APPLE_TEAM_ID=ABCDE12345"
if run 1 ios --version 1.2.3 --build-only; then expect_in "ios needs a key id" "$OUTPUT" "ASC_KEY_ID"; fi
EXTRA_ENV="$ASC ASC_KEY_PATH=$WORK/missing.p8"
if run 1 ios --version 1.2.3 --build-only; then expect_in "ios needs the key file" "$OUTPUT" "No API key file"; fi
EXTRA_ENV="$ASC"
if run 0 ios --version 1.2.3 --build-only; then
  expect_in "generates the project" "$LOG" "xcodegen [generate] [--spec] [$WORK/iosapp/project.yml]"
  expect_in "archives for devices" "$LOG" "xcodebuild [archive]"
  expect_in "generic iOS destination" "$LOG" "[-destination] [generic/platform=iOS]"
  expect_in "marketing version" "$LOG" "[MARKETING_VERSION=1.2.3]"
  expect_in "build number defaults to the versionCode" "$LOG" "[CURRENT_PROJECT_VERSION=10203]"
  expect_in "team" "$LOG" "[DEVELOPMENT_TEAM=ABCDE12345]"
  expect_in "API key signing" "$LOG" "[-authenticationKeyID] [KEY123] [-authenticationKeyIssuerID] [issuer-uuid]"
  expect_in "exports an ipa" "$LOG" "xcodebuild [-exportArchive]"
  expect_in "the app's project and scheme" "$LOG" "[-project] [$WORK/iosapp/DroidHands.xcodeproj] [-scheme] [DroidHands]"
  expect_in "bundle id" "$LOG" "[PB_APP_BUNDLE_ID=com.droidhands.ios]"
  expect_in "build only uploads nothing" "$OUTPUT" "nothing was uploaded"
  expect_not_in "build only never calls altool" "$LOG" "xcrun "
  expect_in "export options" "$(cat "$WORK/out/ios/ExportOptions.plist")" "<string>app-store-connect</string>"
fi
if run 0 ios --version 1.2.3 --allow-dirty --build-number 77; then
  expect_in "custom build number" "$LOG" "[CURRENT_PROJECT_VERSION=77]"
  expect_in "asks App Store Connect for the build first" "$LOG" "asc [has-build] [--bundle-id] [com.droidhands.ios] [--version] [1.2.3] [--build] [77]"
  expect_in "passes the API key to the helper" "$LOG" "asc-env key=[KEY123] issuer=[issuer-uuid] path=[$WORK/AuthKey_KEY123.p8]"
  expect_in "uploads with altool" "$LOG" "xcrun [altool] [--upload-app] [-f] [$WORK/out/ios/export/DroidHands.ipa] [--type] [ios] [--apiKey] [KEY123] [--apiIssuer] [issuer-uuid] [--p8-file-path] [$WORK/AuthKey_KEY123.p8]"
  expect_not_in "no submission without --submit" "$LOG" "asc [submit]"
  expect_in "says how to submit" "$OUTPUT" "--submit"
fi
if run 1 ios --version 1.2.3 --wait-minutes soon; then expect_in "wait minutes must be a number" "$OUTPUT" "--wait-minutes must be"; fi
if run 0 ios --version 1.2.3 --allow-dirty --submit --wait-minutes 5 --notes "Faster pairing"; then
  expect_in "submits for review after the upload" "$LOG" "asc [submit] [--bundle-id] [com.droidhands.ios] [--version] [1.2.3] [--build] [10203] [--wait-minutes] [5] [--notes-file]"
  expect_in "notes become What's New" "$LOG" "notes=[Faster pairing]"
  expect_in "upload comes before the submission" "$(grep -E '^(xcrun|asc \[submit)' "$WORK/log" | head -n 1)" "xcrun [altool]"
fi
EXTRA_ENV="$ASC FAKE_HAS_BUILD=yes"
if run 0 ios --version 1.2.3 --allow-dirty --submit; then
  expect_in "an uploaded build is not built again" "$OUTPUT" "already has 1.2.3 (10203)"
  expect_not_in "no archive" "$LOG" "xcodebuild [archive]"
  expect_not_in "no upload" "$LOG" "xcrun [altool]"
  expect_in "still submits it" "$LOG" "asc [submit]"
fi
EXTRA_ENV="$ASC IOS_DISTRIBUTION_P12=$WORK/dist.p12"
if run 1 ios --version 1.2.3 --build-only; then expect_in "the certificate needs its password" "$OUTPUT" "IOS_DISTRIBUTION_P12_PASSWORD"; fi
EXTRA_ENV="$ASC IOS_DISTRIBUTION_P12=$WORK/dist.p12 IOS_DISTRIBUTION_P12_PASSWORD=p12-secret"
if run 0 ios --version 1.2.3 --allow-dirty; then
  expect_in "imports the certificate into its own keychain" "$LOG" "security [import] [$WORK/dist.p12] [-k] ["
  expect_in "fetches the profile for that certificate" "$LOG" "asc [profile] [--bundle-id] [com.droidhands.ios] [--certificate-serial] [1A2B3C]"
  [ -f "$WORK/home/Library/MobileDevice/Provisioning Profiles/UUID-1.mobileprovision" ] && pass "installs the profile" || fail "profile not installed"
  expect_in "manual signing" "$LOG" "[CODE_SIGN_STYLE=Manual] [CODE_SIGN_IDENTITY=Apple Distribution] [PROVISIONING_PROFILE_SPECIFIER=com.droidhands.ios App Store (CI)]"
  expect_not_in "no provisioning updates when signing manually" "$(grep '^xcodebuild' "$WORK/log")" "-allowProvisioningUpdates"
  expect_in "export names the profile" "$(cat "$WORK/out/ios/ExportOptions.plist")" "<key>com.droidhands.ios</key><string>com.droidhands.ios App Store (CI)</string>"
  expect_in "export signs manually" "$(cat "$WORK/out/ios/ExportOptions.plist")" "<string>manual</string>"
  expect_in "restores the keychain list" "$LOG" "security [list-keychains] [-d] [user] [-s] [/Users/me/Library/Keychains/login.keychain-db]"
  expect_in "deletes the signing keychain" "$LOG" "security [delete-keychain]"
  expect_not_in "the certificate password is not printed" "$OUTPUT" "p12-secret"
fi

# ------------------------------------------------------------------------------------- all
EXTRA_ENV="$PLAY"
if run 1 all --version 1.2.3 --allow-dirty --relay-url https://relay.example.com; then
  expect_in "all checks iOS settings before anything is built" "$OUTPUT" "APPLE_TEAM_ID"
  expect_not_in "nothing was built or uploaded" "$LOG" "gradlew"
fi
EXTRA_ENV="$PLAY $ASC"
if run 0 all --version 1.2.3 --allow-dirty --relay-url https://relay.example.com; then
  expect_in "all uploads to Play" "$LOG" "/edits/edit-1:commit]"
  expect_in "all uploads to App Store Connect" "$LOG" "xcrun [altool]"
fi

printf '\n%d passed, %d failed\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
