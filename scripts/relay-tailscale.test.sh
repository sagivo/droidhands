#!/usr/bin/env bash
# Tests scripts/relay-tailscale.sh without Tailscale: a fake `tailscale` on PATH logs its arguments
# and keeps a serve config in a file, answering `status --json` and `serve status --json` like the
# real CLI. The relay itself is the real Node relay from relay/ (built if needed), started on a free
# local port, so PUBLIC_URL, /healthz and the OAuth metadata are checked for real.
#
#   ./scripts/relay-tailscale.test.sh                      # with the bash on PATH
#   PATH=/bin:/usr/bin:$(dirname "$(command -v node)") ./scripts/relay-tailscale.test.sh   # macOS bash 3.2
#   SHOW_OUTPUT=1 ./scripts/relay-tailscale.test.sh        # also print each run's output
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUT="$SCRIPT_DIR/relay-tailscale.sh"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/relay-tailscale-test.XXXXXX")"
DATA="$WORK/data"
PORT="$(node -e 'const s = require("net").createServer().listen(0, "127.0.0.1", () => { console.log(s.address().port); s.close(); })')"
HOST="my-mac.tail1234.ts.net"

cleanup() {
  "$SUT" --stop --data-dir "$DATA" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

PASSED=0
FAILED=0

# ------------------------------------------------------------------------------------- fake world

mkdir -p "$WORK/bin"
cat >"$WORK/bin/tailscale" <<'EOF'
#!/usr/bin/env bash
# Fake tailscale CLI: logs every call, keeps the serve config in $FAKE_STATE/serve.json.
set -euo pipefail
printf 'tailscale%s\n' "$(printf ' [%s]' "$@")" >>"$FAKE_LOG"
serve="$FAKE_STATE/serve.json"
[ -f "$serve" ] || printf '{}' >"$serve"
host="my-mac.tail1234.ts.net"
https_of() { for a in "$@"; do case "$a" in --https=*) printf '%s' "${a#--https=}" ;; esac; done; }
edit() { python3 - "$serve" "$@" <<'PY'
import json, sys
path, op, port, host = sys.argv[1:5]
target = sys.argv[5] if len(sys.argv) > 5 else None
c = json.load(open(path))
hp = f"{host}:{port}"
if op in ("serve", "funnel"):
    c.setdefault("TCP", {})[port] = {"HTTPS": True}
    c.setdefault("Web", {})[hp] = {"Handlers": {"/": {"Proxy": target}}}
    if op == "funnel":
        c.setdefault("AllowFunnel", {})[hp] = True
elif op == "funnel-off":
    c.get("AllowFunnel", {}).pop(hp, None)
elif op == "serve-off":
    c.get("TCP", {}).pop(port, None)
    c.get("Web", {}).pop(hp, None)
    c.get("AllowFunnel", {}).pop(hp, None)
for k in ("TCP", "Web", "AllowFunnel"):
    if k in c and not c[k]:
        del c[k]
json.dump(c, open(path, "w"))
PY
}
case "$*" in
  "status --json")
    cat <<JSON
{
  "Version": "1.78.1-t1234abcd",
  "BackendState": "${FAKE_BACKEND:-Running}",
  "TailscaleIPs": ["100.101.102.103", "fd7a:115c:a1e0::1"],
  "Self": {
    "ID": "nABCDEF1CNTRL",
    "HostName": "my-mac",
    "DNSName": "${FAKE_DNS-$host.}",
    "OS": "macOS",
    "TailscaleIPs": ["100.101.102.103", "fd7a:115c:a1e0::1"],
    "Online": true
  },
  "MagicDNSSuffix": "tail1234.ts.net",
  "CurrentTailnet": { "Name": "user@example.com", "MagicDNSSuffix": "tail1234.ts.net", "MagicDNSEnabled": true }
}
JSON
    ;;
  "serve status --json") cat "$serve" ;;
  serve*off) edit serve-off "$(https_of "$@")" "$host" ;;
  funnel*off) edit funnel-off "$(https_of "$@")" "$host" ;;
  serve\ --bg*) edit serve "$(https_of "$@")" "$host" "${!#}"; echo "Available within your tailnet: https://$host" ;;
  funnel\ --bg*) edit funnel "$(https_of "$@")" "$host" "${!#}"; echo "Available on the internet: https://$host" ;;
  *) echo "fake tailscale: unexpected $*" >&2; exit 2 ;;
esac
EOF
chmod +x "$WORK/bin/tailscale"

export PATH="$WORK/bin:$PATH"
export FAKE_LOG="$WORK/calls.log" FAKE_STATE="$WORK"
export NO_COLOR=1
unset TAILSCALE RELAY_ACCESS_KEY

# --------------------------------------------------------------------------------------- helpers

OUT="$WORK/out.txt"
STATUS=0

# run ARGS...: runs the script, output in $OUT, exit status in $STATUS, fresh call log.
run() {
  : >"$FAKE_LOG"
  set +e
  "$SUT" --data-dir "$DATA" --port "$PORT" "$@" >"$OUT" 2>&1
  STATUS=$?
  set -e
  if [ -n "${SHOW_OUTPUT:-}" ]; then
    printf -- '--- relay-tailscale.sh %s (exit %s)\n' "$*" "$STATUS"
    cat "$OUT"
    printf -- '--- tailscale calls\n'
    cat "$FAKE_LOG"
  fi
}

