#!/usr/bin/env bash
# Build and run the DroidHands iOS runner (clients/ios/runner) on a simulator or an iPhone.
#
#   PB_RUNNER_SECRET=... ./scripts/ios-runner.sh --simulator <udid> [--port 8101]
#   PB_RUNNER_SECRET=... ./scripts/ios-runner.sh --device <udid> --team <TEAMID> [--port 8101]
#
# Generates the Xcode project with xcodegen, builds the runner for testing, then runs its single UI test
# (DroidHandsRunner/DroidHandsRunnerTests/testRunBridge) in the foreground. While it runs, the runner
# serves the runner protocol (docs/IOS.md) on the phone's port; stop it with Ctrl-C. The secret is
# handed to the runner through the environment (TEST_RUNNER_PB_RUNNER_SECRET), never on a command line.
#
# Needs: Xcode (xcodebuild) and xcodegen (brew install xcodegen). Works with macOS's bash 3.2.
# NO_COLOR=1 disables colors.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNNER_DIR="${DROIDHANDS_RUNNER_SRC:-$REPO_ROOT/clients/ios/runner}"
readonly SCHEME="DroidHandsRunner"
readonly TEST_ID="DroidHandsRunner/DroidHandsRunnerTests/testRunBridge"
readonly DEFAULT_BUNDLE_BASE="com.droidhands.runner"

usage() {
  cat <<EOF
Usage: PB_RUNNER_SECRET=<secret> $(basename "$0") (--simulator UDID | --device UDID) [options]

Builds the DroidHands iOS runner and runs it until you stop it (Ctrl-C).

  --simulator UDID    Run on this booted simulator ('xcrun simctl list devices booted').
  --device UDID       Run on this iPhone, connected by USB or paired over Wi-Fi ('xcrun devicectl list devices').
  --team TEAMID       Apple development team for signing (needed with --device). Find it in Xcode >
                      Settings > Accounts, or in your certificate's "OU" field.
  --port N            Port the runner listens on, on the phone (default 8101).
  --bundle-id ID      Bundle id of the runner (default ${DEFAULT_BUNDLE_BASE}, plus .<team> on a device so it
                      doesn't collide with another team's registration).
  --derived-data DIR  Xcode build folder (default clients/ios/runner/build/DerivedData).
  -h, --help          Show this help.

Environment: PB_RUNNER_SECRET (required: the host's secret for this runner), XCODEGEN, XCODEBUILD, NO_COLOR.
EOF
}

# ---------------------------------------------------------------------------------------------- ui

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_GREEN=$'\033[32m' C_RED=$'\033[31m' C_BOLD=$'\033[1m' C_RESET=$'\033[0m'
else
  C_GREEN="" C_RED="" C_BOLD="" C_RESET=""
fi

say() { printf '%s\n' "$*"; }
ok() { printf '%s✓%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
die() {
  printf '%s✗ %s%s\n' "$C_RED" "$*" "$C_RESET" >&2
  exit 1
}

# ------------------------------------------------------------------------------------------- args

MODE=""
UDID=""
TEAM=""
PORT=8101
BUNDLE_ID=""
DERIVED_DATA="$RUNNER_DIR/build/DerivedData"

need_value() { [ $# -ge 2 ] && [ -n "$2" ] || die "$1 needs a value (see --help)"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --simulator | --device)
      need_value "$@"
      [ -z "$MODE" ] || die "Use either --simulator or --device, once."
      MODE="${1#--}"
      UDID="$2"
      shift 2
      ;;
    --team)
      need_value "$@"
      TEAM="$2"
      shift 2
      ;;
    --port)
      need_value "$@"
      PORT="$2"
      shift 2
      ;;
    --bundle-id)
      need_value "$@"
      BUNDLE_ID="$2"
      shift 2
      ;;
    --derived-data)
      need_value "$@"
      DERIVED_DATA="$2"
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "Unknown option: $1"
      ;;
  esac
done

[ -n "$MODE" ] || {
  usage >&2
  die "Say where to run the runner: --simulator UDID or --device UDID."
}
case "$PORT" in
  '' | *[!0-9]*) die "--port must be a number from 1 to 65535." ;;
esac
[ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || die "--port must be a number from 1 to 65535."
[ -n "${PB_RUNNER_SECRET:-}" ] || die "Set PB_RUNNER_SECRET to the secret the host will use; the runner refuses to start without one."
if [ "$MODE" = device ]; then
  [ -n "$TEAM" ] || die "--device needs --team TEAMID: Xcode must sign the runner to install it on an iPhone."
  case "$TEAM" in
    *[!A-Za-z0-9]*) die "--team should be a 10-character team id such as ABCDE12345." ;;
  esac
