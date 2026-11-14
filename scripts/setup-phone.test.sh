#!/usr/bin/env bash
# Tests scripts/setup-phone.sh without a device: a fake `adb` runs each `adb shell` command line
# through a real sh with fake device tools (pm, settings, appops, cmd, dumpsys, am, getprop) that
# log their arguments one per [bracket], so quoting is checked exactly as a phone would parse it.
#
#   ./scripts/setup-phone.test.sh                      # with the bash on PATH
#   PATH=/bin:/usr/bin ./scripts/setup-phone.test.sh   # macOS bash 3.2
#   SHOW_OUTPUT=1 ./scripts/setup-phone.test.sh        # also print each run's output
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETUP="$SCRIPT_DIR/setup-phone.sh"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/setup-phone-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

PASSED=0
FAILED=0

# ------------------------------------------------------------------------------------- fake world

mkdir -p "$WORK/bin" "$WORK/device"
printf 'fake apk' >"$WORK/app.apk"

cat >"$WORK/bin/adb" <<'EOF'
#!/usr/bin/env bash
set -u
printf 'adb%s\n' "$(printf ' [%s]' "$@")" >>"$FAKE_LOG"
if [ "${1:-}" = "-s" ]; then
  printf '%s\n' "$2" >>"$FAKE_STATE/serials"
  shift 2
fi
case "${1:-}" in
  devices) printf 'List of devices attached\n%b\n' "${FAKE_DEVICES-emulator-5554\tdevice}" ;;
  get-state) echo device ;;
  install)
    echo "Performing Streamed Install"
    echo "${FAKE_INSTALL:-Success}"
    case "${FAKE_INSTALL:-Success}" in Success) exit 0 ;; *) exit 1 ;; esac ;;
  # The real adb joins its arguments into one line for the device's sh; the script passes one.
  shell) PATH="$FAKE_DEVICE_BIN:/usr/bin:/bin" /bin/sh -c "$2" ;;
  *) echo "fake adb: unsupported: $*" >&2; exit 1 ;;
esac
EOF

device_tool() { # name body
  {
    echo '#!/bin/sh'
    echo 'printf "%s" "$(basename "$0")" >>"$FAKE_LOG"; for a in "$@"; do printf " [%s]" "$a" >>"$FAKE_LOG"; done; echo >>"$FAKE_LOG"'
    printf '%s\n' "$2"
  } >"$WORK/device/$1"
}

device_tool getprop 'case "$1" in
  ro.build.version.sdk) echo "${FAKE_SDK:-34}" ;;
  ro.build.version.release) echo "14" ;;
  ro.product.model) echo "Pixel 8" ;;
esac'

device_tool dumpsys 'case "$1" in
  package)
    [ -n "${FAKE_NO_DUMPSYS:-}" ] && exit 0
    cat <<OUT
Packages:
  Package [dev.phonebridge.android] (1234abc):
    versionName=0.1.0
    requested permissions:
      android.permission.INTERNET
      android.permission.POST_NOTIFICATIONS
      android.permission.READ_CONTACTS
      android.permission.READ_SMS: restricted=true
      android.permission.ACCESS_BACKGROUND_LOCATION: restricted=true
      dev.phonebridge.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
    install permissions:
      android.permission.INTERNET: granted=true
OUT
    ;;
  deviceidle) echo "Added: ${3#+}" ;;
esac'

device_tool pm 'case "$3" in
  android.permission.INTERNET | dev.phonebridge.android.*)
    echo "Exception occurred while executing '"'"'grant'"'"':" >&2
    echo "java.lang.SecurityException: Permission $3 requested by $2 is not a changeable permission type" >&2
    exit 255 ;;
  android.permission.READ_SMS)
    echo "java.lang.SecurityException: Cannot grant hard restricted non-exempt permission $3" >&2
    exit 255 ;;
esac'

device_tool settings 'f="$FAKE_STATE/settings_$2_$3"
case "$1" in
  get) if [ -f "$f" ]; then cat "$f"; echo; else echo null; fi ;;
  put) printf "%s" "$4" >"$f" ;;
esac'

device_tool appops 'case " ${FAKE_APPOPS_FAIL:-} " in
  *" $3 "*) echo "Error: Unknown operation string: $3"; exit 1 ;;
esac'

device_tool cmd ':'

device_tool am 'echo "Starting: Intent { }"; echo "Status: ok"'

