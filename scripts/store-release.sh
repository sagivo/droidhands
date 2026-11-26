#!/usr/bin/env bash
# Builds DroidHands and uploads it to Google Play (Android App Bundle) and App Store Connect (iOS, for
# TestFlight and App Review). See docs/RELEASING.md, "App stores".
#
#   ./scripts/store-release.sh android [--track internal|alpha|beta|production] [--draft] [--notes TEXT]
#   ./scripts/store-release.sh ios [--build-number N] [--submit]
#   ./scripts/store-release.sh all
#   ./scripts/store-release.sh <lane> --build-only      # build and sign, upload nothing
#   ./scripts/store-release.sh all --only-if-changed    # skip a store whose app is the same as what it has
#
# The version comes from the git tag on HEAD (v1.2.3), or --version 1.2.3, and maps to the same
# versionCode (MAJOR*10000 + MINOR*100 + PATCH) as the GitHub release workflow, so numbers never
# disagree between channels. Settings come from environment variables or from ~/.droidhands/store.env
# (KEY=VALUE lines, never executed; environment variables win). Run with --help for the list.
#
# Needs: Android: a JDK 17, the Android SDK, curl, jq, openssl. iOS: macOS with Xcode and xcodegen.
# Works with macOS's bash 3.2. NO_COLOR=1 disables colors.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# The git checkout --only-if-changed compares (tests point it at a scratch repository).
SOURCE_REPO="${DROIDHANDS_SOURCE_REPO:-$REPO_ROOT}"
ANDROID_DIR="${DROIDHANDS_ANDROID_DIR:-$REPO_ROOT/clients/android}"
IOS_APP_DIR="${DROIDHANDS_IOS_APP_DIR:-$REPO_ROOT/clients/ios/app}"
OUT_DIR="${DROIDHANDS_STORE_OUT:-$REPO_ROOT/dist/store}"
STORE_ENV="${DROIDHANDS_STORE_ENV:-$HOME/.droidhands/store.env}"
readonly IOS_SCHEME="DroidHands"
readonly PLAY_API="https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
readonly PLAY_UPLOAD="https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"

usage() {
  cat <<EOF
Usage: $(basename "$0") (android | ios | all) [options]

Builds the signed release and uploads it: android → Google Play, ios → App Store Connect (TestFlight).

  --version X.Y.Z       Version to release (default: the vX.Y.Z tag on HEAD).
  --build-only          Build and sign, upload nothing. Artifacts land in dist/store/.
  --skip-tests          Android: skip the unit tests and plugin checks that run before the build.
  --allow-dirty         Upload from a working tree with uncommitted changes (not recommended).
  --yes                 Don't ask before publishing to Google Play's production track.
  --only-if-changed     Ask each store which version it already has, and skip that store when nothing that
                        goes into its app changed since that version's tag (the release workflow does this).
                        Build settings such as the relay URL aren't compared: leave this out to ship those.

Android:
  --track NAME          Play track: internal (default), alpha, beta or production.
  --draft               Create the release as a draft, to finish and roll out in the Play Console. A new
                        app's first release must be a draft.
  --notes TEXT          Release notes (at most 500 characters), or --notes-file FILE.
  --notes-language TAG  Language of the notes (default en-US).
  --relay-url URL       Relay a fresh install connects to (default: DROIDHANDS_DEFAULT_RELAY_URL).

iOS:
  --build-number N      CFBundleVersion (default: the versionCode). App Store Connect wants a higher one
                        for every upload of the same version.
  --submit              After the upload, wait for Apple to process the build, then put it on the App Store
                        version and submit it for review (released when approved). --notes become "What's New".
  --wait-minutes N      How long --submit waits for Apple's processing (default 60).

Settings (environment or $STORE_ENV):
  Android signing   PB_KEYSTORE_PATH, PB_KEYSTORE_PASSWORD, PB_KEY_ALIAS, PB_KEY_PASSWORD. Defaults to the
                    files scripts/make-release-key.sh writes (~/.droidhands/droidhands-release.jks).
  Google Play       PLAY_SERVICE_ACCOUNT_JSON (path to a service account key that is invited in the Play
                    Console with permission to release this app), PLAY_PACKAGE_NAME (default
                    com.droidhands.android).
  App Store Connect ASC_KEY_ID, ASC_ISSUER_ID, ASC_KEY_PATH (the AuthKey_<id>.p8 file; default
                    ~/.appstoreconnect/private_keys/AuthKey_<ASC_KEY_ID>.p8), APPLE_TEAM_ID (10 characters),
                    IOS_APP_BUNDLE_ID (default com.droidhands.ios).
  iOS signing       IOS_DISTRIBUTION_P12 and IOS_DISTRIBUTION_P12_PASSWORD: sign with this Apple Distribution
                    certificate and an App Store profile fetched through the API (for CI). Unset: Xcode's
                    automatic signing with the certificates in your keychain.
  Build             DROIDHANDS_DEFAULT_RELAY_URL, DROIDHANDS_API_URL (the accounts server; unset builds both
                    apps without accounts, which the store listings and privacy answers assume).
EOF
}

# ---------------------------------------------------------------------------------------------- ui

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_GREEN=$'\033[32m' C_YELLOW=$'\033[33m' C_RED=$'\033[31m' C_BOLD=$'\033[1m' C_RESET=$'\033[0m'
else
  C_GREEN="" C_YELLOW="" C_RED="" C_BOLD="" C_RESET=""
fi

say() { printf '%s\n' "$*"; }
ok() { printf '%s✓%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%s! %s%s\n' "$C_YELLOW" "$*" "$C_RESET" >&2; }
die() {
  printf '%s✗ %s%s\n' "$C_RED" "$*" "$C_RESET" >&2
  exit 1
}

