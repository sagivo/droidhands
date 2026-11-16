#!/usr/bin/env bash
# Starts PhoneBridge for an iPhone or simulator (docs/IOS.md): the on-phone runner, then the host on this Mac.
#
#   ./scripts/ios-host.sh (--simulator [<udid>] | --device <udid>) [--relay URL] [--local-port N]
#
# Generates a fresh random runner secret (PB_RUNNER_SECRET) for this session, starts
# scripts/ios-runner.sh with the same target in the background, waits until the runner answers
# GET /status with that secret, then builds and runs the host (:ios-host:installDist). Stopping the
# host (Ctrl-C) stops the runner too. Without --relay or --local-port, agents on this Mac connect at
# http://127.0.0.1:8765/mcp. --simulator without a udid uses the booted simulator.
#
# Environment: PB_RUNNER_PORT (default 8101), PB_RUNNER_URL (default http://127.0.0.1:$PB_RUNNER_PORT),
# PB_RUNNER_WAIT (seconds to wait for the runner, default 600: the first build takes a while),
# PB_RELAY_ACCESS_KEY (the relay's access key, if it needs one), JAVA_HOME.
#
# Works with macOS's bash 3.2.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/clients/android"
HOST_BIN="$REPO_ROOT/clients/ios/host/build/install/phonebridge-ios-host/bin/phonebridge-ios-host"

usage() {
  sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
}

die() {
  echo "ios-host: $*" >&2
  exit 1
}

target=""
udid=""
relay=""
local_port=""
while [ $# -gt 0 ]; do
  case "$1" in
    --simulator)
      target="--simulator"
      if [ $# -gt 1 ] && [ "${2#--}" = "$2" ]; then udid="$2"; shift; fi
      ;;
    --device)
      target="--device"
      [ $# -gt 1 ] || die "--device needs the iPhone's udid (xcrun devicectl list devices)"
      udid="$2"
      shift
      ;;
    --relay)
      [ $# -gt 1 ] || die "--relay needs a URL"
      relay="$2"
      shift
      ;;
    --local-port)
      [ $# -gt 1 ] || die "--local-port needs a port"
      local_port="$2"
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown option '$1'"
      ;;
  esac
  shift
done

[ -n "$target" ] || { usage >&2; die "pass --simulator [<udid>] or --device <udid>"; }
if [ -z "$udid" ]; then
  udid="$(xcrun simctl list devices booted 2>/dev/null | grep -Eo '[0-9A-F]{8}-([0-9A-F]{4}-){3}[0-9A-F]{12}' | head -n 1 || true)"
  [ -n "$udid" ] || die "no booted simulator: boot one in Xcode (or xcrun simctl boot <udid>), or pass its udid"
fi
if [ -z "$relay" ] && [ -z "$local_port" ]; then local_port=8765; fi

runner_port="${PB_RUNNER_PORT:-8101}"
runner_url="${PB_RUNNER_URL:-http://127.0.0.1:$runner_port}"
wait_seconds="${PB_RUNNER_WAIT:-600}"

# A new secret per session: only this host can drive the runner.
if command -v openssl >/dev/null 2>&1; then
  PB_RUNNER_SECRET="$(openssl rand -hex 32)"
else
  PB_RUNNER_SECRET="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 64)"
fi
export PB_RUNNER_SECRET

runner_log="$(mktemp -t phonebridge-runner)"
runner_pid=""

cleanup() {
  if [ -n "$runner_pid" ] && kill -0 "$runner_pid" 2>/dev/null; then
    echo "ios-host: stopping the runner" >&2
    pkill -TERM -P "$runner_pid" 2>/dev/null || true
    kill -TERM "$runner_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

echo "ios-host: starting the runner on $target $udid (log: $runner_log)" >&2
"$SCRIPT_DIR/ios-runner.sh" "$target" "$udid" --port "$runner_port" >"$runner_log" 2>&1 &
runner_pid=$!

# Build the host while the runner builds.
echo "ios-host: building the host" >&2
"$ANDROID_DIR/gradlew" -p "$ANDROID_DIR" -q :ios-host:installDist || die "the host didn't build"

echo "ios-host: waiting for the runner at $runner_url" >&2
waited=0
until curl -fsS -m 3 -H "Authorization: Bearer $PB_RUNNER_SECRET" "$runner_url/status" >/dev/null 2>&1; do
  if ! kill -0 "$runner_pid" 2>/dev/null; then
    tail -n 30 "$runner_log" >&2 || true
    die "the runner stopped before it answered (log above, full log: $runner_log)"
  fi
  if [ "$waited" -ge "$wait_seconds" ]; then
    tail -n 30 "$runner_log" >&2 || true
    die "the runner didn't answer within $wait_seconds s (full log: $runner_log)"
  fi
  sleep 2
  waited=$((waited + 2))
done
echo "ios-host: runner is up" >&2

args=("$target" "$udid" --runner-url "$runner_url")
if [ -n "$relay" ]; then args+=(--relay "$relay"); fi
if [ -n "$local_port" ]; then args+=(--local-port "$local_port"); fi
"$HOST_BIN" "${args[@]}"
