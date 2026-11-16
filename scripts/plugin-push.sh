#!/usr/bin/env bash
# Tries a plugin on a phone without rebuilding DroidHands: pushes it to a DEBUG build as a local plugin
# (docs/PLUGINS.md §10).
#
#   ./scripts/plugin-push.sh plugins/myapp              # a plugin folder (its plugin.yaml)
#   ./scripts/plugin-push.sh path/to/plugin.yaml        # or the file itself
#   ./scripts/plugin-push.sh --remove myapp             # delete a pushed plugin
#   ... [--serial SERIAL]                               # when several devices are connected
#
# It writes files/local-plugins/<id>.yaml in DroidHands's private storage with `adb shell run-as`, which
# Android only allows for debuggable builds; with a release build, use Settings > Developer > Import plugin.
# The app picks the file up on the next app-action call (no restart). Local plugins are off by default: turn
# on Settings > Developer > Local plugins. Their actions always ask for approval on the phone, and the app
# validates the file again (problems show in Settings > Developer). Check it first with
# ./scripts/plugin-check.sh plugins/myapp.
#
# Works with macOS's bash 3.2 and Linux. Finds adb via $ADB, $ANDROID_HOME, $ANDROID_SDK_ROOT, PATH and the
# usual SDK locations.
set -euo pipefail

readonly PKG="com.droidhands.android"
readonly DIR="files/local-plugins"
readonly ID_PATTERN='^[a-z][a-z0-9_]{1,39}$'

usage() {
  cat <<EOF
Usage: $(basename "$0") [--serial SERIAL] plugins/<id> | path/to/plugin.yaml
       $(basename "$0") [--serial SERIAL] --remove <id>

Pushes a plugin to a DroidHands debug build as a local plugin (Settings > Developer > Local plugins must be on).
EOF
}

die() {
  echo "Error: $1" >&2
  [ -n "${2:-}" ] && echo "  $2" >&2
  exit 1
}

SERIAL=""
REMOVE=""
TARGET=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --serial) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; SERIAL="$2"; shift 2 ;;
    --serial=*) SERIAL="${1#*=}"; shift ;;
    --remove) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; REMOVE="$2"; shift 2 ;;
    --remove=*) REMOVE="${1#*=}"; shift ;;
    -*) usage >&2; exit 2 ;;
    *) [ -z "$TARGET" ] || { usage >&2; exit 2; }; TARGET="$1"; shift ;;
  esac
done
if [ -n "$REMOVE" ] && [ -n "$TARGET" ]; then usage >&2; exit 2; fi
if [ -z "$REMOVE" ] && [ -z "$TARGET" ]; then usage >&2; exit 2; fi

# ------------------------------------------------------------------------------------------ plugin

FILE=""
ID=""
if [ -n "$REMOVE" ]; then
  ID="$REMOVE"
else
  if [ -d "$TARGET" ]; then FILE="$TARGET/plugin.yaml"; else FILE="$TARGET"; fi
  [ -f "$FILE" ] || die "No plugin file at $FILE" "Pass a plugin folder (plugins/<id>) or its plugin.yaml."
  # The top-level `id:` line (quotes and a trailing comment allowed).
  ID="$(sed -n 's/^id:[[:space:]]*["'\'']\{0,1\}\([A-Za-z0-9_]*\)["'\'']\{0,1\}[[:space:]]*\(#.*\)\{0,1\}$/\1/p' "$FILE" | head -n 1)"
  [ -n "$ID" ] || die "Could not read the plugin id from $FILE" "The file needs a top-level line like: id: myapp"
fi
printf '%s' "$ID" | grep -Eq "$ID_PATTERN" || die "'$ID' is not a valid plugin id" "Ids are lowercase letters, digits and _ (2 to 40 characters), starting with a letter."

# ------------------------------------------------------------------------------------------ device

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

ADB_BIN="$(find_adb)" || die "adb not found" "Install Android platform-tools, or set ADB=/path/to/adb or ANDROID_HOME."
if [ -z "$SERIAL" ]; then
  READY="$( { "$ADB_BIN" devices </dev/null 2>/dev/null || true; } | tr -d '\r' | awk 'NR > 1 && $2 == "device" { print $1 }')"
  COUNT="$(printf '%s\n' "$READY" | grep -c . || true)"
  [ "$COUNT" -gt 0 ] || die "No device connected" "Connect the phone with USB debugging on (or start an emulator)."
  [ "$COUNT" -eq 1 ] || die "Several devices connected: $(printf '%s' "$READY" | tr '\n' ' ')" "Choose one with --serial SERIAL."
  SERIAL="$READY"
fi
ADB_CMD=("$ADB_BIN" -s "$SERIAL")

# Runs a command line in the app's private storage (run-as); prints its output without CRs.
as_app() {
  "${ADB_CMD[@]}" shell "run-as $PKG sh -c '$1'" </dev/null 2>&1 | tr -d '\r'
}

PROBE="$(as_app 'echo ok' || true)"
if [ "$PROBE" != "ok" ]; then
  die "Can't write to DroidHands's storage on $SERIAL ($PROBE)" \
    "This needs a debug build of DroidHands (./scripts/setup-phone.sh installs one). With another build, use Settings > Developer > Import plugin."
fi

# ------------------------------------------------------------------------------------------ push

if [ -n "$REMOVE" ]; then
  as_app "rm -f $DIR/$ID.yaml" >/dev/null || die "Could not remove '$ID' from $SERIAL"
  echo "Removed the local plugin '$ID' (if it was there)."
  exit 0
fi

"${ADB_CMD[@]}" shell "run-as $PKG sh -c 'mkdir -p $DIR && cat > $DIR/$ID.yaml'" < "$FILE" ||
  die "Pushing $FILE to $SERIAL failed" "Check the USB connection and run this again."
LOCAL_BYTES="$(wc -c < "$FILE" | tr -d ' ')"
PUSHED_BYTES="$( { as_app "wc -c < $DIR/$ID.yaml" || true; } | tr -dc '0-9')"
[ "$LOCAL_BYTES" = "$PUSHED_BYTES" ] || die "The pushed file has $PUSHED_BYTES bytes instead of $LOCAL_BYTES" "Check the USB connection and run this again."

echo "Pushed '$ID' to $SERIAL as a local plugin ($DIR/$ID.yaml); DroidHands loads it on the next app-action call."
if as_app 'cat shared_prefs/bridge_settings.xml 2>/dev/null' | grep -q 'name="local_plugins_enabled" value="true"'; then
  echo "Local plugins are on. Every action of this plugin asks for your approval on the phone."
else
  echo "Reminder: turn on Settings > Developer > Local plugins in the DroidHands app, or agents won't see this plugin."
fi
echo "Problems with the file show in Settings > Developer. Try it: app_actions_list(app=\"<app>\"), then app_action_run."