WORK="$(mktemp -d "${TMPDIR:-/tmp}/store-release.XXXXXX")"
EDIT_ID=""
PLAY_PACKAGE=""
cleanup() {
  # A Play edit that was never committed is discarded; deleting it is only tidiness.
  if [ -n "$EDIT_ID" ] && [ -f "$WORK/auth" ]; then
    "${CURL:-curl}" -s -o /dev/null -X DELETE -H "@$WORK/auth" "$PLAY_API/$PLAY_PACKAGE/edits/$EDIT_ID" || true
  fi
  [ -z "${KEYCHAIN:-}" ] || ios_keychain_cleanup
  rm -rf "$WORK"
}
trap cleanup EXIT

# ------------------------------------------------------------------------------------------ config

# Reads KEY=VALUE lines of the settings file into variables that aren't set yet. The file is parsed, never
# sourced, and only known keys count.
load_settings() {
  [ -f "$STORE_ENV" ] || return 0
  if [ -n "$(find "$STORE_ENV" \( -perm -040 -o -perm -004 -o -perm -020 -o -perm -002 \) 2>/dev/null)" ]; then
    warn "$STORE_ENV holds secrets and is readable by others. Fix it with: chmod 600 $STORE_ENV"
  fi
  local key value
  while IFS='=' read -r key value || [ -n "$key" ]; do
    key="${key#"${key%%[![:space:]]*}"}"
    case "$key" in
      '' | '#'*) continue ;;
      PB_KEYSTORE_PATH | PB_KEYSTORE_PASSWORD | PB_KEY_ALIAS | PB_KEY_PASSWORD | PLAY_SERVICE_ACCOUNT_JSON | \
        PLAY_PACKAGE_NAME | ASC_KEY_ID | ASC_ISSUER_ID | ASC_KEY_PATH | APPLE_TEAM_ID | IOS_APP_BUNDLE_ID | \
        IOS_DISTRIBUTION_P12 | IOS_DISTRIBUTION_P12_PASSWORD | DROIDHANDS_DEFAULT_RELAY_URL | DROIDHANDS_API_URL) ;;
      *) continue ;;
    esac
    value="${value%"${value##*[![:space:]]}"}"
    case "$value" in
      \"*\") value="${value#\"}" value="${value%\"}" ;;
      \'*\') value="${value#\'}" value="${value%\'}" ;;
    esac
    case "$value" in
      '~/'*) value="$HOME/${value#\~/}" ;;
    esac
    if [ -z "${!key:-}" ] && [ -n "$value" ]; then
      printf -v "$key" '%s' "$value"
      export "${key?}"
    fi
  done <"$STORE_ENV"
}

need_value() { [ $# -ge 2 ] && [ -n "$2" ] || die "$1 needs a value (see --help)"; }

LANE=""
VERSION=""
BUILD_ONLY=0
SKIP_TESTS=0
ALLOW_DIRTY=0
ASSUME_YES=0
ONLY_IF_CHANGED=0
TRACK="internal"
DRAFT=0
NOTES=""
NOTES_FILE=""
NOTES_LANGUAGE="en-US"
BUILD_NUMBER=""
SUBMIT=0
WAIT_MINUTES=60

while [ $# -gt 0 ]; do
  case "$1" in
    android | ios | all)
      [ -z "$LANE" ] || die "Name the lane once (android, ios or all)."
      LANE="$1"
      shift
      ;;
    --version) need_value "$@"; VERSION="$2"; shift 2 ;;
    --build-only) BUILD_ONLY=1; shift ;;
    --skip-tests) SKIP_TESTS=1; shift ;;
    --allow-dirty) ALLOW_DIRTY=1; shift ;;
    --yes) ASSUME_YES=1; shift ;;
    --only-if-changed) ONLY_IF_CHANGED=1; shift ;;
    --track) need_value "$@"; TRACK="$2"; shift 2 ;;
    --draft) DRAFT=1; shift ;;
    --notes) need_value "$@"; NOTES="$2"; shift 2 ;;
    --notes-file) need_value "$@"; NOTES_FILE="$2"; shift 2 ;;
    --notes-language) need_value "$@"; NOTES_LANGUAGE="$2"; shift 2 ;;
    --relay-url) need_value "$@"; DROIDHANDS_DEFAULT_RELAY_URL="$2"; export DROIDHANDS_DEFAULT_RELAY_URL; shift 2 ;;
    --build-number) need_value "$@"; BUILD_NUMBER="$2"; shift 2 ;;
    --submit) SUBMIT=1; shift ;;
    --wait-minutes) need_value "$@"; WAIT_MINUTES="$2"; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; die "Unknown option: $1" ;;
  esac
done

[ -n "$LANE" ] || { usage >&2; die "Say what to release: android, ios or all."; }
case "$TRACK" in
  internal | alpha | beta | production) ;;
  *) die "--track must be internal, alpha, beta or production." ;;
esac
case "$BUILD_NUMBER" in
  *[!0-9]*) die "--build-number must be a whole number." ;;
esac
case "$WAIT_MINUTES" in
  '' | *[!0-9]*) die "--wait-minutes must be a whole number." ;;
esac
if [ -n "$NOTES_FILE" ]; then
  [ -z "$NOTES" ] || die "Use --notes or --notes-file, not both."
  [ -f "$NOTES_FILE" ] || die "No such notes file: $NOTES_FILE"
  NOTES="$(cat "$NOTES_FILE")"
fi
[ "$BUILD_ONLY" -eq 0 ] || [ "$ONLY_IF_CHANGED" -eq 0 ] || die "--only-if-changed asks the stores, so it can't be used with --build-only."
[ "${#NOTES}" -le 500 ] || die "Play release notes are limited to 500 characters; these are ${#NOTES}."

