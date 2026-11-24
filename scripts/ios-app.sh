#!/usr/bin/env bash
# Build, test and run DroidHands for iPhone (clients/ios/app, the App Store app) on a simulator.
#
#   ./scripts/ios-app.sh test [--simulator UDID]               # unit tests
#   ./scripts/ios-app.sh run [--simulator UDID] [--relay URL]  # install and launch it
#   ./scripts/ios-app.sh screenshots [--simulator UDID] [--relay URL] [--out DIR]
#
# `run --relay URL` points a Debug build at another relay (for example a local one: cd relay && npm run build &&
# PORT=8788 PUBLIC_URL=http://localhost:8788 npm start) and writes a Full-access token for an agent named "Test"
# to the app's Documents/test-token.txt, so you can call it with curl right away. `screenshots` walks every screen
# (DroidHandsUITests) and saves a PNG of each, at the simulator's size: use an iPhone 6.9" simulator for the
# App Store. Without --simulator, the first booted iPhone simulator is used, else the first available one.
# DROIDHANDS_API_URL= (set, empty) builds without accounts, like the App Store build; use it for store screenshots.
#
# Releases are built by scripts/store-release.sh ios. Needs: Xcode and xcodegen (brew install xcodegen).
# Works with macOS's bash 3.2.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_DIR="$REPO_ROOT/clients/ios/app"
DERIVED="$APP_DIR/build/DerivedData"
BUNDLE_ID="com.droidhands.ios"

die() {
  printf '✗ %s\n' "$*" >&2
  exit 1
}

[ $# -ge 1 ] || die "Say what to do: test, run or screenshots (see the top of this script)."
ACTION="$1"
shift
SIM="" RELAY="" OUT="$REPO_ROOT/dist/ios-screenshots"
while [ $# -gt 0 ]; do
  case "$1" in
    --simulator) SIM="${2:?--simulator needs a UDID}"; shift 2 ;;
    --relay) RELAY="${2:?--relay needs a URL}"; shift 2 ;;
    --out) OUT="${2:?--out needs a directory}"; shift 2 ;;
    *) die "Unknown option $1" ;;
  esac
done

command -v xcodegen >/dev/null 2>&1 || die "xcodegen not found. Install it with: brew install xcodegen"
if [ -z "$SIM" ]; then
  SIM="$(xcrun simctl list devices booted | sed -n 's/.*iPhone.*(\([0-9A-F-]\{36\}\)) (Booted).*/\1/p' | head -n 1)"
  [ -n "$SIM" ] || SIM="$(xcrun simctl list devices available | sed -n 's/.*iPhone.*(\([0-9A-F-]\{36\}\)).*/\1/p' | head -n 1)"
  [ -n "$SIM" ] || die "No iPhone simulator found. Create one in Xcode › Window › Devices and Simulators."
fi
xcrun simctl boot "$SIM" 2>/dev/null || true
xcrun simctl bootstatus "$SIM" -b >/dev/null

xcodegen generate --spec "$APP_DIR/project.yml" --project "$APP_DIR" --quiet
build() {
  local extra=()
  [ -z "${DROIDHANDS_DEFAULT_RELAY_URL:-}" ] || extra+=("DROIDHANDS_DEFAULT_RELAY_URL=$DROIDHANDS_DEFAULT_RELAY_URL")
  # Set but empty builds without accounts, as the store build does (the project default has them).
  [ -z "${DROIDHANDS_API_URL+set}" ] || extra+=("DROIDHANDS_API_URL=$DROIDHANDS_API_URL")
  xcodebuild -project "$APP_DIR/DroidHands.xcodeproj" -scheme DroidHands -destination "platform=iOS Simulator,id=$SIM" \
    -derivedDataPath "$DERIVED" -quiet ${extra[@]+"${extra[@]}"} "$@"
}

case "$ACTION" in
  test)
    build test -only-testing:DroidHandsTests
    printf '✓ Unit tests passed\n'
    ;;
  run)
    build build
    xcrun simctl install "$SIM" "$DERIVED/Build/Products/Debug-iphonesimulator/DroidHands.app"
    if [ -n "$RELAY" ]; then
      SIMCTL_CHILD_DH_RELAY_URL="$RELAY" SIMCTL_CHILD_DH_TEST_TOKEN_NAME="Test" \
        xcrun simctl launch --terminate-running-process "$SIM" "$BUNDLE_ID" >/dev/null
      sleep 3
      printf '✓ Running against %s. Token: %s\n' "$RELAY" "$(xcrun simctl get_app_container "$SIM" "$BUNDLE_ID" data)/Documents/test-token.txt"
    else
      xcrun simctl launch --terminate-running-process "$SIM" "$BUNDLE_ID" >/dev/null
      printf '✓ Running on simulator %s\n' "$SIM"
    fi
    ;;
  screenshots)
    mkdir -p "$OUT"
    # xcodebuild hands TEST_RUNNER_<name> to the UI tests as <name>.
    export TEST_RUNNER_DH_SCREENSHOT_DIR="$OUT"
    [ -z "$RELAY" ] || export TEST_RUNNER_DH_RELAY_URL="$RELAY"
    build test -only-testing:DroidHandsUITests
    printf '✓ Screenshots in %s\n' "$OUT"
    ;;
  *) die "Unknown action $ACTION (test, run or screenshots)." ;;
esac
