#!/usr/bin/env bash
# Run your own DroidHands relay on this computer, reachable through Tailscale.
#
#   ./scripts/relay-tailscale.sh [--public] [--https-port N] [--port N] [--docker] [--access-key KEY]
#   ./scripts/relay-tailscale.sh --stop
#
# Starts the Node relay from relay/ (or its Docker image with --docker) on 127.0.0.1 only, with
# PUBLIC_URL=https://<this computer's MagicDNS name> and a persistent data directory, then publishes
# it with `tailscale serve` (only your tailnet can reach it) or, with --public, `tailscale funnel`
# (the whole internet: needed for cloud agents such as claude.ai and ChatGPT). Prints the relay's
# setup link and QR code for the phone. Safe to run again: it reuses or updates what it started, and
# never replaces a serve/funnel mapping it didn't create.
#
# Needs: the tailscale CLI (logged in, MagicDNS and HTTPS certificates on), curl, and Node.js >= 20
# with npm (or Docker with --docker). Works with macOS's bash 3.2 and Linux. NO_COLOR=1 disables colors.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RELAY_DIR="${DROIDHANDS_RELAY_SRC:-$REPO_ROOT/relay}"
readonly CONTAINER="droidhands-relay-tailscale"
readonly IMAGE="droidhands-relay"
readonly VOLUME="droidhands-relay-data"
readonly CONFIG_LABEL="com.droidhands.relay-config"

usage() {
  cat <<EOF
Usage: $(basename "$0") [options]
       $(basename "$0") --stop

Runs your own DroidHands relay on this computer and publishes it over Tailscale HTTPS.

  --public            Publish with Tailscale Funnel: anyone on the internet can reach the relay.
                      Needed for cloud agents (claude.ai, ChatGPT). Use an --access-key with it.
  --https-port N      Tailscale HTTPS port (default 443; Funnel allows 443, 8443 and 10000).
  --port N            Local port of the relay, on 127.0.0.1 (default 8787).
  --docker            Run the relay's Docker image instead of Node.js.
  --access-key KEY    Phones must present this key (RELAY_ACCESS_KEY; it goes into the setup link).
                      Default: \$RELAY_ACCESS_KEY, if set.
  --data-dir DIR      Where the relay keeps its secret, log and state (default ~/.droidhands-relay).
  --stop              Remove the Tailscale mapping this script made, and stop the relay.
  -h, --help          Show this help.

Without --public only devices on your tailnet reach the relay: your phone (with the Tailscale app)
and agents on your own computers (Claude Code, Codex, Claude Desktop). claude.ai and ChatGPT can't.

Environment: TAILSCALE (path to the tailscale CLI), RELAY_ACCESS_KEY, NO_COLOR.
EOF
}

# ---------------------------------------------------------------------------------------------- ui

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_GREEN=$'\033[32m' C_YELLOW=$'\033[33m' C_RED=$'\033[31m' C_BOLD=$'\033[1m' C_RESET=$'\033[0m'
else
  C_GREEN="" C_YELLOW="" C_RED="" C_BOLD="" C_RESET=""
fi

say() { printf '%s\n' "$*"; }
ok() { printf '%s✓%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%s!%s %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
die() {
  printf '%s✗ %s%s\n' "$C_RED" "$*" "$C_RESET" >&2
  exit 1
}

# ------------------------------------------------------------------------------------------- args

PUBLIC=0
HTTPS_PORT=""
PORT=8787
DOCKER=0
STOP=0
ACCESS_KEY="${RELAY_ACCESS_KEY:-}"
DATA_DIR="${DROIDHANDS_RELAY_DATA:-$HOME/.droidhands-relay}"

need_value() { [ $# -ge 2 ] && [ -n "$2" ] || die "$1 needs a value (see --help)"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --public) PUBLIC=1 ;;
    --https-port) need_value "$@"; HTTPS_PORT="$2"; shift ;;
    --https-port=*) HTTPS_PORT="${1#*=}" ;;
    --port) need_value "$@"; PORT="$2"; shift ;;
    --port=*) PORT="${1#*=}" ;;
    --docker) DOCKER=1 ;;
    --access-key) need_value "$@"; ACCESS_KEY="$2"; shift ;;
    --access-key=*) ACCESS_KEY="${1#*=}" ;;
    --data-dir) need_value "$@"; DATA_DIR="$2"; shift ;;
    --data-dir=*) DATA_DIR="${1#*=}" ;;
    --stop) STOP=1 ;;
    -h | --help) usage; exit 0 ;;
    *) die "Unknown option: $1 (see --help)" ;;
  esac
  shift