load_settings

# ---------------------------------------------------------------------------------------- version

if [ -z "$VERSION" ]; then
  TAG="$(git -C "$REPO_ROOT" describe --exact-match --tags --match 'v*' HEAD 2>/dev/null || true)"
  [ -n "$TAG" ] || die "HEAD has no version tag. Tag it (git tag v1.2.3) or pass --version 1.2.3."
  VERSION="${TAG#v}"
fi
# The same rule as .github/workflows/release.yml.
if [[ ! "$VERSION" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]]; then
  die "The version must look like 1.2.3 (minor and patch at most 99), got '$VERSION'."
fi
VERSION_CODE=$((BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3]))
[ "$VERSION_CODE" -ge 1 ] || die "Version 0.0.0 can't be released."

if [ "$BUILD_ONLY" -eq 0 ] && [ "$ALLOW_DIRTY" -eq 0 ] && [ -n "$(git -C "$REPO_ROOT" status --porcelain 2>/dev/null)" ]; then
  die "The working tree has uncommitted changes. Commit them (the store build should match a commit), or pass --allow-dirty."
fi

if [ "$BUILD_ONLY" -eq 0 ] && [ "$TRACK" = production ] && { [ "$LANE" = android ] || [ "$LANE" = all ]; } && [ "$DRAFT" -eq 0 ]; then
  if [ "$ASSUME_YES" -eq 0 ]; then
    [ -t 0 ] || die "Publishing to production needs --yes when there is no terminal to ask on."
    printf 'Roll %s (versionCode %s) out to ALL Google Play users on production? Type "production" to continue: ' "$VERSION" "$VERSION_CODE"
    read -r answer
    [ "$answer" = production ] || die "Cancelled."
  fi
fi

mkdir -p "$OUT_DIR"
say "${C_BOLD}DroidHands $VERSION${C_RESET} (versionCode $VERSION_CODE), lane: $LANE$([ "$BUILD_ONLY" -eq 1 ] && printf ', build only')"

# ---------------------------------------------------------------------------------------- android

# Prints the first existing path of the arguments, or nothing.
first_dir() {
  local dir
  for dir in "$@"; do
    if [ -n "$dir" ] && [ -d "$dir" ]; then
      printf '%s' "$dir"
      return 0
    fi
  done
}

android_prepare() {
  if [ -z "${JAVA_HOME:-}" ]; then
    local jdk
    jdk="$(first_dir /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17)"
    if [ -n "$jdk" ]; then JAVA_HOME="$jdk"; export JAVA_HOME; fi
  fi
  command -v "${JAVA_HOME:+$JAVA_HOME/bin/}java" >/dev/null 2>&1 || die "No JDK found. Install JDK 17 (brew install openjdk@17) or set JAVA_HOME."
  if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ] && ! grep -qs '^sdk.dir=' "$ANDROID_DIR/local.properties"; then
    local sdk
    sdk="$(first_dir "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools)"
    [ -n "$sdk" ] || die "No Android SDK found. Set ANDROID_HOME, or sdk.dir in clients/android/local.properties."
    ANDROID_HOME="$sdk"
    export ANDROID_HOME
  fi
  [ -x "$ANDROID_DIR/gradlew" ] || die "No Gradle wrapper at $ANDROID_DIR/gradlew."

  # The defaults are the files scripts/make-release-key.sh writes.
  local default_keystore="$HOME/.droidhands/droidhands-release.jks"
  : "${PB_KEYSTORE_PATH:=$default_keystore}"
  : "${PB_KEY_ALIAS:=droidhands}"
  if [ -z "${PB_KEYSTORE_PASSWORD:-}" ] && [ -f "$PB_KEYSTORE_PATH.password" ]; then
    PB_KEYSTORE_PASSWORD="$(cat "$PB_KEYSTORE_PATH.password")"
  fi
  : "${PB_KEY_PASSWORD:=${PB_KEYSTORE_PASSWORD:-}}"
  [ -f "$PB_KEYSTORE_PATH" ] || die "No release keystore at $PB_KEYSTORE_PATH. Create one with scripts/make-release-key.sh, or set PB_KEYSTORE_PATH."
  [ -n "$PB_KEYSTORE_PASSWORD" ] || die "Set PB_KEYSTORE_PASSWORD (and PB_KEY_PASSWORD) for $PB_KEYSTORE_PATH."
  export PB_KEYSTORE_PATH PB_KEYSTORE_PASSWORD PB_KEY_ALIAS PB_KEY_PASSWORD

  if [ -z "${DROIDHANDS_DEFAULT_RELAY_URL:-}" ]; then
    warn "No relay URL (DROIDHANDS_DEFAULT_RELAY_URL or --relay-url): this build has no default relay, so a fresh install can't connect on its own."
  fi
}

play_prepare() {
  PLAY_PACKAGE="${PLAY_PACKAGE_NAME:-com.droidhands.android}"
  [ -n "${PLAY_SERVICE_ACCOUNT_JSON:-}" ] || die "Set PLAY_SERVICE_ACCOUNT_JSON to the service account key file (Play Console > Setup > API access)."
  [ -f "$PLAY_SERVICE_ACCOUNT_JSON" ] || die "No such file: $PLAY_SERVICE_ACCOUNT_JSON"
  command -v "${CURL:=curl}" >/dev/null 2>&1 || die "curl not found."
  command -v jq >/dev/null 2>&1 || die "jq not found (brew install jq)."
  command -v openssl >/dev/null 2>&1 || die "openssl not found."
  jq -e '.client_email and .private_key' "$PLAY_SERVICE_ACCOUNT_JSON" >/dev/null 2>&1 ||
    die "$PLAY_SERVICE_ACCOUNT_JSON is not a service account key (it needs client_email and private_key)."
}

