#!/usr/bin/env bash
# Starts an app plugin from what an installed app declares (docs/PLUGINS.md §10, CONTRIBUTING.md): reads the
# app's intent filters from the phone over USB (`adb shell dumpsys package <package>`, no root needed) and
# prints a plugin.yaml skeleton with a TODO on everything to check.
#
#   ./scripts/plugin-scaffold.sh com.spotify.music            # print the skeleton
#   ./scripts/plugin-scaffold.sh --write com.spotify.music    # save it as plugins/<id>/plugin.yaml
#   ... [--name "Spotify"] [--id spotify] [--serial SERIAL]
#
# What it finds becomes actions: the app's share target (SEND text/plain), its text selection action
# (PROCESS_TEXT), media search, web search and geo: links. The links it opens (VIEW + BROWSABLE schemes and
# hosts) are listed as TODO comments to turn into `url` actions. The app's name comes from --name, else from
# its APK (`aapt2 dump badging`, when the Android build tools are installed), else from the package.
# Then: ./scripts/plugin-check.sh plugins/<id>
#
# Works with macOS's bash 3.2 and Linux. Finds adb via $ADB, $ANDROID_HOME, $ANDROID_SDK_ROOT, PATH and the
# usual SDK locations.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
readonly PACKAGE_PATTERN='^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$'
readonly ID_PATTERN='^[a-z][a-z0-9_]{1,39}$'

usage() {
  cat <<EOF
Usage: $(basename "$0") [--write] [--name NAME] [--id ID] [--serial SERIAL] <package>

Prints a plugin.yaml skeleton for an app installed on the connected phone (--write saves it as
plugins/<id>/plugin.yaml instead).
EOF
}

die() {
  echo "Error: $1" >&2
  [ -n "${2:-}" ] && echo "  $2" >&2
  exit 1
}

WRITE=""
NAME=""
ID=""
SERIAL=""
PKG=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --write) WRITE=1; shift ;;
    --name) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; NAME="$2"; shift 2 ;;
    --name=*) NAME="${1#*=}"; shift ;;
    --id) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; ID="$2"; shift 2 ;;
    --id=*) ID="${1#*=}"; shift ;;
    --serial) [ "$#" -ge 2 ] || { usage >&2; exit 2; }; SERIAL="$2"; shift 2 ;;
    --serial=*) SERIAL="${1#*=}"; shift ;;
    -*) usage >&2; exit 2 ;;
    *) [ -z "$PKG" ] || { usage >&2; exit 2; }; PKG="$1"; shift ;;
  esac
done
[ -n "$PKG" ] || { usage >&2; exit 2; }
printf '%s' "$PKG" | grep -Eq "$PACKAGE_PATTERN" || die "'$PKG' is not a package name" "For example: com.spotify.music (adb shell pm list packages lists them)."
case "$PKG" in
  dev.phonebridge.fixture.*) ;;
  dev.phonebridge|dev.phonebridge.*) die "Plugins can't target PhoneBridge itself" ;;
esac
if [ -n "$ID" ]; then
  printf '%s' "$ID" | grep -Eq "$ID_PATTERN" || die "'$ID' is not a valid plugin id" "Ids are lowercase letters, digits and _ (2 to 40 characters), starting with a letter."
fi

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

WORK="$(mktemp -d "${TMPDIR:-/tmp}/plugin-scaffold.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

"${ADB_CMD[@]}" shell dumpsys package "$PKG" </dev/null 2>/dev/null | tr -d '\r' >"$WORK/dumpsys" ||
  die "Could not read the app's details from $SERIAL" "Check the USB connection and run this again."
grep -q "Package \[$PKG\]" "$WORK/dumpsys" ||
  die "$PKG is not installed on $SERIAL" "adb shell pm list packages | grep <name> finds the package name."

VERSION="$(sed -n 's/^ *versionName=\([^ ]*\).*/\1/p' "$WORK/dumpsys" | head -n 1)"

