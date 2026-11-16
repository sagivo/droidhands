#!/usr/bin/env bash
# Validates DroidHands app plugins and runs their examples, with no phone needed (docs/PLUGINS.md §7, §10).
#
#   ./scripts/plugin-check.sh                  # every plugin in plugins/
#   ./scripts/plugin-check.sh plugins/myapp    # one plugin folder (or a plugin.yaml)
#
# Runs `./gradlew :core:checkPlugins` in clients/android. Needs a JDK 17+; the Android SDK is not needed.
# JAVA_HOME and ANDROID_HOME are only given defaults when they are unset. Exits 1 when a plugin has problems.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/clients/android"

usage() {
  cat <<EOF
Usage: $(basename "$0") [plugins/<id> | path/to/plugin.yaml]

Checks every plugin in plugins/ (or just the one given): format, security rules and examples.
EOF
}

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
esac
if [ "$#" -gt 1 ]; then
  usage >&2
  exit 2
fi

args=()
if [ "$#" -eq 1 ]; then
  target="$1"
  if [ ! -e "$target" ]; then
    echo "No such file or folder: $target" >&2
    exit 2
  fi
  # Absolute, so it means the same thing whatever directory Gradle runs in.
  if [ -d "$target" ]; then
    target="$(cd "$target" && pwd)"
  else
    target="$(cd "$(dirname "$target")" && pwd)/$(basename "$target")"
  fi
  args+=("-Ppath=$target")
fi

# Defaults only when unset (Homebrew's JDK 17 on macOS, Android Studio's SDK).
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk; do
    if [ -x "$candidate/bin/java" ]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in /opt/homebrew/share/android-commandlinetools "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -d "$candidate" ]; then
      export ANDROID_HOME="$candidate"
      break
    fi
  done
fi

cd "$ANDROID_DIR"
# --configure-on-demand: only the core module is configured, so no Android SDK is needed. The report goes to
# stdout; Gradle's own failure banner (stderr) is only shown when the checker didn't get to print a report.
errors="$(mktemp)"
trap 'rm -f "$errors"' EXIT
status=0
report="$(./gradlew --configure-on-demand --quiet --console=plain :core:checkPlugins ${args[@]+"${args[@]}"} 2>"$errors")" || status=$?
printf '%s\n' "$report"
if [ "$status" -ne 0 ]; then
  case "$report" in
    *"checked,"*) ;;
    *) cat "$errors" >&2 ;;
  esac
  exit 1
fi
