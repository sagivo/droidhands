#!/usr/bin/env bash
# One-command USB setup of a PhoneBridge developer build on an Android 11+ phone or emulator.
#
#   ./scripts/setup-phone.sh [--apk path] [--relay URL] [--key ACCESSKEY] [--serial SERIAL]
#
# Builds the debug APK (unless --apk is given), installs it, grants every runtime permission the
# app requests, turns on the special accesses (accessibility, notification access, Do Not Disturb
# access, battery-optimization exemption, display over other apps, and the Android 13+ restricted
# settings for sideloaded apps), optionally points the app at a relay, launches it and prints a
# checklist. Each step is best effort: a failure is reported in the summary, not fatal, unless
# nothing after it could work (no adb, no device, install failed).
#
# Works with macOS's bash 3.2 and Linux. Finds adb via $ADB, $ANDROID_HOME, $ANDROID_SDK_ROOT, PATH
# and the usual SDK locations. Set NO_COLOR=1 to disable colors.
set -euo pipefail

readonly PKG="dev.phonebridge.android"
readonly ACCESSIBILITY_SERVICE="$PKG/$PKG.access.BridgeAccessibilityService"
readonly ACCESSIBILITY_SERVICE_SHORT="$PKG/.access.BridgeAccessibilityService"
readonly NOTIFICATION_LISTENER="$PKG/$PKG.notify.BridgeNotificationListener"
readonly MAIN_ACTIVITY="$PKG/.ui.MainActivity"
readonly MIN_SDK=30

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/clients/android"
DEFAULT_APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
MANIFEST="$ANDROID_DIR/app/src/main/AndroidManifest.xml"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--apk path] [--relay URL] [--key ACCESSKEY] [--serial SERIAL]

Installs and fully sets up PhoneBridge on a USB-connected Android 11+ device (USB debugging on).

  --apk PATH        Install this APK instead of building clients/android (debug build).
  --relay URL       Relay base URL (http:// or https://). Opens the app's "Connect to relay?" prompt.
  --key ACCESSKEY   The relay's access key (RELAY_ACCESS_KEY), if it has one. Needs --relay.
  --serial SERIAL   Device to use when several are connected (see 'adb devices').
  -h, --help        Show this help.

Environment: ADB (path to adb), ANDROID_HOME / ANDROID_SDK_ROOT, JAVA_HOME (for the build), NO_COLOR.
EOF
}

# ---------------------------------------------------------------------------------------------- ui

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_GREEN=$'\033[32m' C_YELLOW=$'\033[33m' C_RED=$'\033[31m' C_DIM=$'\033[2m' C_BOLD=$'\033[1m' C_RESET=$'\033[0m'
else
  C_GREEN="" C_YELLOW="" C_RED="" C_DIM="" C_BOLD="" C_RESET=""
fi

CHECK_STATUS=()
CHECK_TEXT=()
SUMMARY_PRINTED=0

# record STATUS TEXT  (STATUS: ok | warn | fail | skip)
record() {
  CHECK_STATUS+=("$1")
  CHECK_TEXT+=("$2")
  case "$1" in
    ok) printf '  %s✓%s %s\n' "$C_GREEN" "$C_RESET" "$2" ;;
    warn) printf '  %s!%s %s\n' "$C_YELLOW" "$C_RESET" "$2" ;;
    fail) printf '  %s✗%s %s\n' "$C_RED" "$C_RESET" "$2" ;;
    *) printf '  %s-%s %s\n' "$C_DIM" "$C_RESET" "$2" ;;
  esac
}

step() { printf '\n%s%s%s\n' "$C_BOLD" "$1" "$C_RESET"; }
info() { printf '    %s%s%s\n' "$C_DIM" "$1" "$C_RESET"; }

print_summary() {
  [ "$SUMMARY_PRINTED" = 1 ] && return 0
  SUMMARY_PRINTED=1
  local i ok=0 warn=0 fail=0 status mark color
  printf '\n%sSummary%s\n' "$C_BOLD" "$C_RESET"
  for ((i = 0; i < ${#CHECK_STATUS[@]}; i++)); do
    status="${CHECK_STATUS[$i]}"
    case "$status" in
      ok) mark="✓" color="$C_GREEN" ok=$((ok + 1)) ;;
      warn) mark="!" color="$C_YELLOW" warn=$((warn + 1)) ;;
      fail) mark="✗" color="$C_RED" fail=$((fail + 1)) ;;
      *) mark="-" color="$C_DIM" ;;
    esac
    printf '  %s%s%s %s\n' "$color" "$mark" "$C_RESET" "${CHECK_TEXT[$i]}"
  done
  printf '\n  %sok: %d%s, %swarnings: %d%s, %sfailed: %d%s\n' \
    "$C_GREEN" "$ok" "$C_RESET" "$C_YELLOW" "$warn" "$C_RESET" "$C_RED" "$fail" "$C_RESET"
}