# ------------------------------------------------------------------------------------------ name

find_aapt() {
  local sdk dir
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools; do
    [ -n "$sdk" ] && [ -d "$sdk/build-tools" ] || continue
    for dir in $(ls -1 "$sdk/build-tools" 2>/dev/null | sort -r); do
      if [ -x "$sdk/build-tools/$dir/aapt2" ]; then printf '%s' "$sdk/build-tools/$dir/aapt2"; return 0; fi
      if [ -x "$sdk/build-tools/$dir/aapt" ]; then printf '%s' "$sdk/build-tools/$dir/aapt"; return 0; fi
    done
  done
  return 1
}

if [ -z "$NAME" ]; then
  AAPT="$(find_aapt || true)"
  # The base APK holds the label (split APKs don't); `pm path` needs no root.
  PATHS="$( { "${ADB_CMD[@]}" shell pm path "$PKG" </dev/null 2>/dev/null || true; } | tr -d '\r' | sed -n 's/^package://p')"
  APK="$(printf '%s\n' "$PATHS" | grep -E '/base\.apk$' | head -n 1 || true)"
  [ -n "$APK" ] || APK="$(printf '%s\n' "$PATHS" | head -n 1)"
  if [ -n "$AAPT" ] && [ -n "$APK" ]; then
    echo "Reading the app's name from its APK..." >&2
    if "${ADB_CMD[@]}" pull "$APK" "$WORK/base.apk" </dev/null >/dev/null 2>&1; then
      NAME="$("$AAPT" dump badging "$WORK/base.apk" 2>/dev/null | sed -n "s/^application-label:'\(.*\)'$/\1/p" | head -n 1 || true)"
    fi
  fi
fi
if [ -z "$NAME" ]; then
  NAME="$(printf '%s' "$PKG" | awk -F. '{ print $NF }')"
  NAME="$(printf '%s' "${NAME:0:1}" | tr '[:lower:]' '[:upper:]')${NAME:1}"
  echo "Using \"$NAME\" as the app's name (pass --name \"App name\" to set it)." >&2
fi

# ------------------------------------------------------------------------------------------ id

slug() {
  # Lowercase ASCII words joined by _, starting with a letter, at most 40 characters.
  printf '%s' "$1" | LC_ALL=C tr -c 'A-Za-z0-9' '_' | tr '[:upper:]' '[:lower:]' |
    sed -e 's/__*/_/g' -e 's/^[^a-z]*//' -e 's/_$//' | cut -c 1-40 | sed -e 's/_*$//'
}

if [ -z "$ID" ]; then
  ID="$(slug "$NAME")"
  if ! printf '%s' "$ID" | grep -Eq "$ID_PATTERN"; then
    ID="$(slug "$(printf '%s\n' "$PKG" | tr '.' '\n' | { grep -Ev '^(com|org|net|io|co|de|me|www|android|app|apps|mobile)$' || true; } | tr '\n' '_')")"
  fi
  printf '%s' "$ID" | grep -Eq "$ID_PATTERN" || ID="myapp"
fi

# ------------------------------------------------------------------------------------------ skeleton