check() {
  local name="$1"
  shift
  if "$@"; then
    PASSED=$((PASSED + 1))
    printf 'ok   %s\n' "$name"
  else
    FAILED=$((FAILED + 1))
    printf 'FAIL %s\n' "$name"
    sed 's/^/     | /' "$OUT"
  fi
}

output_has() { grep -qF -- "$1" "$OUT"; }
called() { grep -qF -- "$1" "$FAKE_LOG"; }
not_called() { ! grep -qF -- "$1" "$FAKE_LOG"; }
exit_is() { [ "$STATUS" = "$1" ]; }
healthy() { curl -fsS --max-time 2 "http://127.0.0.1:$PORT/healthz" >/dev/null 2>&1; }
down() { ! healthy; }
issuer_is() {
  [ "$(curl -fsS "http://127.0.0.1:$PORT/.well-known/oauth-authorization-server" | node -e 'console.log(JSON.parse(require("fs").readFileSync(0, "utf8")).issuer)')" = "$1" ]
}
relay_pid() { cat "$DATA/relay.pid" 2>/dev/null; }

# ------------------------------------------------------------------------------------------ tests

check "syntax" bash -n "$SUT"
run --help
check "--help" exit_is 0
check "--help lists --public" output_has "--public"

FAKE_BACKEND=NeedsLogin run
check "logged out: refuses" exit_is 1
check "logged out: says how to log in" output_has "tailscale up"
check "logged out: no relay started" down

FAKE_DNS="" run
check "no MagicDNS name: refuses" exit_is 1
check "no MagicDNS name: says how to fix it" output_has "MagicDNS"

TAILSCALE="$WORK/missing" run
check "no tailscale CLI: refuses" exit_is 1
check "no tailscale CLI: says where to get it" output_has "tailscale.com/download"

run --public --https-port 1234
check "funnel port: refuses other ports" exit_is 1
check "funnel port: names the allowed ones" output_has "443, 8443 and 10000"

# An unrelated mapping on 443 must never be replaced.
printf '{"TCP":{"443":{"HTTPS":true}},"Web":{"%s:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:3000"}}}}}' "$HOST" >"$WORK/serve.json"
run
check "unrelated mapping: refuses" exit_is 1
check "unrelated mapping: explains" output_has "already serves something else"
check "unrelated mapping: no relay started" down
check "unrelated mapping: left alone" not_called "off"
rm -f "$WORK/serve.json"

run
check "tailnet: starts" exit_is 0
check "tailnet: relay is healthy" healthy
check "tailnet: PUBLIC_URL is the MagicDNS name" issuer_is "https://$HOST"
check "tailnet: tailscale serve on 443" called "tailscale [serve] [--bg] [--https=443] [http://127.0.0.1:$PORT]"
check "tailnet: no funnel" not_called "funnel"
check "tailnet: prints the setup link" output_has "https://$HOST/setup"
check "tailnet: prints the banner" output_has "scan the QR code"
check "tailnet: says it is private" output_has "private to your tailnet"
check "tailnet: relay secret persisted" test -s "$DATA/relay-secret"
check "tailnet: data dir is private" test "$(ls -ld "$DATA" | cut -c1-10)" = "drwx------"
FIRST_PID="$(relay_pid)"

run
check "again: succeeds" exit_is 0
check "again: reuses the running relay" output_has "already running"
check "again: same process" test "$(relay_pid)" = "$FIRST_PID"
check "again: still prints the setup link" output_has "https://$HOST/setup"

run --public
check "public without key: succeeds" exit_is 0
check "public without key: recommends an access key" output_has "--access-key"

run --public --access-key "s3cret key"
check "public: succeeds" exit_is 0
check "public: restarted for the new key" test "$(relay_pid)" != "$FIRST_PID"
check "public: funnel on 443" called "tailscale [funnel] [--bg] [--https=443] [http://127.0.0.1:$PORT]"
check "public: says it is public" output_has "public internet"
check "public: setup link carries the key" output_has "https://$HOST/setup#key=s3cret%20key"
check "public: still the same PUBLIC_URL" issuer_is "https://$HOST"

run --access-key "s3cret key"
check "back to tailnet: succeeds" exit_is 0
check "back to tailnet: funnel switched off first" called "tailscale [funnel] [--https=443] [off]"
check "back to tailnet: served again" called "tailscale [serve] [--bg] [--https=443] [http://127.0.0.1:$PORT]"
check "back to tailnet: funnel gone" test -z "$(grep -o AllowFunnel "$WORK/serve.json" || true)"

run --stop
check "stop: succeeds" exit_is 0
check "stop: removes the mapping" called "tailscale [serve] [--https=443] [off]"
check "stop: relay is down" down
check "stop: mapping gone" test "$(cat "$WORK/serve.json")" = "{}"

run --stop
check "stop again: succeeds" exit_is 0
check "stop again: nothing to remove" output_has "No Tailscale mapping"

run --https-port 8443
check "https port: succeeds" exit_is 0
check "https port: PUBLIC_URL has the port" issuer_is "https://$HOST:8443"
check "https port: serve on 8443" called "tailscale [serve] [--bg] [--https=8443] [http://127.0.0.1:$PORT]"
run --stop
check "https port: stop uses the saved port" called "tailscale [serve] [--https=8443] [off]"
check "https port: stopped" down

printf '\n%s passed, %s failed\n' "$PASSED" "$FAILED"
[ "$FAILED" = 0 ]