done

is_port() { case "$1" in '' | *[!0-9]*) return 1 ;; *) [ "$1" -ge 1 ] && [ "$1" -le 65535 ] ;; esac; }
is_port "$PORT" || die "--port must be a number between 1 and 65535, got '$PORT'"
[ -z "$HTTPS_PORT" ] || is_port "$HTTPS_PORT" || die "--https-port must be a number between 1 and 65535, got '$HTTPS_PORT'"

STATE_FILE="$DATA_DIR/relay-tailscale.state"
PID_FILE="$DATA_DIR/relay.pid"
LOG_FILE="$DATA_DIR/relay.log"

# ------------------------------------------------------------------------------------ JSON helper

# json_get JSON KEY... prints the value at that path: a string, number or boolean as is, the keys
# of an object one per line, nothing if absent. Keys are separate arguments (host names contain dots).
json_get() {
  local json="$1"
  shift
  if command -v node >/dev/null 2>&1; then
    printf '%s' "$json" | node -e '
      let v; try { v = JSON.parse(require("fs").readFileSync(0, "utf8")); } catch { process.exit(0); }
      for (const k of process.argv.slice(1)) { v = v !== null && typeof v === "object" ? v[k] : undefined; }
      if (v === undefined || v === null) process.exit(0);
      console.log(typeof v === "object" ? Object.keys(v).join("\n") : String(v));' "$@"
  elif command -v python3 >/dev/null 2>&1; then
    printf '%s' "$json" | python3 -c '
import json, sys
try:
    v = json.load(sys.stdin)
except ValueError:
    sys.exit(0)
for k in sys.argv[1:]:
    v = v.get(k) if isinstance(v, dict) else None
if v is None:
    sys.exit(0)
print("\n".join(v) if isinstance(v, dict) else (str(v).lower() if isinstance(v, bool) else v))' "$@"
  else
    die "Reading Tailscale's status needs node or python3."
  fi
}

# --------------------------------------------------------------------------------------- tailscale

find_tailscale() {
  if [ -n "${TAILSCALE:-}" ]; then
    printf '%s' "$TAILSCALE"
  elif command -v tailscale >/dev/null 2>&1; then
    command -v tailscale
  elif [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ]; then
    # The Mac App Store / standalone app ships the CLI inside the bundle.
    printf '%s' /Applications/Tailscale.app/Contents/MacOS/Tailscale
  fi
}

TS="$(find_tailscale || true)"
have_tailscale() { [ -n "$TS" ] && command -v "$TS" >/dev/null 2>&1; }
require_tailscale() {
  have_tailscale || die "The tailscale command isn't installed. Get Tailscale from https://tailscale.com/download and log in."
}
ts() { "$TS" "$@"; }

# Current serve config (JSON, "{}" when empty).
serve_json() { ts serve status --json 2>/dev/null || printf '{}'; }

# Who owns the HTTPS mapping on $HOST_PORT in serve config $1: "none", "ours" (proxies to our relay
# at the root and nothing else) or "other".
mapping_owner() {
  local json="$1" handlers proxy
  if [ -n "$(json_get "$json" TCP "$HTTPS_PORT" TCPForward)" ]; then
    printf 'other'
    return
  fi
  handlers="$(json_get "$json" Web "$HOST_PORT" Handlers)"
  if [ -z "$handlers" ]; then
    printf 'none'
    return
  fi
  proxy="$(json_get "$json" Web "$HOST_PORT" Handlers / Proxy)"
  if [ "$handlers" = "/" ] && is_our_proxy "$proxy"; then printf 'ours'; else printf 'other'; fi
}

is_our_proxy() {
  case "$1" in
    "http://127.0.0.1:$PORT" | "http://127.0.0.1:$PORT/" | "127.0.0.1:$PORT" | "http://localhost:$PORT" | "http://localhost:$PORT/") return 0 ;;
    *) return 1 ;;
  esac
}

# Removes the mapping on $HTTPS_PORT (it must be ours); a Funnel is switched off first.
unpublish() {
  local json="$1"
  if [ "$(json_get "$json" AllowFunnel "$HOST_PORT")" = "true" ]; then
    ts funnel --https="$HTTPS_PORT" off >/dev/null
  fi
  if [ "$(mapping_owner "$(serve_json)")" = "ours" ]; then
    ts serve --https="$HTTPS_PORT" off >/dev/null
  fi
}

# ----------------------------------------------------------------------------------------- relay

