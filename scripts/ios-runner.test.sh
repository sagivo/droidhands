#!/usr/bin/env bash
# Tests scripts/ios-runner.sh without Xcode: fake `xcodegen` and `xcodebuild` (XCODEGEN / XCODEBUILD) log their
# arguments and the TEST_RUNNER_* environment, so argument checks, signing settings and how the secret reaches the
# runner are checked without building anything.
#
#   ./scripts/ios-runner.test.sh
#   SHOW_OUTPUT=1 ./scripts/ios-runner.test.sh     # also print each run's output
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUT="$SCRIPT_DIR/ios-runner.sh"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/ios-runner-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

PASSED=0
FAILED=0

mkdir -p "$WORK/bin" "$WORK/runner"
printf 'name: DroidHandsRunner\n' >"$WORK/runner/project.yml"
cat >"$WORK/bin/xcodegen" <<'EOF'
#!/usr/bin/env bash
printf 'xcodegen%s\n' "$(printf ' [%s]' "$@")" >>"$FAKE_LOG"
EOF
cat >"$WORK/bin/xcodebuild" <<'EOF'
#!/usr/bin/env bash
printf 'xcodebuild%s\n' "$(printf ' [%s]' "$@")" >>"$FAKE_LOG"
printf 'env secret=[%s] port=[%s]\n' "${TEST_RUNNER_PB_RUNNER_SECRET:-}" "${TEST_RUNNER_PB_RUNNER_PORT:-}" >>"$FAKE_LOG"
EOF
chmod +x "$WORK/bin/xcodegen" "$WORK/bin/xcodebuild"

# run EXPECTED_STATUS ARGS...  (environment: SECRET, the runner's secret; empty for none)
run() {
  local expected="$1"
  shift
  : >"$WORK/log"
  local status=0
  OUTPUT="$(env -u PB_RUNNER_SECRET FAKE_LOG="$WORK/log" XCODEGEN="$WORK/bin/xcodegen" XCODEBUILD="$WORK/bin/xcodebuild" \
    DROIDHANDS_RUNNER_SRC="$WORK/runner" NO_COLOR=1 ${SECRET:+PB_RUNNER_SECRET="$SECRET"} \
    "$SUT" "$@" 2>&1)" || status=$?
  LOG="$(cat "$WORK/log")"
  if [ -n "${SHOW_OUTPUT:-}" ]; then printf '%s\n' "$OUTPUT"; fi
  if [ "$status" != "$expected" ]; then
    fail "exit status $status, expected $expected for: $*"$'\n'"$OUTPUT"
    return 1
  fi
}

pass() {
  PASSED=$((PASSED + 1))
  printf 'ok   %s\n' "$1"
}
fail() {
  FAILED=$((FAILED + 1))
  printf 'FAIL %s\n' "$1"
}
expect_in() { # NAME HAYSTACK NEEDLE
  case "$2" in
    *"$3"*) pass "$1" ;;
    *) fail "$1: missing '$3' in:"$'\n'"$2" ;;
  esac
}
expect_not_in() {
  case "$2" in
    *"$3"*) fail "$1: unexpected '$3' in:"$'\n'"$2" ;;
    *) pass "$1" ;;
  esac
}

SECRET=""
if run 1 --simulator SIM-1; then expect_in "refuses without PB_RUNNER_SECRET" "$OUTPUT" "PB_RUNNER_SECRET"; fi
SECRET="s3cret-value"
if run 1; then expect_in "needs --simulator or --device" "$OUTPUT" "--simulator UDID or --device UDID"; fi
if run 1 --device DEV-1; then expect_in "--device needs --team" "$OUTPUT" "--team"; fi
if run 1 --simulator SIM-1 --port 70000; then expect_in "rejects a bad port" "$OUTPUT" "--port"; fi
if run 1 --simulator SIM-1 --device DEV-1; then expect_in "rejects both targets" "$OUTPUT" "either"; fi
if run 1 --simulator SIM-1 --bogus; then expect_in "rejects unknown options" "$OUTPUT" "Unknown option: --bogus"; fi
if run 0 --help; then expect_in "prints usage" "$OUTPUT" "Usage:"; fi

if run 0 --simulator SIM-1 --port 9000; then
  expect_in "simulator: generates the project" "$LOG" "xcodegen [generate] [--spec] [$WORK/runner/project.yml]"
  expect_in "simulator: builds for testing" "$LOG" "xcodebuild [build-for-testing]"
  expect_in "simulator: destination" "$LOG" "[-destination] [platform=iOS Simulator,id=SIM-1]"
  expect_in "simulator: ad-hoc signing" "$LOG" "[CODE_SIGN_IDENTITY=-]"
  expect_in "simulator: default bundle id" "$LOG" "[PB_BUNDLE_ID_BASE=com.droidhands.runner]"
  expect_in "simulator: runs only the runner test" "$LOG" \
    "xcodebuild [test-without-building] [-project] [$WORK/runner/DroidHandsRunner.xcodeproj] [-scheme] [DroidHandsRunner]"
  expect_in "simulator: -only-testing" "$LOG" "[-only-testing:DroidHandsRunner/DroidHandsRunnerTests/testRunBridge]"
  expect_in "simulator: secret and port reach the runner" "$LOG" "env secret=[s3cret-value] port=[9000]"
  expect_not_in "simulator: secret is not an argument" "$(grep -v '^env ' <<<"$LOG")" "s3cret-value"
  expect_not_in "simulator: secret is not printed" "$OUTPUT" "s3cret-value"
fi

if run 0 --device DEV-1 --team ABCDE12345; then
  expect_in "device: destination" "$LOG" "[-destination] [platform=iOS,id=DEV-1]"
  expect_in "device: automatic signing with the team" "$LOG" "[CODE_SIGN_STYLE=Automatic] [DEVELOPMENT_TEAM=ABCDE12345]"
  expect_in "device: provisioning updates" "$LOG" "[-allowProvisioningUpdates]"
  expect_in "device: bundle id per team" "$LOG" "[PB_BUNDLE_ID_BASE=com.droidhands.runner.abcde12345]"
  expect_in "device: default port" "$LOG" "port=[8101]"
  expect_in "device: Developer Mode guidance" "$OUTPUT" "Developer Mode"
  expect_in "device: trust guidance" "$OUTPUT" "VPN & Device"
fi

printf '\n%d passed, %d failed\n' "$PASSED" "$FAILED"
[ "$FAILED" -eq 0 ]