android_build() {
  say "Building the Android App Bundle…"
  # The store edition (docs/EDITIONS.md): no accessibility service, so it meets Play's Accessibility API policy.
  local tasks=(:app:bundleStoreRelease)
  if [ "$SKIP_TESTS" -eq 0 ]; then
    tasks=(:core:test :core:checkPlugins :app:testStoreDebugUnitTest "${tasks[@]}")
  fi
  # The in-app update check reads GitHub releases and points to an APK signed with another key; Play
  # installs must update through Play, so the Play build has the check off (no repository, no GITHUB_REPOSITORY).
  (
    cd "$ANDROID_DIR"
    env -u GITHUB_REPOSITORY ./gradlew "${tasks[@]}" \
      "-Pdroidhands.versionName=$VERSION" "-Pdroidhands.versionCode=$VERSION_CODE" \
      -Pdroidhands.requireReleaseSigning=true "-Pdroidhands.githubRepo=" \
      ${DROIDHANDS_DEFAULT_RELAY_URL:+"-Pdroidhands.defaultRelayUrl=$DROIDHANDS_DEFAULT_RELAY_URL"} \
      ${DROIDHANDS_API_URL:+"-Pdroidhands.apiUrl=$DROIDHANDS_API_URL"} \
      --console=plain
  ) || die "The Android build failed (see Gradle's output above)."

  local built="$ANDROID_DIR/app/build/outputs/bundle/storeRelease/app-store-release.aab"
  [ -f "$built" ] || die "Gradle finished but $built is missing."
  AAB="$OUT_DIR/DroidHands-$VERSION.aab"
  cp "$built" "$AAB"

  local jarsigner="${JARSIGNER:-${JAVA_HOME:+$JAVA_HOME/bin/}jarsigner}"
  if command -v "$jarsigner" >/dev/null 2>&1; then
    "$jarsigner" -verify -certs "$AAB" >"$WORK/jarsigner.txt" 2>&1 || die "The bundle's signature does not verify:"$'\n'"$(cat "$WORK/jarsigner.txt")"
    if grep -q "CN=Android Debug" "$WORK/jarsigner.txt"; then
      die "The bundle is signed with a debug key. Refusing to upload it."
    fi
  else
    warn "jarsigner not found, so the bundle's signature wasn't checked."
  fi
  ok "Built $AAB"
}

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# Exchanges the service account key for an access token and keeps it in a header file (so it never shows in
# a process list).
play_login() {
  local email token_uri now unsigned
  email="$(jq -r .client_email "$PLAY_SERVICE_ACCOUNT_JSON")"
  token_uri="$(jq -r '.token_uri // "https://oauth2.googleapis.com/token"' "$PLAY_SERVICE_ACCOUNT_JSON")"
  now="$(date +%s)"
  unsigned="$(printf '%s' '{"alg":"RS256","typ":"JWT"}' | b64url).$(
    jq -nc --arg iss "$email" --arg aud "$token_uri" --argjson iat "$now" \
      '{iss: $iss, scope: "https://www.googleapis.com/auth/androidpublisher", aud: $aud, iat: $iat, exp: ($iat + 3600)}' | b64url
  )"
  (umask 077 && jq -r .private_key "$PLAY_SERVICE_ACCOUNT_JSON" >"$WORK/sa.pem")
  local signature
  signature="$(printf '%s' "$unsigned" | openssl dgst -sha256 -sign "$WORK/sa.pem" | b64url)" || die "Could not sign the login request with the service account key."
  rm -f "$WORK/sa.pem"
  local code
  code="$("$CURL" -sS -o "$WORK/resp" -w '%{http_code}' -X POST "$token_uri" \
    --data-urlencode "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer" \
    --data-urlencode "assertion=$unsigned.$signature")" || die "Could not reach $token_uri."
  [ "$code" = 200 ] || die "Google refused the service account login ($code): $(jq -r '.error_description // .error // empty' "$WORK/resp" 2>/dev/null)"
  (umask 077 && printf 'Authorization: Bearer %s\n' "$(jq -r .access_token "$WORK/resp")" >"$WORK/auth")
}

# play_try METHOD URL [curl args…]: like play, but sets PLAY_CODE to the HTTP status instead of stopping on errors.
play_try() {
  local method="$1" url="$2"
  shift 2
  PLAY_CODE="$("$CURL" -sS -o "$WORK/resp" -w '%{http_code}' -X "$method" -H "@$WORK/auth" "$@" "$url")" ||
    die "Could not reach Google Play ($method $url)."
}

# play METHOD URL [curl args…]: the answer lands in $WORK/resp; anything but 2xx stops the release.
play() {
  play_try "$@"
  case "$PLAY_CODE" in
    2??) return 0 ;;
  esac
  play_fail
}

# Stops the release with Google's answer to the last play_try ($PLAY_CODE, $WORK/resp).
play_fail() {
  local code="$PLAY_CODE" message
  message="$(jq -r '.error.message // empty' "$WORK/resp" 2>/dev/null || true)"
  [ -n "$message" ] || message="$(head -c 300 "$WORK/resp")"
  case "$code" in
    401 | 403) message="$message (the service account needs release permission for this app in the Play Console: Users and permissions)" ;;
    404) message="$message (is the app created in the Play Console, with package $PLAY_PACKAGE?)" ;;
  esac
  die "Google Play answered $code: $message"
}

