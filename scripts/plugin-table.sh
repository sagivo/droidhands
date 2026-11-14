#!/usr/bin/env bash
# Regenerates the table of bundled plugins in plugins/README.md from plugins/*/plugin.yaml (docs/PLUGINS.md).
#
#   ./scripts/plugin-table.sh           # rewrite the table
#   ./scripts/plugin-table.sh --check   # only check it: exits 1 when it is out of date
#                                       # (CI checks it too, as part of ./gradlew :core:test)
#
# Runs `./gradlew :core:pluginTable` in clients/android. Needs a JDK 17+; the Android SDK is not needed.
# JAVA_HOME is only given a default when it is unset.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/clients/android"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--check]

Updates the plugin table in plugins/README.md; --check only reports whether it is up to date.
EOF
}

args=()
case "${1:-}" in
  -h|--help) usage; exit 0 ;;
  --check) args+=("-Pcheck=true") ;;
  "") ;;
  *) usage >&2; exit 2 ;;
esac
if [ "$#" -gt 1 ]; then
  usage >&2
  exit 2
fi

# Defaults only when unset (Homebrew's JDK 17 on macOS, common Linux paths).
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk; do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

cd "$ANDROID_DIR"
# --configure-on-demand: only the core module is configured, so no Android SDK is needed. Gradle's own
# failure banner is left out when the generator printed why it failed.
errors="$(mktemp)"
trap 'rm -f "$errors"' EXIT
status=0
./gradlew --configure-on-demand --quiet --console=plain :core:pluginTable ${args[@]+"${args[@]}"} 2>"$errors" || status=$?
if [ "$status" -ne 0 ]; then
  if grep -q -e "plugins/README.md" -e "Fix the plugins first" "$errors"; then
    sed -e '/^FAILURE: /,$d' -e '/^[[:space:]]*$/d' "$errors" >&2
  else
    cat "$errors" >&2
  fi
  exit 1
fi
