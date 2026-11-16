#!/usr/bin/env bash
# Creates the key that signs DroidHands releases, and prints (does not run) the GitHub CLI
# commands that store it as the secrets the release workflow needs. See docs/RELEASING.md.
#
#   ./scripts/make-release-key.sh [--out PATH] [--alias NAME] [--repo OWNER/NAME]
#
# Writes a PKCS12 keystore (RSA 4096, valid ~27 years) and a file with its random password next to
# it, both readable only by you. Keep both safe and backed up offline: Android only installs an
# update signed with the same key, so if the key is lost, every user has to uninstall (losing
# their agents and settings) to install a newer version. Never commit them.
#
# Needs keytool (from a JDK 17+) and openssl. Works with macOS's bash 3.2 and Linux.
set -euo pipefail

OUT="$HOME/.droidhands/droidhands-release.jks"
ALIAS="droidhands"
REPO="<owner>/<repo>"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--out PATH] [--alias NAME] [--repo OWNER/NAME]

  --out PATH         Keystore to create (default: $OUT). Must not exist yet.
  --alias NAME       Key alias (default: $ALIAS).
  --repo OWNER/NAME  GitHub repository to show in the printed commands (default: a placeholder).
EOF
}

need_value() {
  if [ $# -lt 2 ] || [ -z "$2" ]; then
    echo "error: $1 needs a value" >&2
    exit 2
  fi
}

while [ $# -gt 0 ]; do
  case "$1" in
    --out) need_value "$@"; OUT="$2"; shift 2 ;;
    --out=*) OUT="${1#*=}"; shift ;;
    --alias) need_value "$@"; ALIAS="$2"; shift 2 ;;
    --alias=*) ALIAS="${1#*=}"; shift ;;
    --repo) need_value "$@"; REPO="$2"; shift 2 ;;
    --repo=*) REPO="${1#*=}"; shift ;;
    -h | --help) usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$ALIAS" in
  "" | *[!A-Za-z0-9._-]*) echo "error: --alias may only contain letters, digits, '.', '_' and '-'" >&2; exit 2 ;;
esac

KEYTOOL="keytool"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/keytool" ]; then KEYTOOL="$JAVA_HOME/bin/keytool"; fi
if ! command -v "$KEYTOOL" >/dev/null 2>&1; then
  echo "error: keytool not found. Install a JDK 17+ (e.g. Temurin) or set JAVA_HOME." >&2
  exit 1
fi
if ! command -v openssl >/dev/null 2>&1; then
  echo "error: openssl not found (needed to generate the password)." >&2
  exit 1
fi

PASSWORD_FILE="$OUT.password"
if [ -e "$OUT" ] || [ -e "$PASSWORD_FILE" ]; then
  echo "error: $OUT or $PASSWORD_FILE already exists. Refusing to overwrite a signing key." >&2
  exit 1
fi

umask 077
mkdir -p "$(dirname "$OUT")"

# PKCS12 keystores use one password for the store and the key. 32 random alphanumeric characters.
PB_RELEASE_KEY_PASSWORD="$(openssl rand -base64 48 | LC_ALL=C tr -dc 'A-Za-z0-9' | cut -c 1-32)"
if [ "${#PB_RELEASE_KEY_PASSWORD}" -lt 32 ]; then
  echo "error: could not generate a password" >&2
  exit 1
fi
export PB_RELEASE_KEY_PASSWORD
printf '%s' "$PB_RELEASE_KEY_PASSWORD" > "$PASSWORD_FILE"

# Passwords are passed through the environment, so they never appear in the process list.
"$KEYTOOL" -genkeypair -noprompt \
  -keystore "$OUT" -storetype PKCS12 \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=DroidHands release" \
  -storepass:env PB_RELEASE_KEY_PASSWORD -keypass:env PB_RELEASE_KEY_PASSWORD >/dev/null
unset PB_RELEASE_KEY_PASSWORD
chmod 600 "$OUT" "$PASSWORD_FILE"

FINGERPRINT="$("$KEYTOOL" -list -v -keystore "$OUT" -storetype PKCS12 -alias "$ALIAS" \
  -storepass:file "$PASSWORD_FILE" 2>/dev/null | sed -n 's/^[[:space:]]*SHA256: //p' | head -n 1)"

cat <<EOF
Created the release key:
  keystore  $OUT
  password  $PASSWORD_FILE
  alias     $ALIAS
  SHA-256   ${FINGERPRINT:-(see keytool -list)}

Back up both files somewhere safe and offline. Without this key, no update can be installed over
a released DroidHands.

To let the release workflow sign with it, run these commands yourself (GitHub CLI, logged in with
access to $REPO). Nothing has been uploaded.

  base64 < "$OUT" | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64 --repo $REPO
  gh secret set ANDROID_KEYSTORE_PASSWORD --repo $REPO < "$PASSWORD_FILE"
  gh secret set ANDROID_KEY_PASSWORD --repo $REPO < "$PASSWORD_FILE"
  gh secret set ANDROID_KEY_ALIAS --repo $REPO --body "$ALIAS"

Optional, the relay new installs connect to on first run (see relay/README.md):

  gh variable set DROIDHANDS_RELAY_URL --repo $REPO --body "https://relay.example.com"

To sign a release build on this computer instead:

  export PB_KEYSTORE_PATH="$OUT" PB_KEY_ALIAS="$ALIAS"
  export PB_KEYSTORE_PASSWORD="\$(cat "$PASSWORD_FILE")" PB_KEY_PASSWORD="\$(cat "$PASSWORD_FILE")"
  (cd clients/android && ./gradlew :app:assembleRelease)
EOF