play_upload() {
  say "Uploading to Google Play ($PLAY_PACKAGE, track $TRACK)…"
  play_login
  play POST "$PLAY_API/$PLAY_PACKAGE/edits" -H 'Content-Type: application/json' -d '{}'
  EDIT_ID="$(jq -r .id "$WORK/resp")"
  [ -n "$EDIT_ID" ] && [ "$EDIT_ID" != null ] || die "Google Play did not open an edit."

  # Running again for the same version (a re-run CI job) changes nothing that is already there.
  play GET "$PLAY_API/$PLAY_PACKAGE/edits/$EDIT_ID/tracks"
  if jq -e --arg track "$TRACK" --arg code "$VERSION_CODE" \
    '[.tracks[]? | select(.track == $track) | .releases[]? | .versionCodes[]?] | index($code)' "$WORK/resp" >/dev/null; then
    ok "versionCode $VERSION_CODE is already on the $TRACK track: nothing to upload."
    return 0
  fi
  play GET "$PLAY_API/$PLAY_PACKAGE/edits/$EDIT_ID/bundles"
  if jq -e --argjson code "$VERSION_CODE" '[.bundles[]? | .versionCode] | index($code)' "$WORK/resp" >/dev/null; then
    ok "Google Play already has versionCode $VERSION_CODE; reusing it"
  else
    play POST "$PLAY_UPLOAD/$PLAY_PACKAGE/edits/$EDIT_ID/bundles?uploadType=media" \
      -H 'Content-Type: application/octet-stream' --data-binary "@$AAB" --max-time 900
    local uploaded
    uploaded="$(jq -r .versionCode "$WORK/resp")"
    [ "$uploaded" = "$VERSION_CODE" ] || die "Google Play stored versionCode $uploaded, expected $VERSION_CODE."
    ok "Uploaded the bundle"
  fi

  local status=completed
  [ "$DRAFT" -eq 0 ] || status=draft
  local release
  release="$(jq -nc --arg name "$VERSION" --arg code "$VERSION_CODE" --arg status "$status" \
    --arg lang "$NOTES_LANGUAGE" --arg notes "$NOTES" \
    '{name: $name, versionCodes: [$code], status: $status}
     + (if $notes == "" then {} else {releaseNotes: [{language: $lang, text: $notes}]} end)')"
  play PUT "$PLAY_API/$PLAY_PACKAGE/edits/$EDIT_ID/tracks/$TRACK" -H 'Content-Type: application/json' \
    -d "$(jq -nc --arg track "$TRACK" --argjson release "$release" '{track: $track, releases: [$release]}')"
  # After a rejection (and in some other app states) Play won't send changes for review on its own: it asks for
  # changesNotSentForReview=true, and the changes then wait in the Console's Publishing overview.
  local sent=1
  play_try POST "$PLAY_API/$PLAY_PACKAGE/edits/$EDIT_ID:commit" -d ''
  if [ "$PLAY_CODE" = 400 ] && grep -q changesNotSentForReview "$WORK/resp"; then
    sent=0
    play POST "$PLAY_API/$PLAY_PACKAGE/edits/$EDIT_ID:commit?changesNotSentForReview=true" -d ''
  else
    case "$PLAY_CODE" in 2??) ;; *) play_fail ;; esac
  fi
  EDIT_ID=""
  ok "Release $VERSION ($status) is on the $TRACK track"
  if [ "$sent" -eq 0 ]; then
    say "  Google Play didn't send it for review automatically. Send it in the Play Console: Publishing overview >"
    say "  Send changes for review."
  elif [ "$status" = draft ]; then
    say "  Finish it in the Play Console: Release > ${TRACK} > the draft > Review and roll out."
  elif [ "$TRACK" != production ]; then
    say "  Testers on the $TRACK track get it after Google's processing, usually within minutes."
  else
    say "  Production releases go through Google's review before users see them."
  fi
}

android_prepare_all() {
  android_prepare
  [ "$BUILD_ONLY" -eq 1 ] || play_prepare
}

android_lane() {
  android_build
  if [ "$BUILD_ONLY" -eq 1 ]; then
    say "Build only: nothing was uploaded."
  else
    play_upload
  fi
}

# --------------------------------------------------------------------------------------------- ios