fi
if [ -z "$BUNDLE_ID" ]; then
  if [ "$MODE" = device ]; then
    BUNDLE_ID="$DEFAULT_BUNDLE_BASE.$(printf '%s' "$TEAM" | tr '[:upper:]' '[:lower:]')"
  else
    BUNDLE_ID="$DEFAULT_BUNDLE_BASE"
  fi
fi
case "$BUNDLE_ID" in
  *[!A-Za-z0-9.-]* | .* | *.) die "--bundle-id may hold only letters, digits, '-' and '.'." ;;
esac

# ------------------------------------------------------------------------------------------ tools

XCODEBUILD="${XCODEBUILD:-xcodebuild}"
command -v "$XCODEBUILD" >/dev/null 2>&1 || die "xcodebuild not found. Install Xcode, then run: xcode-select -s /Applications/Xcode.app"
if [ -z "${XCODEGEN:-}" ]; then
  if command -v xcodegen >/dev/null 2>&1; then
    XCODEGEN="xcodegen"
  elif [ -x /opt/homebrew/bin/xcodegen ]; then
    XCODEGEN="/opt/homebrew/bin/xcodegen"
  elif [ -x /usr/local/bin/xcodegen ]; then
    XCODEGEN="/usr/local/bin/xcodegen"
  else
    die "xcodegen not found. Install it with: brew install xcodegen"
  fi
fi
[ -f "$RUNNER_DIR/project.yml" ] || die "No runner project at $RUNNER_DIR (project.yml missing)."

# ------------------------------------------------------------------------------------------ build

say "${C_BOLD}DroidHands iOS runner${C_RESET} → $MODE $UDID, port $PORT"

"$XCODEGEN" generate --spec "$RUNNER_DIR/project.yml" --project "$RUNNER_DIR" --quiet ||
  die "xcodegen could not generate the Xcode project."
ok "Generated DroidHandsRunner.xcodeproj"

PROJECT="$RUNNER_DIR/DroidHandsRunner.xcodeproj"
COMMON=(-project "$PROJECT" -scheme "$SCHEME" -derivedDataPath "$DERIVED_DATA")
SETTINGS=("PB_BUNDLE_ID_BASE=$BUNDLE_ID")
if [ "$MODE" = simulator ]; then
  DESTINATION="platform=iOS Simulator,id=$UDID"
  # Simulators run ad-hoc ("Sign to Run Locally") builds; no team or certificate needed.
  SETTINGS+=(CODE_SIGN_STYLE=Manual "CODE_SIGN_IDENTITY=-" DEVELOPMENT_TEAM=)
  SIGNING_FLAGS=()
else
  DESTINATION="platform=iOS,id=$UDID"
  SETTINGS+=(CODE_SIGN_STYLE=Automatic "DEVELOPMENT_TEAM=$TEAM")
  SIGNING_FLAGS=(-allowProvisioningUpdates -allowProvisioningDeviceRegistration)
  say ""
  say "On the iPhone, before the first run:"
  say "  1. Turn on Developer Mode: Settings > Privacy & Security > Developer Mode (the phone restarts)."
  say "  2. Keep the phone unlocked while the runner starts."
  say "  3. After the first install, trust your developer profile: Settings > General > VPN & Device"
  say "     Management > your Apple Development profile > Trust. Then run this script again."
  say ""
fi

say "Building (xcodebuild build-for-testing)…"
"$XCODEBUILD" build-for-testing "${COMMON[@]}" -destination "$DESTINATION" ${SIGNING_FLAGS[@]+"${SIGNING_FLAGS[@]}"} \
  "${SETTINGS[@]}" || {
  if [ "$MODE" = device ]; then
    die "The build failed. Check that team $TEAM is signed in under Xcode > Settings > Accounts and the iPhone is connected and unlocked."
  fi
  die "The build failed (see xcodebuild's output above)."
}
ok "Built the runner"

# ------------------------------------------------------------------------------------------- run

export TEST_RUNNER_PB_RUNNER_SECRET="$PB_RUNNER_SECRET"
export TEST_RUNNER_PB_RUNNER_PORT="$PORT"
say "Starting the runner on port $PORT. It keeps running until you press Ctrl-C."
if [ "$MODE" = simulator ]; then
  say "The host reaches it at http://127.0.0.1:$PORT (simulators share the Mac's network)."
else
  say "Leave the iPhone unlocked; a locked iPhone can't be operated."
fi
exec "$XCODEBUILD" test-without-building "${COMMON[@]}" -destination "$DESTINATION" \
  "-only-testing:$TEST_ID" -parallel-testing-enabled NO -test-timeouts-enabled NO