die() {
  record fail "$1"
  if [ $# -gt 1 ]; then info "$2"; fi
  print_summary
  exit 1
}

# ------------------------------------------------------------------------------------------- args

APK=""
RELAY=""
KEY=""
SERIAL=""
KEY_GIVEN=0

need_value() {
  if [ $# -lt 2 ] || [ -z "$2" ]; then
    echo "error: $1 needs a value" >&2
    usage >&2
    exit 2
  fi
}

while [ $# -gt 0 ]; do
  case "$1" in
    --apk) need_value "$@"; APK="$2"; shift 2 ;;
    --apk=*) APK="${1#*=}"; shift ;;
    --relay) need_value "$@"; RELAY="$2"; shift 2 ;;
    --relay=*) RELAY="${1#*=}"; shift ;;
    --key) need_value "$@"; KEY="$2"; KEY_GIVEN=1; shift 2 ;;
    --key=*) KEY="${1#*=}"; KEY_GIVEN=1; shift ;;
    --serial) need_value "$@"; SERIAL="$2"; shift 2 ;;
    --serial=*) SERIAL="${1#*=}"; shift ;;
    -h | --help) usage; exit 0 ;;
    *) echo "error: unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [ "$KEY_GIVEN" = 1 ] && [ -z "$RELAY" ]; then
  echo "error: --key needs --relay" >&2
  exit 2
fi
if [ -n "$RELAY" ]; then
  case "$RELAY" in
    http://?* | https://?*) RELAY="${RELAY%/}" ;;
    *) echo "error: --relay must start with http:// or https:// (got: $RELAY)" >&2; exit 2 ;;
  esac
fi

# An unexpected failure (set -e) still shows what was done so far.
trap 'if [ "$?" -ne 0 ] && [ "$SUMMARY_PRINTED" = 0 ]; then print_summary; fi' EXIT

# ---------------------------------------------------------------------------------------- helpers

# Single-quotes $1 for the device's /system/bin/sh (adb shell joins its arguments into one command line).
shq() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

# RFC 3986 percent-encoding of $1 (byte-wise, so UTF-8 is encoded correctly).
urlencode() {
  local LC_ALL=C
  local s="$1" out="" c i
  for ((i = 0; i < ${#s}; i++)); do
    c="${s:i:1}"
    case "$c" in
      [a-zA-Z0-9.~_-]) out="$out$c" ;;
      *) out="$out$(printf '%%%02X' "'$c")" ;;
    esac
  done
  printf '%s' "$out"
}

find_adb() {
  local candidate
  for candidate in \
    "${ADB:-}" \
    "${ANDROID_HOME:+$ANDROID_HOME/platform-tools/adb}" \
    "${ANDROID_SDK_ROOT:+$ANDROID_SDK_ROOT/platform-tools/adb}" \
    "$(command -v adb 2>/dev/null || true)" \
    "$HOME/Library/Android/sdk/platform-tools/adb" \
    "$HOME/Android/Sdk/platform-tools/adb" \
    "/opt/homebrew/share/android-commandlinetools/platform-tools/adb" \
    "/usr/local/share/android-commandlinetools/platform-tools/adb"; do
    if [ -n "$candidate" ] && [ -x "$candidate" ]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}

ADB_CMD=()

# Runs a command line in the device shell; prints its output without CRs (older devices use a pty).
dsh() {
  "${ADB_CMD[@]}" shell "$1" </dev/null 2>&1 | tr -d '\r'
}

# Like dsh, but also fails when the output reports an exception (old adb doesn't forward exit codes).
dsh_checked() {
  local out
  out="$(dsh "$1")" || { printf '%s' "$out"; return 1; }
  printf '%s' "$out"
  case "$out" in
    *Exception* | *"Error:"* | *"Unknown command"* | *"not found"* | *"Can't find service"*) return 1 ;;
  esac
  return 0
}

# Permissions the installed package requests, one per line (from the package manager).
requested_permissions() {
  dsh "dumpsys package $PKG" | awk '
    /^ *requested permissions:/ { inside = 1; next }
    inside {
      name = $1
      sub(/:$/, "", name)
      if (name ~ /^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$/) print name
      else exit
    }'
}

# Fallback: uses-permission entries of the app manifest in this repository.
manifest_permissions() {
  [ -f "$MANIFEST" ] || return 0
  sed -n 's/.*<uses-permission[^>]*android:name="\([^"]*\)".*/\1/p' "$MANIFEST"
  # Multi-line <uses-permission> elements put the name on its own line.
  awk '/<uses-permission/ { inside = 1 } inside && /android:name="/ { sub(/.*android:name="/, ""); sub(/".*/, ""); print; inside = 0 }' "$MANIFEST"
}