ios_prepare() {
  [ "$(uname -s)" = Darwin ] || die "The iOS lane needs macOS with Xcode."
  XCODEBUILD="${XCODEBUILD:-xcodebuild}"
  XCRUN="${XCRUN:-xcrun}"
  SECURITY="${SECURITY:-security}"
  NODE="${NODE:-node}"
  command -v "$XCODEBUILD" >/dev/null 2>&1 || die "xcodebuild not found. Install Xcode, then run: xcode-select -s /Applications/Xcode.app"
  if [ -z "${XCODEGEN:-}" ]; then
    XCODEGEN="$(command -v xcodegen || first_file /opt/homebrew/bin/xcodegen /usr/local/bin/xcodegen)"
    [ -n "$XCODEGEN" ] || die "xcodegen not found. Install it with: brew install xcodegen"
  fi
  [ -f "$IOS_APP_DIR/project.yml" ] || die "No iOS project at $IOS_APP_DIR (project.yml missing)."

  [ -n "${APPLE_TEAM_ID:-}" ] || die "Set APPLE_TEAM_ID to your 10-character team id (Apple Developer > Membership details)."
  case "$APPLE_TEAM_ID" in
    *[!A-Za-z0-9]*) die "APPLE_TEAM_ID should be a 10-character team id such as ABCDE12345." ;;
  esac
  [ -n "${ASC_KEY_ID:-}" ] || die "Set ASC_KEY_ID to the App Store Connect API key id (Users and Access > Integrations > App Store Connect API)."
  [ -n "${ASC_ISSUER_ID:-}" ] || die "Set ASC_ISSUER_ID to the issuer id shown above the API keys list."
  KEY_PATH="${ASC_KEY_PATH:-$HOME/.appstoreconnect/private_keys/AuthKey_$ASC_KEY_ID.p8}"
  [ -f "$KEY_PATH" ] || die "No API key file at $KEY_PATH. Set ASC_KEY_PATH to your AuthKey_$ASC_KEY_ID.p8."
  BUNDLE_ID="${IOS_APP_BUNDLE_ID:-com.droidhands.ios}"
  case "$BUNDLE_ID" in
    *[!A-Za-z0-9.-]* | .* | *.) die "IOS_APP_BUNDLE_ID may hold only letters, digits, '-' and '.'." ;;
  esac
  if [ -n "${IOS_DISTRIBUTION_P12:-}" ]; then
    [ -f "$IOS_DISTRIBUTION_P12" ] || die "No certificate file at $IOS_DISTRIBUTION_P12 (IOS_DISTRIBUTION_P12)."
    [ -n "${IOS_DISTRIBUTION_P12_PASSWORD:-}" ] || die "Set IOS_DISTRIBUTION_P12_PASSWORD for $IOS_DISTRIBUTION_P12."
    command -v jq >/dev/null 2>&1 || die "jq not found (brew install jq)."
  fi
  if [ "$BUILD_ONLY" -eq 0 ] || [ -n "${IOS_DISTRIBUTION_P12:-}" ]; then
    command -v "$NODE" >/dev/null 2>&1 || die "Node.js not found; the iOS lane uses it to talk to App Store Connect (scripts/app-store-connect.mjs)."
  fi
}

# node scripts/app-store-connect.mjs COMMAND [options…], with the API key.
asc() {
  ASC_KEY_ID="$ASC_KEY_ID" ASC_ISSUER_ID="$ASC_ISSUER_ID" ASC_KEY_PATH="$KEY_PATH" "$NODE" "$SCRIPT_DIR/app-store-connect.mjs" "$@"
}

KEYCHAIN=""
OLD_KEYCHAINS=()
CERT_SERIAL=""

# Puts the distribution certificate in a keychain of its own for this run (removed on exit), and sets
# CERT_SERIAL to the certificate's serial number.
ios_keychain() {
  KEYCHAIN="$WORK/signing.keychain-db"
  local password
  password="$(openssl rand -hex 16)"
  local line
  while IFS= read -r line; do
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line#\"}"
    OLD_KEYCHAINS+=("${line%\"}")
  done < <("$SECURITY" list-keychains -d user)
  "$SECURITY" create-keychain -p "$password" "$KEYCHAIN" || die "Could not create a keychain for signing."
  "$SECURITY" set-keychain-settings -lut 21600 "$KEYCHAIN"
  "$SECURITY" unlock-keychain -p "$password" "$KEYCHAIN"
  "$SECURITY" import "$IOS_DISTRIBUTION_P12" -k "$KEYCHAIN" -f pkcs12 -P "$IOS_DISTRIBUTION_P12_PASSWORD" \
    -T /usr/bin/codesign -T /usr/bin/security >/dev/null || die "Could not import $IOS_DISTRIBUTION_P12 (is IOS_DISTRIBUTION_P12_PASSWORD right?)."
  # Apple's intermediate certificate, so codesign can build the chain on a machine that lacks it.
  if "${CURL:-curl}" -sSf --max-time 60 -o "$WORK/AppleWWDRCAG3.cer" https://www.apple.com/certificateauthority/AppleWWDRCAG3.cer; then
    "$SECURITY" import "$WORK/AppleWWDRCAG3.cer" -k "$KEYCHAIN" >/dev/null 2>&1 || true
  fi
  "$SECURITY" set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$password" "$KEYCHAIN" >/dev/null ||
    die "Could not allow codesign to use the certificate's key."
  "$SECURITY" list-keychains -d user -s "$KEYCHAIN" ${OLD_KEYCHAINS[@]+"${OLD_KEYCHAINS[@]}"}
  local serial
  serial="$("$SECURITY" find-certificate -c "Apple Distribution" -p "$KEYCHAIN" | openssl x509 -noout -serial 2>/dev/null)" ||
    die "$IOS_DISTRIBUTION_P12 holds no Apple Distribution certificate."
  CERT_SERIAL="${serial#serial=}"
  [ -n "$CERT_SERIAL" ] || die "$IOS_DISTRIBUTION_P12 holds no Apple Distribution certificate."
}

ios_keychain_cleanup() {
  [ -n "$KEYCHAIN" ] || return 0
  "$SECURITY" list-keychains -d user -s ${OLD_KEYCHAINS[@]+"${OLD_KEYCHAINS[@]}"} 2>/dev/null || true
  "$SECURITY" delete-keychain "$KEYCHAIN" 2>/dev/null || true
  KEYCHAIN=""
}