# The Activity Resolver Table lists each intent filter of the app's activities (once per index it is in):
#         3c7d1e5 com.spotify.music/.MainActivity filter 9d1f3a2
#           Action: "android.intent.action.VIEW"
#           Category: "android.intent.category.BROWSABLE"
#           Scheme: "spotify"
#           Authority: "open.spotify.com": -1
awk -v pkg="$PKG" -v id="$ID" -v name="$NAME" -v version="$VERSION" '
function q(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); return "\"" s "\"" }
function value(line) { sub(/^[^"]*"/, "", line); sub(/".*$/, "", line); return line }
function flush() {
  if (current == "" || (current in seen)) { current = ""; return }
  seen[current] = 1
  n++
  cls[n] = comp; act[n] = acts; cat[n] = cats; sch[n] = schemes; aut[n] = hosts; typ[n] = types
  current = ""
}
function relative(c) {
  if (substr(c, 1, 1) == ".") return c
  if (index(c, pkg ".") == 1) return substr(c, length(pkg) + 1)
  return ""
}
function has(list, item) { return index("|" list "|", "|" item "|") > 0 }
function classLine(c, r) {
  r = relative(c)
  if (r != "") print "        class: " r
}
function classNote(c) {
  if (relative(c) == "") {
    print "  # The app handles it in " c ", which a plugin can'"'"'t name (docs/PLUGINS.md §6.2);"
    print "  # Android picks the app'"'"'s matching screen instead."
  }
}
function expectClass(c, r) { r = relative(c); return r == "" ? "" : ", class: " r }
function header(what) {
  if (count > 0) print ""
  count++
  print "  # TODO: found from " what ". Check the title and description, and the risk."
}

/^Activity Resolver Table:/ { table = 1; next }
table && /^[^ ]/ { flush(); table = 0 }
!table { next }
/ filter [0-9a-f]+$/ {
  flush()
  comp = $2
  if (index(comp, pkg "/") != 1) next
  comp = substr(comp, length(pkg) + 2)
  current = $NF; acts = ""; cats = ""; schemes = ""; hosts = ""; types = ""
  next
}
current == "" { next }
/^ +Action: "/ { acts = acts "|" value($0); next }
/^ +Category: "/ { cats = cats "|" value($0); next }
/^ +Scheme: "/ { schemes = schemes "|" value($0); next }
/^ +Authority: "/ { hosts = hosts "|" value($0); next }
/^ +Type: "/ { types = types "|" value($0); next }

END {
  flush()
  for (i = 1; i <= n; i++) {
    c = cls[i]
    if (c != "" && substr(c, 1, 1) == ".") c = pkg c
    if (has(act[i], "android.intent.action.MAIN") && has(cat[i], "android.intent.category.LAUNCHER") && launcher == "") launcher = c
    textType = has(typ[i], "text/plain") || has(typ[i], "text/*") || has(typ[i], "*/*")
    if (has(act[i], "android.intent.action.SEND") && textType && share == "") share = c
    if (has(act[i], "android.intent.action.PROCESS_TEXT") && processText == "") processText = c
    if (has(act[i], "android.media.action.MEDIA_PLAY_FROM_SEARCH") && play == "") play = c
    if (has(act[i], "android.intent.action.WEB_SEARCH") && web == "") web = c
    if (has(act[i], "android.intent.action.VIEW") && has(sch[i], "geo") && geo == "") geo = c
    if (has(act[i], "android.intent.action.VIEW") && has(cat[i], "android.intent.category.BROWSABLE")) {
      ns = split(substr(sch[i], 2), s, "|")
      nh = split(substr(aut[i], 2), h, "|")
      for (k = 1; k <= ns; k++) {
        if (s[k] == "" || s[k] == "geo") continue
        if (s[k] == "http" || s[k] == "https") {
          for (m = 1; m <= nh; m++) if (h[m] != "") { link = "https://" h[m] "/..."; if (!(link in linkSeen)) { linkSeen[link] = 1; links[++linkCount] = link } }
        } else {
          link = s[k] ":..."; if (!(link in linkSeen)) { linkSeen[link] = 1; links[++linkCount] = link }
        }
      }
    }
  }

  print "# yaml-language-server: $schema=../plugin.schema.json"
  print "#"
  print "# Draft plugin for " name " (" pkg (version != "" ? ", version " version : "") "), made by scripts/plugin-scaffold.sh from"
  print "# the intent filters the app declares. To finish it (docs/PLUGINS.md, CONTRIBUTING.md):"
  print "#   1. Fill in every TODO, keep the actions that are useful and give them clear titles and descriptions."
  print "#   2. Turn the links the app opens (listed at the end) into url actions."
  print "#   3. Run ./scripts/plugin-check.sh plugins/" id ", then try it on the phone (./scripts/plugin-push.sh plugins/" id ")."
  print "id: " id
  print "name: " q(name)
  print "description: " q("TODO: one sentence about " name ".")
  print "category: other                 # TODO: pick one (the categories are listed in docs/PLUGINS.md §3)"
  print "# homepage: https://...         # TODO: the app'"'"'s website (optional)"
  print "status: experimental"
  print "# maintainers: [your-github-handle]"
  if (version != "") {
    print "# tested:                       # TODO: once you ran every action on a phone"
    print "#   - {app_version: " q(version) ", android: \"14\", date: \"YYYY-MM-DD\", by: your-github-handle}"
  }
  print "android:"
  print "  packages: [" pkg "]"
  print ""
  print "actions:"

  if (share != "") {
    header("the app'"'"'s share target (SEND, text/plain)"); classNote(share)
    print "  - id: share_text"
    print "    title: Share text"
    print "    description: " q("Opens " name "'"'"'s share screen with the text filled in. Nothing is posted or sent until someone taps send there.")
    print "    risk: action"
    print "    params:"
    print "      text: {description: The text to share, required: true, max_length: 5000}"
    print "    android:"
    print "      intent:"
    print "        action: android.intent.action.SEND"
    print "        type: text/plain"
    classLine(share)
    print "        extras:"
    print "          android.intent.extra.TEXT: \"{{text}}\""
    print "    examples:"
    print "      - params: {text: \"hello world\"}"
    print "        expect: {intent: {action: android.intent.action.SEND, package: " pkg expectClass(share) ", type: text/plain, extras: {android.intent.extra.TEXT: \"hello world\"}}}"
    print "      - params: {}"
    print "        expect: {error: text}"
  }
  if (processText != "") {
    header("the app'"'"'s text selection action (PROCESS_TEXT)"); classNote(processText)
    print "  - id: process_text"
    print "    title: Process text              # TODO: say what it does, e.g. Translate text"
    print "    description: " q("Hands the text to " name "'"'"'s text action (the one Android offers in the text selection menu) and opens its result.")
    print "    risk: action"
    print "    params:"
    print "      text: {description: The text to process, required: true, max_length: 5000}"
    print "    android:"
    print "      intent:"
    print "        action: android.intent.action.PROCESS_TEXT"
    print "        type: text/plain"
    classLine(processText)
    print "        extras:"
    print "          android.intent.extra.PROCESS_TEXT: \"{{text}}\""
    print "          android.intent.extra.PROCESS_TEXT_READONLY: true"
    print "    examples:"
    print "      - params: {text: \"hello world\"}"
    print "        expect: {intent: {action: android.intent.action.PROCESS_TEXT, package: " pkg expectClass(processText) ", extras: {android.intent.extra.PROCESS_TEXT: \"hello world\"}}}"
    print "      - params: {}"
    print "        expect: {error: text}"
  }
  if (play != "") {
    header("the app'"'"'s media search (MEDIA_PLAY_FROM_SEARCH)"); classNote(play)
    print "  - id: play_from_search"
    print "    title: Play music"
    print "    description: " q("Asks " name " to find and play what the query names (a song, artist, album, playlist or podcast).")
    print "    risk: action"
    print "    params:"
    print "      query: {description: What to play, required: true, max_length: 500}"
    print "    completes: true"
    print "    android:"
    print "      intent:"
    print "        action: android.media.action.MEDIA_PLAY_FROM_SEARCH"
    classLine(play)
    print "        extras:"
    print "          query: \"{{query}}\""
    print "          android.intent.extra.focus: \"vnd.android.cursor.item/*\""
    print "    examples:"
    print "      - params: {query: \"hello world\"}"
    print "        expect: {intent: {action: android.media.action.MEDIA_PLAY_FROM_SEARCH, package: " pkg expectClass(play) ", extras: {query: \"hello world\"}}}"
    print "      - params: {}"
    print "        expect: {error: query}"
  }
  if (web != "") {
    header("the app'"'"'s search handler (WEB_SEARCH)"); classNote(web)
    print "  - id: web_search"
    print "    title: Search"
    print "    description: " q("Opens " name "'"'"'s search results for the query.")
    print "    risk: action"
    print "    params:"
    print "      query: {description: What to search for, required: true, max_length: 500}"
    print "    android:"
    print "      intent:"
    print "        action: android.intent.action.WEB_SEARCH"
    classLine(web)
    print "        extras:"
    print "          query: \"{{query}}\""
    print "    examples:"
    print "      - params: {query: \"hello world\"}"
    print "        expect: {intent: {action: android.intent.action.WEB_SEARCH, package: " pkg expectClass(web) ", extras: {query: \"hello world\"}}}"
    print "      - params: {}"
    print "        expect: {error: query}"
  }
  if (geo != "") {
    header("the app'"'"'s geo: link handler"); classNote(geo)
    print "  - id: show_on_map"
    print "    title: Show on map"
    print "    description: " q("Shows a place or address in " name " (a geo: link). It does not start navigation.")
    print "    risk: action"
    print "    params:"
    print "      query: {description: A place name or address, required: true, max_length: 500}"
    print "    android:"
    print "      intent:"
    print "        action: android.intent.action.VIEW"
    print "        data: \"geo:0,0?q={{query}}\""
    classLine(geo)
    print "    examples:"
    print "      - params: {query: \"hello world\"}"
    print "        expect: {intent: {action: android.intent.action.VIEW, package: " pkg expectClass(geo) ", data: \"geo:0,0?q=hello%20world\"}}"
    print "      - params: {}"
    print "        expect: {error: query}"
  }
  if (count == 0) {
    # Nothing else to start from: the launcher screen, so the file is valid as it is.
    header("the app'"'"'s launcher screen (agents get this one without a plugin too)"); if (launcher != "") classNote(launcher)
    print "  - id: open"
    print "    title: Open the app"
    print "    description: " q("Opens " name " on its main screen.")
    print "    risk: action"
    print "    completes: true"
    print "    android:"
    print "      intent:"
    print "        action: android.intent.action.MAIN"
    print "        categories: [android.intent.category.LAUNCHER]"
    if (launcher != "") classLine(launcher)
  }
  print ""
  if (linkCount > 0) {
    print "  # TODO: " name " opens these links. Turn the useful ones into url actions (docs/PLUGINS.md §6.3):"
    for (i = 1; i <= linkCount && i <= 20; i++) print "  #   " links[i]
    if (linkCount > 20) print "  #   (and " (linkCount - 20) " more)"
  } else {
    print "  # TODO: add the app'"'"'s own deep links as url actions (docs/PLUGINS.md §6.3)."
  }
  print "  # - id: search"
  print "  #   title: Search"
  print "  #   description: Opens " name "'"'"'s search results for a query."
  print "  #   risk: action"
  print "  #   params:"
  print "  #     query: {description: What to search for, required: true, max_length: 200}"
  print "  #   android:"
  print "  #     url: \"https://example.com/search?q={{query}}\""
}
' "$WORK/dumpsys" >"$WORK/plugin.yaml"

if [ -z "$WRITE" ]; then
  cat "$WORK/plugin.yaml"
  exit 0
fi
TARGET_DIR="$REPO_ROOT/plugins/$ID"
[ ! -e "$TARGET_DIR/plugin.yaml" ] || die "plugins/$ID/plugin.yaml already exists" "Edit it, or pass --id another_id."
mkdir -p "$TARGET_DIR"
cp "$WORK/plugin.yaml" "$TARGET_DIR/plugin.yaml"
echo "Wrote plugins/$ID/plugin.yaml. Fill in the TODOs, then run ./scripts/plugin-check.sh plugins/$ID"