# ------------------------------------------------------------------------------------------ steps

step "Device"
ADB_BIN="$(find_adb)" || die "adb not found" "Install Android platform-tools, or set ADB=/path/to/adb or ANDROID_HOME."
record ok "adb: $ADB_BIN"

if [ -z "$SERIAL" ]; then
  DEVICES="$("$ADB_BIN" devices </dev/null | tr -d '\r' | awk 'NR > 1 && NF >= 2 { print $1 " " $2 }')"
  READY="$(printf '%s\n' "$DEVICES" | awk '$2 == "device" { print $1 }')"
  READY_COUNT="$(printf '%s\n' "$READY" | grep -c . || true)"
  if [ "$READY_COUNT" -eq 0 ]; then
    if printf '%s\n' "$DEVICES" | grep -q " unauthorized$"; then
      die "Device not authorized" "Unlock the phone and accept the 'Allow USB debugging?' prompt, then run this again."
    fi
    die "No device connected" "Connect the phone with USB and turn on Developer options > USB debugging (or start an emulator)."
  fi
  if [ "$READY_COUNT" -gt 1 ]; then
    die "Several devices connected: $(printf '%s' "$READY" | tr '\n' ' ')" "Choose one with --serial SERIAL."
  fi
  SERIAL="$READY"
fi
ADB_CMD=("$ADB_BIN" -s "$SERIAL")

STATE="$("${ADB_CMD[@]}" get-state </dev/null 2>&1 | tr -d '\r' || true)"
[ "$STATE" = "device" ] || die "Device $SERIAL is not ready ($STATE)" "Check the USB connection and accept the USB debugging prompt."

SDK="$(dsh "getprop ro.build.version.sdk" | tr -dc '0-9')"
RELEASE="$(dsh "getprop ro.build.version.release")"
MODEL="$(dsh "getprop ro.product.model")"
[ -n "$SDK" ] || die "Could not read the Android version of $SERIAL"
if [ "$SDK" -lt "$MIN_SDK" ]; then
  die "$MODEL runs Android $RELEASE (API $SDK); PhoneBridge needs Android 11 (API $MIN_SDK) or newer"
fi
record ok "Device: $MODEL, Android $RELEASE (API $SDK), serial $SERIAL"

step "App"
if [ -z "$APK" ]; then
  [ -x "$ANDROID_DIR/gradlew" ] || die "Can't build: $ANDROID_DIR/gradlew not found" "Pass --apk path/to/app.apk instead."
  if [ -z "${JAVA_HOME:-}" ]; then
    if [ -x /usr/libexec/java_home ] && JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null)"; then
      export JAVA_HOME
    elif [ -d /opt/homebrew/opt/openjdk@17 ]; then
      export JAVA_HOME=/opt/homebrew/opt/openjdk@17
    fi
  fi
  info "Building the debug APK (./gradlew :app:assembleDebug)…"
  if (cd "$ANDROID_DIR" && ./gradlew :app:assembleDebug --console=plain -q); then
    APK="$DEFAULT_APK"
    record ok "Built $APK"
  else
    die "Build failed" "Fix the build output above, or pass --apk path/to/app.apk."
  fi
fi
[ -f "$APK" ] || die "APK not found: $APK"

if INSTALL_OUT="$("${ADB_CMD[@]}" install -r "$APK" </dev/null 2>&1 | tr -d '\r')" && printf '%s' "$INSTALL_OUT" | grep -q "Success"; then
  record ok "Installed $(basename "$APK")"
else
  printf '%s\n' "$INSTALL_OUT" | tail -5 | while IFS= read -r line; do info "$line"; done
  case "$INSTALL_OUT" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*)
      die "Install failed: a PhoneBridge signed with another key is installed" \
        "Uninstall it first (this deletes its agents and settings): adb -s $SERIAL uninstall $PKG" ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*)
      die "Install failed: a newer PhoneBridge is installed" "Uninstall it first: adb -s $SERIAL uninstall $PKG" ;;
    *INSTALL_FAILED_USER_RESTRICTED* | *INSTALL_FAILED_ABORTED*)
      die "Install failed: the phone refused the USB install" "Accept the install prompt on the phone, or enable Developer options > Install via USB." ;;
    *) die "Install failed" "See the adb output above." ;;
  esac
fi

step "Permissions"
PERMISSIONS="$(requested_permissions || true)"
if [ -z "$PERMISSIONS" ]; then
  info "Could not list the app's permissions from the device; using $MANIFEST"
  PERMISSIONS="$(manifest_permissions | sort -u)"