ios_lane() {
  local key_path="$KEY_PATH" bundle_id="$BUNDLE_ID"
  if [ -z "${DROIDHANDS_DEFAULT_RELAY_URL:-}" ]; then
    warn "No relay URL (DROIDHANDS_DEFAULT_RELAY_URL or --relay-url): the app keeps project.yml's default relay."
  fi

  local build="${BUILD_NUMBER:-$VERSION_CODE}"
  local dir="$OUT_DIR/ios"
  rm -rf "$dir"
  mkdir -p "$dir"
  local auth=(-authenticationKeyPath "$key_path" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID")

  # Running again for the same version (a re-run CI job) skips what Apple already has.
  local uploaded=no
  if [ "$BUILD_ONLY" -eq 0 ]; then
    uploaded="$(asc has-build --bundle-id "$bundle_id" --version "$VERSION" --build "$build")" ||
      die "Could not ask App Store Connect about build $VERSION ($build)."
  fi

  if [ "$uploaded" = yes ]; then
    ok "App Store Connect already has $VERSION ($build): nothing to build or upload."
  else
    "$XCODEGEN" generate --spec "$IOS_APP_DIR/project.yml" --project "$IOS_APP_DIR" --quiet || die "xcodegen could not generate the Xcode project."
    ok "Generated DroidHands.xcodeproj"

    # Automatic signing uses the certificates in your keychain. With IOS_DISTRIBUTION_P12 (CI, where every
    # machine is new), the build signs with that certificate and a profile fetched through the API instead.
    local signing=(-allowProvisioningUpdates "${auth[@]}" CODE_SIGN_STYLE=Automatic)
    local export_signing="<key>signingStyle</key><string>automatic</string>"
    if [ -n "${IOS_DISTRIBUTION_P12:-}" ]; then
      local profile name uuid
      ios_keychain
      ok "Imported the distribution certificate (serial $CERT_SERIAL)"
      profile="$(asc profile --bundle-id "$bundle_id" --certificate-serial "$CERT_SERIAL" --out "$dir/profile.mobileprovision")" ||
        die "Could not get an App Store provisioning profile for $bundle_id."
      name="$(jq -r .name <<<"$profile")"
      uuid="$(jq -r .uuid <<<"$profile")"
      local profiles
      for profiles in "$HOME/Library/MobileDevice/Provisioning Profiles" "$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"; do
        mkdir -p "$profiles"
        cp "$dir/profile.mobileprovision" "$profiles/$uuid.mobileprovision"
      done
      ok "Installed the provisioning profile \"$name\""
      signing=(CODE_SIGN_STYLE=Manual "CODE_SIGN_IDENTITY=Apple Distribution" "PROVISIONING_PROFILE_SPECIFIER=$name"
        "OTHER_CODE_SIGN_FLAGS=--keychain $KEYCHAIN")
      export_signing="<key>signingStyle</key><string>manual</string>
  <key>signingCertificate</key><string>Apple Distribution</string>
  <key>provisioningProfiles</key><dict><key>$bundle_id</key><string>$name</string></dict>"
    fi

    say "Archiving (xcodebuild archive)…"
    "$XCODEBUILD" archive -project "$IOS_APP_DIR/DroidHands.xcodeproj" -scheme "$IOS_SCHEME" -configuration Release \
      -destination 'generic/platform=iOS' -archivePath "$dir/DroidHands.xcarchive" -derivedDataPath "$dir/DerivedData" \
      "${signing[@]}" "DEVELOPMENT_TEAM=$APPLE_TEAM_ID" "PB_APP_BUNDLE_ID=$bundle_id" \
      ${DROIDHANDS_DEFAULT_RELAY_URL:+"DROIDHANDS_DEFAULT_RELAY_URL=$DROIDHANDS_DEFAULT_RELAY_URL"} \
      "DROIDHANDS_API_URL=${DROIDHANDS_API_URL:-}" \
      "MARKETING_VERSION=$VERSION" "CURRENT_PROJECT_VERSION=$build" ||
      die "The archive failed. Check that team $APPLE_TEAM_ID owns the API key and that the app id $bundle_id is registered to it."
    ok "Archived version $VERSION ($build)"

    cat >"$dir/ExportOptions.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>export</string>
  <key>teamID</key><string>$APPLE_TEAM_ID</string>
  $export_signing
  <key>uploadSymbols</key><true/>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict>
</plist>
EOF
    local export_auth=(-allowProvisioningUpdates "${auth[@]}")
    [ -z "${IOS_DISTRIBUTION_P12:-}" ] || export_auth=()
    "$XCODEBUILD" -exportArchive -archivePath "$dir/DroidHands.xcarchive" -exportPath "$dir/export" \
      -exportOptionsPlist "$dir/ExportOptions.plist" ${export_auth[@]+"${export_auth[@]}"} ||
      die "Exporting the archive failed (see xcodebuild's output above)."
    ios_keychain_cleanup
    local ipa
    ipa="$(find "$dir/export" -maxdepth 1 -name '*.ipa' | head -n 1)"
    [ -n "$ipa" ] || die "The export produced no .ipa."
    ok "Exported $ipa"

    if [ "$BUILD_ONLY" -eq 1 ]; then
      say "Build only: nothing was uploaded."
      return 0
    fi
    say "Uploading to App Store Connect…"
    "$XCRUN" altool --upload-app -f "$ipa" --type ios --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID" --p8-file-path "$key_path" ||
      die "The upload failed (see altool's output above)."
    ok "Uploaded $VERSION ($build). It shows in TestFlight once Apple has processed it (usually 5 to 30 minutes)."
  fi

  if [ "$SUBMIT" -eq 0 ]; then
    say "  To release it: App Store Connect > your app > add the build to a version and submit it for review."
    say "  (or run this again with --submit)"
    return 0
  fi
  local notes_args=()
  if [ -n "$NOTES" ]; then
    printf '%s\n' "$NOTES" >"$WORK/whats-new.txt"
    notes_args=(--notes-file "$WORK/whats-new.txt")
  fi
  asc submit --bundle-id "$bundle_id" --version "$VERSION" --build "$build" --wait-minutes "$WAIT_MINUTES" \
    ${notes_args[@]+"${notes_args[@]}"} || die "Submitting $VERSION ($build) for App Review failed (see above)."
}

first_file() {
  local file
  for file in "$@"; do
    if [ -x "$file" ]; then
      printf '%s' "$file"
      return 0
    fi
  done
}

# --------------------------------------------------------------------------------- only if changed

# What goes into each store's app, as git pathspecs from the repository root. Docs, tests, and the full edition's
# own sources (docs/EDITIONS.md: never on Play) don't count. Anything else in these folders does, even if only
# the GitHub APK uses it: a release too many is better than a fix that never ships.
ANDROID_APP_PATHS=(clients/android plugins ':(exclude)plugins/_*' ':(glob,exclude)**/*.md' ':(glob,exclude)**/.gitignore'
  ':(exclude)clients/android/app/src/full' ':(exclude)clients/android/app/src/test' ':(exclude)clients/android/core/src/test'
  ':(exclude)clients/android/fixtures')
IOS_APP_PATHS=(clients/ios/app ':(glob,exclude)**/*.md' ':(glob,exclude)**/.gitignore'
  ':(exclude)clients/ios/app/DroidHandsTests' ':(exclude)clients/ios/app/DroidHandsUITests')

# Prints the versionCode of a 1.2.3 version string, or nothing when it isn't one.
version_code_of() {
  [[ "$1" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]] || return 0
  printf '%s' $((BASH_REMATCH[1] * 10000 + BASH_REMATCH[2] * 100 + BASH_REMATCH[3]))
}

# A line in the GitHub Actions run summary when running there, so a skipped store shows without opening the log.
notice() {
  ok "$*"
  [ -z "${GITHUB_ACTIONS:-}" ] || printf '::notice::%s\n' "$*"
}

# app_needed NAME STORE STORE_VERSION PATHSPEC…: returns 1 (skip) only when STORE already has an earlier version
# whose tag exists and nothing in PATHSPEC changed from that tag to this checkout (uncommitted changes included).
app_needed() {
  local name="$1" store="$2" have="$3"
  shift 3
  if [ -z "$have" ]; then
    say "$store has no earlier version of the $name app: releasing it."
    return 0
  fi
  local have_code
  have_code="$(version_code_of "$have")"
  if [ -z "$have_code" ]; then
    say "$store's newest $name version is \"$have\", which isn't a release version: releasing."
    return 0
  fi
  # The same version (a re-run) or a newer one: the lane itself says what the store has.
  [ "$have_code" -lt "$VERSION_CODE" ] || return 0
  if ! git -C "$SOURCE_REPO" rev-parse -q --verify "refs/tags/v$have^{commit}" >/dev/null 2>&1; then
    say "$store has $have, but there is no tag v$have here to compare with (a shallow clone?): releasing the $name app."
    return 0
  fi
  local changed
  changed="$(git -C "$SOURCE_REPO" diff --name-only "v$have" -- "$@")" || die "git could not compare v$have with this checkout."
  if [ -n "$changed" ]; then
    say "The $name app changed since $have, which $store has ($(grep -c . <<<"$changed") files): releasing it."
    return 0
  fi
  notice "Nothing in the $name app changed since $have, which $store already has: skipping $store."
  return 1
}

# Sets STORE_VERSION to the newest version on the Play track, from a Play edit that is then deleted.
play_store_version() {
  play_login
  play POST "$PLAY_API/$PLAY_PACKAGE/edits" -H 'Content-Type: application/json' -d '{}'
  EDIT_ID="$(jq -r .id "$WORK/resp")"
  [ -n "$EDIT_ID" ] && [ "$EDIT_ID" != null ] || die "Google Play did not open an edit."
  play GET "$PLAY_API/$PLAY_PACKAGE/edits/$EDIT_ID/tracks"
  local code
  code="$(jq -r --arg track "$TRACK" '[.tracks[]? | select(.track == $track) | .releases[]? | .versionCodes[]? | tonumber] | max // empty' "$WORK/resp")"
  play DELETE "$PLAY_API/$PLAY_PACKAGE/edits/$EDIT_ID"
  EDIT_ID=""
  STORE_VERSION=""
  [ -z "$code" ] || STORE_VERSION="$((code / 10000)).$((code / 100 % 100)).$((code % 100))"
}

android_needed() {
  play_store_version
  app_needed Android "Google Play ($TRACK track)" "$STORE_VERSION" "${ANDROID_APP_PATHS[@]}"
}

# Compares with the App Store with --submit, and with the newest TestFlight upload without it.
ios_needed() {
  local source=uploads store="TestFlight"
  [ "$SUBMIT" -eq 0 ] || source=app-store store="the App Store"
  STORE_VERSION="$(asc latest-version --bundle-id "$BUNDLE_ID" --source "$source")" ||
    die "Could not ask App Store Connect which version it has."
  app_needed iPhone "$store" "$STORE_VERSION" "${IOS_APP_PATHS[@]}"
}

# ------------------------------------------------------------------------------------------------ run

# Every lane's settings are checked before anything is built or uploaded, so a missing iOS key doesn't leave
# Android released and iOS not.
case "$LANE" in
  android | all) android_prepare_all ;;
esac
case "$LANE" in
  ios | all) ios_prepare ;;
esac
RUN_ANDROID=0
RUN_IOS=0
case "$LANE" in
  android) RUN_ANDROID=1 ;;
  ios) RUN_IOS=1 ;;
  all) RUN_ANDROID=1 RUN_IOS=1 ;;
esac
# Both stores are asked before anything is built.
if [ "$ONLY_IF_CHANGED" -eq 1 ]; then
  if [ "$RUN_ANDROID" -eq 1 ] && ! android_needed; then RUN_ANDROID=0; fi
  if [ "$RUN_IOS" -eq 1 ] && ! ios_needed; then RUN_IOS=0; fi
fi
[ "$RUN_ANDROID" -eq 0 ] || android_lane
[ "$RUN_IOS" -eq 0 ] || ios_lane
say ""
ok "Done."