chmod +x "$WORK/bin/adb" "$WORK"/device/*

# ----------------------------------------------------------------------------------------- runner

OUT=""
STATUS=0
CURRENT=""

# Fresh device state and log; every test starts with this.
reset() {
  rm -rf "$WORK/state"
  mkdir -p "$WORK/state"
  : >"$WORK/log"
}

# run [VAR=value ...] -- setup-phone.sh arguments   (keeps the device state, clears the log)
run() {
  : >"$WORK/log"
  local envs=()
  while [ $# -gt 0 ] && [ "$1" != "--" ]; do
    envs+=("$1")
    shift
  done
  shift
  set +e
  OUT="$(env NO_COLOR=1 ADB="$WORK/bin/adb" FAKE_LOG="$WORK/log" FAKE_STATE="$WORK/state" FAKE_DEVICE_BIN="$WORK/device" \
    ${envs[@]+"${envs[@]}"} bash "$SETUP" "$@" 2>&1)"
  STATUS=$?
  set -e
}

# Pre-sets a secure setting on the fake device.
given_setting() { printf '%s' "$2" >"$WORK/state/settings_secure_$1"; }

fail() {
  FAILED=$((FAILED + 1))
  printf 'FAIL %s: %s\n' "$CURRENT" "$1"
  printf '%s\n' "--- output ---" "$OUT" "--- device log ---"
  cat "$WORK/log" 2>/dev/null || true
}

expect_status() { [ "$STATUS" -eq "$1" ] || fail "exit status $STATUS, expected $1"; }
expect_out() { case "$OUT" in *"$1"*) ;; *) fail "output lacks: $1" ;; esac; }
expect_no_out() { case "$OUT" in *"$1"*) fail "output must not contain: $1" ;; esac; }
expect_log() { grep -qF -- "$1" "$WORK/log" || fail "device log lacks: $1"; }
expect_no_log() { if grep -qF -- "$1" "$WORK/log"; then fail "device log must not contain: $1"; fi; }
expect_setting() { # name expected
  local actual
  actual="$(cat "$WORK/state/settings_secure_$1" 2>/dev/null || echo "<unset>")"
  [ "$actual" = "$2" ] || fail "setting $1 = '$actual', expected '$2'"
}

test_case() {
  CURRENT="$1"
  local before=$FAILED
  reset
  "$1"
  if [ -n "${SHOW_OUTPUT:-}" ]; then printf '%s\n' "$OUT"; fi
  if [ "$FAILED" -eq "$before" ]; then
    PASSED=$((PASSED + 1))
    printf 'ok   %s\n' "$1"
  fi
}

# ------------------------------------------------------------------------------------------ tests

A11Y="dev.phonebridge.android/dev.phonebridge.android.access.BridgeAccessibilityService"
OTHER_A11Y="com.other/com.other.Service"

full_setup_with_relay_and_key() {
  run -- --apk "$WORK/app.apk" --relay "https://relay.example.com/" --key "s3cr+t &x/y'z"
  expect_status 0
  expect_log "adb [-s] [emulator-5554] [install] [-r] [$WORK/app.apk]"
  expect_log "pm [grant] [dev.phonebridge.android] [android.permission.POST_NOTIFICATIONS]"
  expect_log "pm [grant] [dev.phonebridge.android] [android.permission.READ_CONTACTS]"
  expect_log "pm [grant] [dev.phonebridge.android] [android.permission.ACCESS_BACKGROUND_LOCATION]"
  expect_out "could not grant: READ_SMS"
  expect_no_out "INTERNET"
  expect_log "appops [set] [dev.phonebridge.android] [ACCESS_RESTRICTED_SETTINGS] [allow]"
  expect_log "appops [set] [dev.phonebridge.android] [SYSTEM_ALERT_WINDOW] [allow]"
  expect_log "cmd [notification] [allow_listener] [dev.phonebridge.android/dev.phonebridge.android.notify.BridgeNotificationListener]"
  expect_log "cmd [notification] [allow_dnd] [dev.phonebridge.android]"
  expect_log "dumpsys [deviceidle] [whitelist] [+dev.phonebridge.android]"
  expect_setting enabled_accessibility_services "$A11Y"
  expect_setting accessibility_enabled "1"
  expect_log "am [start] [-W] [-a] [android.intent.action.VIEW] [-d] [phonebridge://configure?relay=https%3A%2F%2Frelay.example.com&key=s3cr%2Bt%20%26x%2Fy%27z] [-n] [dev.phonebridge.android/.ui.MainActivity]"
  expect_no_out "s3cr+t"
  expect_out "tap Connect on the phone"
  expect_out "Summary"
}

appends_to_existing_accessibility_services() {
  given_setting enabled_accessibility_services "$OTHER_A11Y"
  run -- --apk "$WORK/app.apk"
  expect_status 0
  expect_setting enabled_accessibility_services "$OTHER_A11Y:$A11Y"
}

rerun_keeps_accessibility_services_unchanged() {
  given_setting enabled_accessibility_services "$OTHER_A11Y:$A11Y"
  run -- --apk "$WORK/app.apk"
  run -- --apk "$WORK/app.apk"
  expect_status 0
  expect_setting enabled_accessibility_services "$OTHER_A11Y:$A11Y"
}

short_form_counts_as_enabled() {
  given_setting enabled_accessibility_services "dev.phonebridge.android/.access.BridgeAccessibilityService"
  run -- --apk "$WORK/app.apk"
  expect_status 0
  expect_setting enabled_accessibility_services "dev.phonebridge.android/.access.BridgeAccessibilityService"
}

without_relay_just_launches() {
  run -- --apk "$WORK/app.apk"
  expect_status 0
  expect_log "am [start] [-W] [-n] [dev.phonebridge.android/.ui.MainActivity]"
  expect_no_log "phonebridge://configure"
  expect_out "Relay: not configured"
}

android_12_skips_restricted_settings() {
  run FAKE_SDK=31 -- --apk "$WORK/app.apk"
  expect_status 0
  expect_no_log "ACCESS_RESTRICTED_SETTINGS"
  expect_out "not needed before Android 13"
}

android_10_is_refused() {
  run FAKE_SDK=29 -- --apk "$WORK/app.apk"
  expect_status 1
  expect_out "needs Android 11"
  expect_no_log "[install]"
}

no_device() {
  run FAKE_DEVICES= -- --apk "$WORK/app.apk"
  expect_status 1
  expect_out "No device connected"
  expect_out "Summary"
}

unauthorized_device() {
  run "FAKE_DEVICES=R58M\tunauthorized" -- --apk "$WORK/app.apk"
  expect_status 1
  expect_out "accept the 'Allow USB debugging?' prompt"
}

several_devices_need_serial() {
  run "FAKE_DEVICES=emulator-5554\tdevice\nR58M\tdevice" -- --apk "$WORK/app.apk"
  expect_status 1
  expect_out "Several devices connected"
  expect_out "--serial"
}

serial_selects_the_device() {
  run "FAKE_DEVICES=emulator-5554\tdevice\nR58M\tdevice" -- --apk "$WORK/app.apk" --serial R58M
  expect_status 0
  expect_log "adb [-s] [R58M] [install]"
  expect_no_log "[-s] [emulator-5554]"
}

incompatible_install_explains_uninstall() {
  run "FAKE_INSTALL=Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match]" -- --apk "$WORK/app.apk"
  expect_status 1
  expect_out "uninstall dev.phonebridge.android"
  expect_no_log "pm [grant]"
}

failing_appops_is_a_warning() {
  run "FAKE_APPOPS_FAIL=SYSTEM_ALERT_WINDOW" -- --apk "$WORK/app.apk"
  expect_status 0
  expect_out "Could not allow display over other apps"
}

falls_back_to_manifest_permissions() {
  run FAKE_NO_DUMPSYS=1 -- --apk "$WORK/app.apk"
  expect_status 0
  expect_log "pm [grant] [dev.phonebridge.android] [android.permission.ANSWER_PHONE_CALLS]"
  expect_log "pm [grant] [dev.phonebridge.android] [android.permission.WRITE_CALENDAR]"
}

missing_apk() {
  run -- --apk "$WORK/nope.apk"
  expect_status 1
  expect_out "APK not found"
}

argument_errors() {
  run -- --key abc
  expect_status 2
  expect_out "--key needs --relay"
  run -- --relay relay.example.com
  expect_status 2
  expect_out "must start with http:// or https://"
  run -- --apk
  expect_status 2
  expect_out "--apk needs a value"
  run -- --bogus
  expect_status 2
  expect_out "unknown option"
  run -- --help
  expect_status 0
  expect_out "Usage:"
  expect_no_out "Summary"
}

for t in full_setup_with_relay_and_key appends_to_existing_accessibility_services rerun_keeps_accessibility_services_unchanged \
  short_form_counts_as_enabled without_relay_just_launches android_12_skips_restricted_settings android_10_is_refused \
  no_device unauthorized_device several_devices_need_serial serial_selects_the_device incompatible_install_explains_uninstall \
  failing_appops_is_a_warning falls_back_to_manifest_permissions missing_apk argument_errors; do
  test_case "$t"
done

printf '\n%d passed, %d failed (bash %s)\n' "$PASSED" "$FAILED" "$BASH_VERSION"
[ "$FAILED" -eq 0 ]