# Something answers DroidHands's /healthz on our local port.
relay_healthy() { curl -fsS --max-time 2 "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; }

wait_healthy() {
  local i
  for i in $(seq 1 50); do
    relay_healthy && return 0
    sleep 0.2
  done
  return 1
}

# PID of the Node relay this script started, if it is still running.
node_relay_pid() {
  local pid
  [ -f "$PID_FILE" ] || return 1
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  case "$pid" in '' | *[!0-9]*) return 1 ;; esac
  kill -0 "$pid" 2>/dev/null || return 1
  # A recycled PID belongs to something else.
  case "$(ps -p "$pid" -o command= 2>/dev/null)" in *dist/server.js*) printf '%s' "$pid" ;; *) return 1 ;; esac
}

stop_node_relay() {
  local pid i
  pid="$(node_relay_pid)" || {
    rm -f "$PID_FILE"
    return 0
  }
  kill "$pid" 2>/dev/null || true
  for i in $(seq 1 50); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 0.2
  done
  kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
}

docker_running() { [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || true)" = "true" ]; }

stop_docker_relay() {
  if docker inspect "$CONTAINER" >/dev/null 2>&1; then docker rm -f "$CONTAINER" >/dev/null; fi
}

# What the running relay was started with; a change means a restart. The key only as a checksum.
fingerprint() {
  local key_sum="none"
  [ -z "$ACCESS_KEY" ] || key_sum="$(printf '%s' "$ACCESS_KEY" | cksum | cut -d' ' -f1)"
  printf '%s|%s|%s' "$PUBLIC_URL" "$PORT" "$key_sum"
}

ensure_node_build() {
  command -v node >/dev/null 2>&1 || die "Node.js isn't installed (needs 20 or newer). Install it, or use --docker."
  command -v npm >/dev/null 2>&1 || die "npm isn't installed. Install Node.js 20 or newer, or use --docker."
  local major
  major="$(node -p 'process.versions.node.split(".")[0]')"
  [ "$major" -ge 20 ] || die "Node.js $major is too old: the relay needs 20 or newer."
  if [ ! -f "$RELAY_DIR/node_modules/.package-lock.json" ] || [ "$RELAY_DIR/package-lock.json" -nt "$RELAY_DIR/node_modules/.package-lock.json" ]; then
    say "Installing the relay's dependencies (npm ci)…"
    (cd "$RELAY_DIR" && npm ci --no-audit --no-fund >/dev/null)
  fi
  if [ ! -f "$RELAY_DIR/dist/server.js" ] || [ -n "$(find "$RELAY_DIR/src" -newer "$RELAY_DIR/dist/server.js" -print -quit)" ]; then
    say "Building the relay…"
    (cd "$RELAY_DIR" && npm run build >/dev/null)
  fi
}

# Filters the relay's output (stdin) down to its setup banner: the link and QR code it writes to
# stderr, without the JSON log lines.
print_banner() { grep -v '^{' || true; }

start_node_relay() {
  local fp="$1" pid offset
  if pid="$(node_relay_pid)"; then
    if [ "$(cat "$DATA_DIR/relay.fingerprint" 2>/dev/null || true)" = "$fp" ] && relay_healthy; then
      ok "The relay is already running (pid $pid)."
      cat "$LOG_FILE.banner" 2>/dev/null || true
      return
    fi
    say "Restarting the relay with the new settings…"
    stop_node_relay
  fi
  if relay_healthy; then die "Something else already answers on 127.0.0.1:$PORT. Stop it, or choose another --port."; fi
  ensure_node_build
  # The log and banner may contain the access key (in the setup link): owner-only.
  (umask 077 && : >>"$LOG_FILE" && : >>"$LOG_FILE.banner")
  offset=$(($(wc -c <"$LOG_FILE") + 1))
  (
    cd "$RELAY_DIR"
    export HOST=127.0.0.1 PORT="$PORT" PUBLIC_URL="$PUBLIC_URL" TRUST_PROXY=true DATA_DIR="$DATA_DIR" SETUP_BANNER=true
    if [ -n "$ACCESS_KEY" ]; then export RELAY_ACCESS_KEY="$ACCESS_KEY"; else unset RELAY_ACCESS_KEY; fi
    nohup node dist/server.js >>"$LOG_FILE" 2>&1 </dev/null &
    printf '%s\n' "$!" >"$PID_FILE"
  )
  printf '%s' "$fp" >"$DATA_DIR/relay.fingerprint"
  if ! wait_healthy; then
    tail -n 20 "$LOG_FILE" >&2 || true
    stop_node_relay
    die "The relay didn't start (log above, full log: $LOG_FILE)."
  fi
  sleep 0.3 # The banner is written right after the server starts listening.
  tail -c "+$offset" "$LOG_FILE" | print_banner >"$LOG_FILE.banner"
  ok "Relay started (pid $(cat "$PID_FILE")), log: $LOG_FILE"
  cat "$LOG_FILE.banner"
}

start_docker_relay() {
  local fp="$1"
  command -v docker >/dev/null 2>&1 || die "Docker isn't installed. Install it, or run without --docker (Node.js)."
  if docker_running && [ "$(docker inspect -f "{{index .Config.Labels \"$CONFIG_LABEL\"}}" "$CONTAINER" 2>/dev/null)" = "$fp" ] && relay_healthy; then
    ok "The relay container is already running ($CONTAINER)."
  else
    stop_docker_relay
    if relay_healthy; then die "Something else already answers on 127.0.0.1:$PORT. Stop it, or choose another --port."; fi
    say "Building the relay image (docker build)…"
    docker build -q -t "$IMAGE" "$RELAY_DIR" >/dev/null
    # The relay secret lives in a named volume, owned by the image's unprivileged user.
    local key_args=()
    [ -z "$ACCESS_KEY" ] || key_args=(-e RELAY_ACCESS_KEY)
    RELAY_ACCESS_KEY="$ACCESS_KEY" docker run -d --name "$CONTAINER" --restart unless-stopped \
      --label "$CONFIG_LABEL=$fp" \
      -p "127.0.0.1:$PORT:8787" -v "$VOLUME:/data" \
      -e PUBLIC_URL="$PUBLIC_URL" -e TRUST_PROXY=true ${key_args[@]+"${key_args[@]}"} \
      "$IMAGE" >/dev/null
    wait_healthy || {
      docker logs --tail 20 "$CONTAINER" >&2 || true
      die "The relay container didn't start (docker logs $CONTAINER)."
    }
    sleep 0.3
    ok "Relay container started ($CONTAINER)."
  fi
  docker logs "$CONTAINER" 2>&1 | print_banner
}

# ----------------------------------------------------------------------------------------- state

save_state() {
  printf 'mode=%s\nport=%s\nhttps_port=%s\nhost_port=%s\npublic=%s\n' \
    "$([ "$DOCKER" = 1 ] && echo docker || echo node)" "$PORT" "$HTTPS_PORT" "$HOST_PORT" "$PUBLIC" >"$STATE_FILE"
}

state_get() {
  [ -f "$STATE_FILE" ] || return 0
  sed -n "s/^$1=//p" "$STATE_FILE" | head -n 1
}

# ------------------------------------------------------------------------------------------- main

tailnet_name() {
  local status backend dns
  status="$(ts status --json 2>/dev/null)" || die "Tailscale isn't running on this computer. Start it and log in ('tailscale up')."
  backend="$(json_get "$status" BackendState)"
  case "$backend" in
    Running) ;;
    NeedsLogin | NoState | "") die "Tailscale isn't logged in. Run 'tailscale up' (or log in from the Tailscale app) and try again." ;;
    *) die "Tailscale is $backend. Connect it ('tailscale up') and try again." ;;
  esac
  dns="$(json_get "$status" Self DNSName)"
  dns="${dns%.}"
  [ -n "$dns" ] || die "This computer has no MagicDNS name. Turn on MagicDNS and HTTPS certificates in the Tailscale admin console (DNS page)."
  printf '%s' "$dns"
}

