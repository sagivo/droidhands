#!/usr/bin/env bash
# Fresh clean build & launch of DroidHands from latest origin/main on both iOS and Android emulators.
#
# Usage:
#   ./scripts/run-emulators.sh [--avd NAME] [--relay URL] [--skip-git]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
AVD="${ANDROID_AVD:-phonebridge}"
RELAY=""
DEFAULT_RELAY="${DROIDHANDS_DEFAULT_RELAY_URL:-https://connect.droidhands.com}"
API_URL="${DROIDHANDS_API_URL:-https://api.droidhands.com}"
SKIP_GIT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --avd) AVD="${2:?--avd needs a name}"; shift 2 ;;
    --relay) RELAY="${2:?--relay needs a URL}"; shift 2 ;;
    --skip-git) SKIP_GIT=1; shift ;;
    -h|--help)
      echo "Usage: $(basename "$0") [--avd NAME] [--relay URL] [--skip-git]"
      exit 0
      ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:/opt/homebrew/bin:$PATH"

cd "$REPO_DIR"

# 1. Sync to latest origin/main
if [ "$SKIP_GIT" -eq 0 ]; then
  if ! git diff --quiet || ! git diff --cached --quiet; then
    echo "✗ Working tree has uncommitted changes. Commit/stash them, or pass --skip-git." >&2
    exit 1
  fi
  echo "==> Updating main from origin/main..."
  git fetch origin main
  git checkout main
  git pull --ff-only origin main
fi

# 2. Ensure Android emulator is running with a GUI window and laptop keyboard enabled
echo "==> Preparing Android emulator ($AVD)..."
AVD_DIR="${ANDROID_AVD_HOME:-$HOME/.android/avd}/${AVD}.avd"
AVD_CONFIG="$AVD_DIR/config.ini"
if [ -f "$AVD_CONFIG" ]; then
  if grep -q '^hw\.keyboard=' "$AVD_CONFIG"; then
    sed -i '' 's/^hw\.keyboard=.*/hw.keyboard=yes/' "$AVD_CONFIG"
  else
    printf '\nhw.keyboard=yes\n' >> "$AVD_CONFIG"
  fi
fi

NEED_ANDROID_RESTART=0
if pgrep -f "qemu-system.*$AVD.*-no-window" >/dev/null 2>&1; then
  echo "    Stopping headless Android emulator..."
  NEED_ANDROID_RESTART=1
elif [ -f "$AVD_DIR/hardware-qemu.ini" ] \
  && grep -Eq '^hw\.keyboard[[:space:]]*=[[:space:]]*(false|no)$' "$AVD_DIR/hardware-qemu.ini" \
  && adb devices | awk 'NR>1 && $2=="device" && $1 ~ /^emulator-/' | grep -q .; then
  echo "    Restarting Android emulator to enable laptop keyboard..."
  NEED_ANDROID_RESTART=1
fi

if [ "$NEED_ANDROID_RESTART" -eq 1 ]; then
  adb emu kill >/dev/null 2>&1 || true
  for _ in $(seq 1 20); do
    pgrep -f "qemu-system.*$AVD" >/dev/null 2>&1 || break
    sleep 1
  done
fi

if ! adb devices | awk 'NR>1 && $2=="device" && $1 ~ /^emulator-/' | grep -q .; then
  echo "    Starting Android emulator ($AVD)..."
  nohup emulator -avd "$AVD" -no-snapshot-load -no-snapshot-save >/tmp/droidhands-android-emu.log 2>&1 </dev/null &
  adb wait-for-device
fi

for _ in $(seq 1 90); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    break
  fi
  sleep 2
done
ANDROID_SERIAL="$(adb devices | awk 'NR>1 && $2=="device" && $1 ~ /^emulator-/ {print $1; exit}')"
[ -n "$ANDROID_SERIAL" ] || { echo "✗ Android emulator did not come online." >&2; exit 1; }

# 3. Ensure iOS simulator is booted and visible with laptop keyboard enabled
echo "==> Preparing iOS simulator..."
defaults write com.apple.iphonesimulator ConnectHardwareKeyboard -bool true 2>/dev/null || true
IOS_SIM="$(xcrun simctl list devices booted | sed -n 's/.*iPhone.*(\([0-9A-F-]\{36\}\)) (Booted).*/\1/p' | head -n 1)"
if [ -z "$IOS_SIM" ]; then
  IOS_SIM="$(xcrun simctl list devices available | sed -n 's/.*iPhone.*(\([0-9A-F-]\{36\}\)).*/\1/p' | head -n 1)"
  [ -n "$IOS_SIM" ] || { echo "✗ No iPhone simulator found." >&2; exit 1; }
  xcrun simctl boot "$IOS_SIM" 2>/dev/null || true
  xcrun simctl bootstatus "$IOS_SIM" -b >/dev/null
fi
open /Applications/Xcode.app/Contents/Applications/DeviceHub.app --args -alwaysSimulateHardwareKeyboard YES 2>/dev/null \
  || open -a Simulator 2>/dev/null || true

# 4. Fresh clean build + install + launch on Android
echo "==> Clean building and installing fresh Android app..."
adb -s "$ANDROID_SERIAL" uninstall com.droidhands.android >/dev/null 2>&1 || true
(cd clients/android && ./gradlew :app:clean :app:assembleFullDebug \
  -Pdroidhands.defaultRelayUrl="$DEFAULT_RELAY" \
  -Pdroidhands.apiUrl="$API_URL" \
  --console=plain -q)
ANDROID_ARGS=(--serial "$ANDROID_SERIAL" --apk "$REPO_DIR/clients/android/app/build/outputs/apk/full/debug/app-full-debug.apk")
[ -z "$RELAY" ] || ANDROID_ARGS+=(--relay "$RELAY")
./scripts/setup-phone.sh "${ANDROID_ARGS[@]}"

# 5. Fresh clean build + install + launch on iOS
echo "==> Clean building and installing fresh iOS app..."
xcrun simctl uninstall "$IOS_SIM" com.droidhands.ios >/dev/null 2>&1 || true
rm -rf clients/ios/app/build/DerivedData
IOS_ARGS=(run --simulator "$IOS_SIM")
[ -z "$RELAY" ] || IOS_ARGS+=(--relay "$RELAY")
DROIDHANDS_DEFAULT_RELAY_URL="$DEFAULT_RELAY" DROIDHANDS_API_URL="$API_URL" ./scripts/ios-app.sh "${IOS_ARGS[@]}"

echo ""
echo "✓ Fresh builds from $(git rev-parse --short HEAD) are running on both iOS ($IOS_SIM) and Android ($ANDROID_SERIAL)."