fi
GRANTED=()
FAILED=()
for perm in $PERMISSIONS; do
  if out="$(dsh_checked "pm grant $PKG $perm")"; then
    GRANTED+=("${perm##*.}")
  else
    case "$out" in
      # Install-time, special or unknown-on-this-version permissions: nothing to grant.
      *"not a changeable permission type"* | *"is not a runtime permission"* | *"Unknown permission"* | *"has not requested permission"*) ;;
      *) FAILED+=("${perm##*.}") ;;
    esac
  fi
done
if [ ${#FAILED[@]} -eq 0 ]; then
  record ok "Granted ${#GRANTED[@]} runtime permissions${GRANTED[0]+: ${GRANTED[*]}}"
else
  record warn "Granted ${#GRANTED[@]} runtime permissions; could not grant: ${FAILED[*]} (grant them in PhoneBridge > Setup)"
fi

step "Special access"
if [ "$SDK" -ge 33 ]; then
  if dsh_checked "appops set $PKG ACCESS_RESTRICTED_SETTINGS allow" >/dev/null; then
    record ok "Restricted settings allowed (Android 13+ sideload restriction)"
  else
    record warn "Could not allow restricted settings; on the phone: Settings > Apps > PhoneBridge > ⋮ > Allow restricted settings"
  fi
else
  record skip "Restricted settings: not needed before Android 13"
fi

CURRENT_A11Y="$(dsh "settings get secure enabled_accessibility_services")"
case "$CURRENT_A11Y" in "null" | "") CURRENT_A11Y="" ;; esac
case ":$CURRENT_A11Y:" in
  *":$ACCESSIBILITY_SERVICE:"* | *":$ACCESSIBILITY_SERVICE_SHORT:"*) NEW_A11Y="$CURRENT_A11Y" ;;
  *) NEW_A11Y="${CURRENT_A11Y:+$CURRENT_A11Y:}$ACCESSIBILITY_SERVICE" ;;
esac
if dsh_checked "settings put secure enabled_accessibility_services $(shq "$NEW_A11Y")" >/dev/null &&
  dsh_checked "settings put secure accessibility_enabled 1" >/dev/null &&
  case ":$(dsh "settings get secure enabled_accessibility_services"):" in
    *":$ACCESSIBILITY_SERVICE:"* | *":$ACCESSIBILITY_SERVICE_SHORT:"*) true ;;
    *) false ;;
  esac; then
  record ok "Accessibility service enabled (other services kept)"
else
  record fail "Could not enable the accessibility service; on the phone: Settings > Accessibility > PhoneBridge agent control"
fi

if dsh_checked "cmd notification allow_listener $NOTIFICATION_LISTENER" >/dev/null; then
  record ok "Notification access granted"
else
  record fail "Could not grant notification access; on the phone: Settings > Notifications > Device & app notifications > PhoneBridge"
fi

if dsh_checked "cmd notification allow_dnd $PKG" >/dev/null; then
  record ok "Do Not Disturb access granted (ringer mode tool)"
else
  record warn "Could not grant Do Not Disturb access (only needed to change the ringer mode)"
fi

if dsh_checked "dumpsys deviceidle whitelist +$PKG" >/dev/null; then
  record ok "Battery optimization exemption (keeps the relay connection alive in Doze)"
else
  record warn "Could not exempt from battery optimization; allow it in PhoneBridge > Setup"
fi

if dsh_checked "appops set $PKG SYSTEM_ALERT_WINDOW allow" >/dev/null; then
  record ok "Display over other apps allowed (lets approval prompts open from the background)"
else
  record warn "Could not allow display over other apps"
fi

step "Launch"
if [ -n "$RELAY" ]; then
  URI="phonebridge://configure?relay=$(urlencode "$RELAY")"
  [ -n "$KEY" ] && URI="$URI&key=$(urlencode "$KEY")"
  if dsh_checked "am start -W -a android.intent.action.VIEW -d $(shq "$URI") -n $MAIN_ACTIVITY" >/dev/null; then
    record ok "Opened the relay setup for $RELAY${KEY:+ (with access key)}: tap Connect on the phone"
  else
    record fail "Could not open the relay setup link; enter the relay in PhoneBridge > Settings"
  fi
else
  if dsh_checked "am start -W -n $MAIN_ACTIVITY" >/dev/null; then
    record ok "PhoneBridge launched"
  else
    record warn "Could not launch PhoneBridge; open it from the app drawer"
  fi
  record skip "Relay: not configured (pass --relay URL [--key KEY], or set it in PhoneBridge > Settings)"
fi

print_summary
printf '\nNext: in PhoneBridge, copy "Your connection URL" and paste it into your AI agent, then tap Allow on the phone.\n'
for ((i = 0; i < ${#CHECK_STATUS[@]}; i++)); do
  [ "${CHECK_STATUS[$i]}" = "fail" ] && exit 1
done
exit 0