do_stop() {
  [ -n "$HTTPS_PORT" ] || HTTPS_PORT="$(state_get https_port)"
  [ -n "$HTTPS_PORT" ] || HTTPS_PORT=443
  local saved_port mode json owner
  saved_port="$(state_get port)"
  [ -z "$saved_port" ] || PORT="$saved_port"
  mode="$(state_get mode)"
  HOST_PORT="$(state_get host_port)"
  if ! have_tailscale; then
    warn "The tailscale command isn't installed, so no Tailscale mapping was removed."
  else
    [ -n "$HOST_PORT" ] || HOST_PORT="$(tailnet_name 2>/dev/null || true):$HTTPS_PORT"
    json="$(serve_json)"
    owner="$(mapping_owner "$json")"
    case "$owner" in
      ours)
        unpublish "$json"
        ok "Removed the Tailscale mapping https://$HOST_PORT."
        ;;
      other) warn "https://$HOST_PORT serves something else; left it alone." ;;
      none) say "No Tailscale mapping on https://$HOST_PORT." ;;
    esac
  fi
  if [ "$mode" = docker ] || { [ -z "$mode" ] && [ "$DOCKER" = 1 ]; }; then
    if command -v docker >/dev/null 2>&1; then stop_docker_relay; fi
  else
    stop_node_relay
  fi
  rm -f "$STATE_FILE"
  ok "Relay stopped. Its secret stays in $([ "$mode" = docker ] && echo "the Docker volume $VOLUME" || echo "$DATA_DIR"), so connected agents keep working next time."
}

mkdir -p "$DATA_DIR"
chmod 700 "$DATA_DIR"

if [ "$STOP" = 1 ]; then
  do_stop
  exit 0
fi

require_tailscale
command -v curl >/dev/null 2>&1 || die "curl isn't installed."
[ -n "$HTTPS_PORT" ] || HTTPS_PORT=443
if [ "$PUBLIC" = 1 ]; then
  case "$HTTPS_PORT" in 443 | 8443 | 10000) ;; *) die "Tailscale Funnel only serves ports 443, 8443 and 10000, not $HTTPS_PORT." ;; esac
fi

DNS_NAME="$(tailnet_name)"
HOST_PORT="$DNS_NAME:$HTTPS_PORT"
if [ "$HTTPS_PORT" = 443 ]; then PUBLIC_URL="https://$DNS_NAME"; else PUBLIC_URL="https://$DNS_NAME:$HTTPS_PORT"; fi
ok "Tailscale is connected: $DNS_NAME"

# Refuse before starting anything if the port is taken by a mapping that isn't ours.
SERVE="$(serve_json)"
OWNER="$(mapping_owner "$SERVE")"
[ "$OWNER" != "other" ] ||
  die "Tailscale already serves something else on https://$HOST_PORT. Remove it ('$TS serve --https=$HTTPS_PORT off') or pick another --https-port."

if [ "$PUBLIC" = 1 ] && [ -z "$ACCESS_KEY" ]; then
  warn "The relay will be public, and anyone can register a phone on it. Recommended: add"
  warn "  --access-key \"\$(openssl rand -hex 16)\"   (it goes into the setup link, so the phone gets it too)"
fi

FP="$(fingerprint)"
if [ "$DOCKER" = 1 ]; then
  stop_node_relay
  start_docker_relay "$FP"
else
  if command -v docker >/dev/null 2>&1; then stop_docker_relay; fi
  start_node_relay "$FP"
fi

# Publish. Switching between tailnet-only and public replaces our own mapping. Tailscale's own output
# stays visible: the first time it may ask to enable HTTPS or Funnel for the tailnet.
HAVE_FUNNEL="$(json_get "$SERVE" AllowFunnel "$HOST_PORT")"
WANT_FUNNEL="$([ "$PUBLIC" = 1 ] && echo true || echo false)"
if [ "$OWNER" = "ours" ] && [ "${HAVE_FUNNEL:-false}" != "$WANT_FUNNEL" ]; then
  unpublish "$SERVE"
fi
if [ "$PUBLIC" = 1 ]; then
  ts funnel --bg --https="$HTTPS_PORT" "http://127.0.0.1:$PORT" ||
    die "Tailscale couldn't publish the relay with Funnel (see above). Funnel must be allowed for this device in your tailnet's policy. The relay keeps running; stop it with: $0 --stop"
else
  ts serve --bg --https="$HTTPS_PORT" "http://127.0.0.1:$PORT" ||
    die "Tailscale couldn't publish the relay (see above). HTTPS certificates must be on for your tailnet. The relay keeps running; stop it with: $0 --stop"
fi
save_state

say ""
if [ "$PUBLIC" = 1 ]; then
  printf '%s%s is on the public internet%s (Tailscale Funnel): claude.ai, ChatGPT and every other agent can reach it.\n' \
    "$C_BOLD" "$PUBLIC_URL" "$C_RESET"
  [ -n "$ACCESS_KEY" ] || warn "No access key: anyone who finds the address can connect a phone to this relay."
else
  printf '%s%s is private to your tailnet%s (tailscale serve).\n' "$C_BOLD" "$PUBLIC_URL" "$C_RESET"
  say "Your phone needs the Tailscale app, signed in to the same tailnet. Agents on your own computers"
  say "(Claude Code, Codex, Claude Desktop) work; cloud agents (claude.ai, ChatGPT) need --public."
fi
say ""
say "Next: in DroidHands on the phone, tap \"Scan QR code\" (Your own relay) and scan the code above,"
say "or open the setup link there and tap \"Open in DroidHands\". Confirm \"Connect to relay?\", then copy"
say "the connection URL the app shows into your agent."
say "Stop with: $0 --stop"
