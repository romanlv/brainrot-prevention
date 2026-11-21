#!/usr/bin/env bash
# Shared helpers for every script (SPEC §14.1, §14.9). Source it; don't run it.
# bash 3.2 compatible: no associative arrays, no mapfile.
set -euo pipefail

_LIB_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(git -C "$_LIB_DIR" rev-parse --show-toplevel)
export ROOT
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
export ANDROID_SERIAL=emulator-5554
export PATH="$ROOT/scripts/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$JAVA_HOME/bin:$PATH"

# ---- identities -----------------------------------------------------------------------------
PKG=${PKG:-dev.brainrotprevention.debug}
QA=0
[ "$PKG" = dev.brainrotprevention.qa ] && QA=1
CTL_COMPONENT="$PKG/dev.brainrotprevention.debug.DebugControlReceiver"
CTL_ACTION=dev.brainrotprevention.debug.CONTROL
MAIN_ACTIVITY="$PKG/dev.brainrotprevention.MainActivity"
A11Y_SERVICE="$PKG/dev.brainrotprevention.platform.BlockerAccessibilityService"
LISTENER_SERVICE="$PKG/dev.brainrotprevention.platform.NotificationBlockerService"
ADMIN_RECEIVER="$PKG/dev.brainrotprevention.platform.StrictAdminReceiver"
T=dev.brainrotprevention.fixture.target
T_MAIN="$T/.MainActivity"
T_PIP="$T/.PipActivity"
T_POST="$T/.PostReceiver"
BROWSER=dev.brainrotprevention.fixture.browser
BROWSER_MAIN="$BROWSER/.MainActivity"
BROWSER2=dev.brainrotprevention.fixture.browser2
BROWSER2_MAIN="$BROWSER2/.MainActivity"
DRIVER=dev.brainrotprevention.e2edriver
DRIVER_RUNNER="$DRIVER/.DriverInstrumentation"
SEEDS="$ROOT/scripts/e2e/seeds"

# APK locations (built by verify.sh G3 / by hand)
APK_DEBUG="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
APK_QA="$ROOT/app/build/outputs/apk/qa/app-qa.apk"
APK_TEST="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
APK_TARGET="$ROOT/fixture-apps/target/build/outputs/apk/debug/target-debug.apk"
APK_BROWSER="$ROOT/fixture-apps/browser/build/outputs/apk/debug/browser-debug.apk"
APK_BROWSER2="$ROOT/fixture-apps/browser2/build/outputs/apk/debug/browser2-debug.apk"
APK_DRIVER="$ROOT/e2e-driver/build/outputs/apk/debug/e2e-driver-debug.apk"

# Output directory for logs and artefacts (run.sh sets OUT=build/verify/<Sx>)
SCENARIO=${SCENARIO:-$(basename "${0%.sh}")}
OUT=${OUT:-$ROOT/build/verify/$SCENARIO}
mkdir -p "$OUT"
STEP=${STEP:-}
LOGPID=
FULLPID=

# Exit codes understood by run.sh / verify.sh
EXIT_TEST=1
EXIT_BUILD=2
EXIT_INFRA=3
EXIT_TIMEOUT=142 # perl alarm (SIGALRM = 14, 128+14)
EXIT_REFUSED=97  # scripts/bin/adb refused the call

# ---- basics ---------------------------------------------------------------------------------
with_timeout() { perl -e 'alarm shift; exec @ARGV or die "exec failed: $!\n"' "$@"; }
now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time * 1000'; }
say() { echo "[$(date +%H:%M:%S)] ${SCENARIO}: $*" >&2; }
sleep_s() { perl -e 'select(undef, undef, undef, $ARGV[0])' "$1"; }

# Quote one argument for the device shell (adb shell joins argv with spaces).
shq() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }
# adb shell with every argument quoted for the remote sh
ashell() {
  local s="" a
  for a in "$@"; do s="$s $(shq "$a")"; done
  adb shell "$s"
}

# jq prelude available to every assert filter
JQ_DEFS='
def reason_sources: [(.reasons // [])[] | if type == "object" then (.source // "" | tostring) else tostring end];
def has_reason($s): reason_sources | any(. == $s or startswith($s + "(") or startswith($s + ":"));
def has_limit($l): [(.reasons // [])[] | objects | .limit] + [.limit] | index($l) != null;
def decision($t; $r): .ev == "decision" and .result == $r and .target == $t;
def blocked($t): decision($t; "BLOCK");
def allowed($t): decision($t; "ALLOW");
'

# ---- failure reporting ----------------------------------------------------------------------
_last_events_json() {
  { events_since_step 2>/dev/null || true; } | tail -n 20 | jq -R . | jq -sc . 2>/dev/null || echo '[]'
}

_capture_artefacts() {
  # logs first, then UI dump and screenshot (SPEC §14.4: dump only as a failure artefact)
  sleep_s 0.5
  stop_log
  ADB_TIMEOUT=20 adb shell uiautomator dump /sdcard/window.xml >/dev/null 2>&1 &&
    ADB_TIMEOUT=20 adb pull /sdcard/window.xml "$OUT/window.xml" >/dev/null 2>&1 || true
  ADB_TIMEOUT=20 adb exec-out screencap -p >"$OUT/fail.png" 2>/dev/null || rm -f "$OUT/fail.png"
}

# _write_result ok kind message [assert] [timeout_s]
_write_result() {
  local ok=$1 kind=$2 msg=$3 assert=${4:-} tmo=${5:-}
  local rel=${OUT#"$ROOT"/}
  local last='[]'
  [ "$ok" = true ] || last=$(_last_events_json)
  jq -n --argjson ok "$ok" --arg kind "$kind" --arg step "$STEP" --arg msg "$msg" \
    --arg assert "$assert" --arg tmo "$tmo" --argjson last "$last" --arg rel "$rel" '
    {ok: $ok}
    + (if $ok then {} else
        {kind: $kind, step: $step, message: $msg, last_events: $last,
         artefacts: {events: ($rel + "/brp.jsonl"), logcat: ($rel + "/logcat.txt"),
                     ui: ($rel + "/window.xml"), screenshot: ($rel + "/fail.png")}}
        + (if $assert == "" then {} else {assert: $assert} end)
        + (if $tmo == "" then {} else {timeout_s: ($tmo | tonumber)} end)
      end)' >"$OUT/result.json.tmp" && mv "$OUT/result.json.tmp" "$OUT/result.json"
}

_RESULT_WRITTEN=0
_fail() { # kind code message [assert] [timeout]
  local kind=$1 code=$2 msg=$3
  trap - ERR
  say "FAIL ($kind) at step '${STEP}': $msg"
  _capture_artefacts
  _write_result false "$kind" "$msg" "${4:-}" "${5:-}"
  _RESULT_WRITTEN=1
  exit "$code"
}
fail_test() { _fail test "$EXIT_TEST" "$@"; }
fail_infra() { _fail infra "$EXIT_INFRA" "$@"; }
fail_build() { _fail build "$EXIT_BUILD" "$@"; }

pass() {
  stop_log
  _write_result true "" ""
  _RESULT_WRITTEN=1
  say "PASS"
}

# Is the emulator still there? (used to classify unexpected command failures)
device_ok() { [ "$(ADB_TIMEOUT=10 adb get-state 2>/dev/null | tr -d '\r')" = device ]; }

_on_err() {
  local rc=$1 line=$2 cmd=$3
  trap - ERR
  if [ "$rc" = "$EXIT_TIMEOUT" ]; then _fail timeout "$EXIT_TIMEOUT" "command timed out (line $line): $cmd"; fi
  if [ "$rc" = "$EXIT_REFUSED" ] || ! device_ok; then _fail infra "$EXIT_INFRA" "device unavailable (rc=$rc, line $line): $cmd"; fi
  _fail test "$EXIT_TEST" "command failed (rc=$rc, line $line): $cmd"
}

_on_exit() {
  local rc=$?
  stop_log 2>/dev/null || true
  # A sourced scenario that ended without pass/fail: write a result so run.sh has a verdict.
  if [ "$_RESULT_WRITTEN" = 0 ] && [ "${LIB_NO_RESULT:-0}" != 1 ]; then
    if [ "$rc" = 0 ]; then _write_result true "" ""; else _write_result false test "exited with rc=$rc"; fi
  fi
}

set -E
trap '_on_err $? $LINENO "$BASH_COMMAND"' ERR
trap _on_exit EXIT

# ---- debug control channel ------------------------------------------------------------------
# ctl_raw <cmd> [am-broadcast extras...]: sets CTL_RES and CTL_DATA; never fails on result=0.
# Runs in the current shell (not in $(...)) so fail_* can end the script.
CTL_RES=
CTL_DATA=
ctl_raw() {
  local cmd=$1
  shift
  local out rc=0
  out=$(ashell am broadcast -f 0x20 -n "$CTL_COMPONENT" -a "$CTL_ACTION" --es cmd "$cmd" "$@" 2>&1) || rc=$?
  if [ "$rc" != 0 ]; then
    [ "$rc" = "$EXIT_TIMEOUT" ] && _fail timeout "$EXIT_TIMEOUT" "ctl $cmd timed out"
    device_ok || fail_infra "ctl $cmd: adb failed (rc=$rc): $out"
    fail_test "ctl $cmd: adb failed (rc=$rc): $out"
  fi
  # "Broadcast completed: result=1, data="ok""
  local line
  line=$(printf '%s\n' "$out" | grep 'Broadcast completed' | tail -n 1 || true)
  CTL_RES=$(printf '%s' "$line" | sed -n 's/.*result=\(-\{0,1\}[0-9]*\).*/\1/p')
  CTL_DATA=$(printf '%s' "$line" | sed -n 's/.*data="\(.*\)".*/\1/p')
  CTL_RES=${CTL_RES:-none}
  printf '%s ctl %s -> result=%s data=%s\n' "$(date +%H:%M:%S)" "$cmd" "$CTL_RES" "$CTL_DATA" >>"$OUT/ctl.log"
}

# ctl <cmd> [extras...]: fails the test unless the receiver acknowledged with result=1
ctl() {
  ctl_raw "$@"
  [ "$CTL_RES" = 1 ] || fail_test "ctl $1 not acknowledged (result=$CTL_RES, data='$CTL_DATA')"
}

# ctl_expect_refused <cmd> [extras...]: the command must answer result=0 (precondition failed)
ctl_expect_refused() {
  ctl_raw "$@"
  [ "$CTL_RES" = 0 ] || fail_test "ctl $1 expected result=0, got $CTL_RES ('$CTL_DATA')"
}

# ctl_import <file>: base64 for files up to 100 KB, else run-as + path
ctl_import() {
  local f=$1 size
  [ -f "$f" ] || fail_test "seed $f not found"
  jq -e . "$f" >/dev/null || fail_test "seed $f is not valid JSON"
  size=$(wc -c <"$f" | tr -d ' ')
  if [ "$size" -le 102400 ] || [ "$QA" = 1 ]; then
    ctl import --es b64 "$(base64 <"$f" | tr -d '\n')"
  else
    adb shell "run-as $PKG sh -c 'mkdir -p files/debug && cat > files/debug/seed.json'" <"$f" ||
      fail_test "could not copy $f into the app"
    ctl import --es path files/debug/seed.json
  fi
}

# read a file from the app's private dir (debuggable builds only)
app_cat() { ashell run-as "$PKG" cat "$1" | tr -d '\r' || fail_test "run-as $PKG cat $1 failed"; }

# ctl_state [out]: writes files/debug/state.json and copies it to $OUT/state.json (or [out])
ctl_state() {
  local dst=${1:-$OUT/state.json}
  ctl state
  [ "$QA" = 1 ] && return 0
  app_cat files/debug/state.json >"$dst"
  jq -e . "$dst" >/dev/null || fail_test "state.json is not valid JSON"
  cat "$dst"
}

# ctl_export <dst>: writes files/debug/export.json and copies it out
ctl_export() {
  local dst=$1
  ctl export
  app_cat files/debug/export.json >"$dst"
  jq -e . "$dst" >/dev/null || fail_test "export.json is not valid JSON"
}

# ---- apps, activities, input ---------------------------------------------------------------
launch() { # launch <pkg/.Activity> [am extras...]
  local comp=$1
  shift
  ashell am start -W -n "$comp" "$@" >/dev/null 2>&1 || fail_test "am start $comp failed"
}
go_home() { adb shell input keyevent KEYCODE_HOME >/dev/null; }
browser_go() { ashell am start -n "$BROWSER_MAIN" --es go "$1" >/dev/null 2>&1 || fail_test "browser_go $1 failed"; }
browser_type() { ashell am start -n "$BROWSER_MAIN" --es type "$1" >/dev/null 2>&1 || fail_test "browser_type failed"; }
launcher_package() {
  adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME |
    tr -d '\r' | tail -n 1 | cut -d/ -f1 || true
}
top_package() {
  adb shell dumpsys activity activities | tr -d '\r' |
    grep -E 'topResumedActivity=|mResumedActivity:|ResumedActivity:' | head -n 1 |
    sed -E 's/.* u[0-9]+ ([^/ ]+)\/.*/\1/' || true
}
installed() { adb shell pm path "$1" 2>/dev/null | grep -q '^package:'; }

# ---- e2e-driver (UiAutomator through am instrument) ----------------------------------------
# driver <cmd> <res> [timeoutMs] [text]; prints the instrumentation output; returns 0 on success
driver() {
  local cmd=$1 res=$2 tmo=${3:-10000} text=${4:-}
  local extra=()
  [ -n "$text" ] && extra=(-e text "$text")
  local out rc=0
  out=$(ADB_TIMEOUT=$((tmo / 1000 + 20)) ashell am instrument -w -e cmd "$cmd" -e res "$res" -e timeout "$tmo" \
    ${extra[@]+"${extra[@]}"} "$DRIVER_RUNNER" 2>&1) || rc=$?
  printf '%s\n' "$out" >>"$OUT/driver.log"
  [ "$rc" = "$EXIT_TIMEOUT" ] && _fail timeout "$EXIT_TIMEOUT" "driver $cmd $res timed out"
  [ "$rc" = 0 ] || { device_ok || fail_infra "driver: adb failed (rc=$rc)"; }
  if printf '%s' "$out" | grep -q 'INSTRUMENTATION_FAILED'; then return 1; fi
  printf '%s' "$out" | grep -q 'INSTRUMENTATION_RESULT: result=OK' &&
    printf '%s' "$out" | grep -q 'INSTRUMENTATION_CODE: -1'
}
driver_tap() { driver tap "$1" "${2:-10000}" || fail_test "driver_tap $1 failed (see $OUT/driver.log)"; }
driver_wait() { driver wait "$1" "${2:-10000}" || fail_test "driver_wait $1: not found (see $OUT/driver.log)"; }
driver_text() { driver text "$1" "${3:-10000}" "$2" || fail_test "driver_text $1 failed (see $OUT/driver.log)"; }

# ---- log collection (SPEC §14.9) -----------------------------------------------------------
stop_log() {
  # shellcheck disable=SC2086
  [ -n "${LOGPID:-}${FULLPID:-}" ] && kill ${LOGPID:-} ${FULLPID:-} 2>/dev/null || true
  LOGPID=
  FULLPID=
  rm -f "$OUT/.collectors"
}
start_log() { # start_log [--no-clear]
  stop_log
  [ "${1:-}" = --no-clear ] || adb logcat -c
  adb logcat -v raw -s BRP:V BRPFIX:V >>"$OUT/brp.jsonl" 2>/dev/null &
  LOGPID=$!
  disown "$LOGPID" 2>/dev/null || true
  adb logcat -v epoch >>"$OUT/logcat.txt" 2>/dev/null &
  FULLPID=$!
  disown "$FULLPID" 2>/dev/null || true
  echo "$LOGPID $FULLPID" >"$OUT/.collectors" # run.sh kills these if the scenario is killed
}
step() {
  STEP=$1
  ctl mark --es id "$1"
  # wait until the mark line reached our collector, so later asserts see a clean window
  local d=$(($(now_ms) + 5000))
  while [ "$(now_ms)" -lt "$d" ]; do
    grep -q "\"ev\":\"mark\",\"id\":\"$STEP\"" "$OUT/brp.jsonl" 2>/dev/null && return 0
    sleep_s 0.1
  done
  fail_infra "mark '$STEP' was acknowledged but never reached the log collector"
}
events_since_step() {
  if [ "$STEP" = __all__ ]; then
    cat "$OUT/brp.jsonl"
  else
    sed -n "/\"ev\":\"mark\",\"id\":\"$STEP\"/,\$p" "$OUT/brp.jsonl"
  fi
}
# matching events since the step, compact JSON, one per line
events_matching() { events_since_step | jq -cR "$JQ_DEFS fromjson? | select($1)" 2>/dev/null || true; }

# assert_event <jq-filter> <seconds (may be fractional)>
assert_event() {
  local d=$(($(now_ms) + $(awk "BEGIN{printf \"%d\", $2 * 1000}")))
  while [ "$(now_ms)" -lt "$d" ]; do
    events_matching "$1" | grep -q . && return 0
    sleep_s 0.2
  done
  events_matching "$1" | grep -q . && return 0
  fail_test "$1 within $2 s" "$1" "$2"
}
# assert_no_event <jq-filter> <seconds>: waits the full window, then checks
assert_no_event() {
  sleep_s "$2"
  if events_matching "$1" | grep -q .; then
    fail_test "unexpected event: $(events_matching "$1" | head -n 1)" "not($1)" "$2"
  fi
}
# last matching event (JSON) or empty
last_event() { events_matching "$1" | tail -n 1; }

# ---- common assertions ----------------------------------------------------------------------
# assert_allowed <pkg> [seconds]: no BLOCK decision for pkg and it stays on top
assert_allowed() {
  local p=$1 s=${2:-2}
  assert_no_event "blocked(\"$p\")" "$s"
  local top
  top=$(top_package)
  [ "$top" = "$p" ] || fail_test "expected $p on top (allowed), top is '$top'"
}
assert_top_is_launcher() {
  local l top
  l=$(launcher_package)
  top=$(top_package)
  [ "$top" = "$l" ] || fail_test "expected the launcher ($l) on top, top is '$top'"
}

# ---- emulator boot wait (shared by emu.sh and S13) -----------------------------------------
qemu_alive() { pgrep -f -- '-avd brp37' >/dev/null 2>&1; }
# wait_boot <seconds>: returns 0 when booted; 1 if the qemu process died; 2 on timeout
wait_boot() {
  local d=$((SECONDS + $1))
  while [ "$SECONDS" -lt "$d" ]; do
    qemu_alive || return 1
    if [ "$(ADB_TIMEOUT=5 adb get-state 2>/dev/null | tr -d '\r')" = device ] &&
      [ "$(ADB_TIMEOUT=5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] &&
      ADB_TIMEOUT=10 adb shell pm path android 2>/dev/null | grep -q '^package:' &&
      ADB_TIMEOUT=10 adb shell settings get global device_provisioned >/dev/null 2>&1 &&
      [ "$(ADB_TIMEOUT=5 adb shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')" != running ]; then
      ADB_TIMEOUT=10 adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
      ADB_TIMEOUT=10 adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
      return 0
    fi
    sleep 2
  done
  return 2
}

# ---- scenario scaffolding -------------------------------------------------------------------
# scenario_begin [seed-file]: new log collector, ctl reset, seed (SPEC §14.8, §15 item 8)
scenario_begin() {
  local seed=${1:-$SEEDS/$SCENARIO.json}
  : >"$OUT/brp.jsonl"
  : >"$OUT/logcat.txt"
  rm -f "$OUT/result.json" "$OUT/window.xml" "$OUT/fail.png"
  device_ok || fail_infra "emulator-5554 is not online"
  installed "$PKG" || fail_infra "$PKG is not installed (run scripts/e2e/grant.sh)"
  start_log
  step setup
  ctl reset
  [ -f "$seed" ] && ctl_import "$seed"
  return 0
}

# clock helpers (all ISO times carry a zone, SPEC §14.5)
clock_set() { ctl clock-set --es at "$1"; }
clock_advance() { ctl clock-advance --el ms "$1"; }
clock_jump() { ctl clock-jump --el ms "$1"; }

# S1's assertions, shared with S16
quick_block_assertions() {
  step qb-launch
  launch "$T_MAIN"
  assert_event "blocked(\"$T\") and has_reason(\"QUICK_BLOCK\")" 1.5
  assert_event ".ev==\"enforced\" and .how==\"HOME\"" 1.5
  assert_event ".ev==\"overlay\" and .state==\"shown\"" 1.5

  step qb-close
  driver_tap block_close
  sleep_s 1
  assert_top_is_launcher

  step qb-relaunch
  launch "$T_MAIN"
  assert_event "blocked(\"$T\")" 1.5

  step qb-pip
  go_home
  driver tap block_close 2000 >/dev/null 2>&1 || true
  launch "$T_PIP" --ez enterPip true
  assert_event "blocked(\"$T\")" 3
  sleep_s 2
  if ! events_matching '.ev=="overlay" and .state=="shown"' | grep -q .; then
    if adb shell dumpsys activity activities | tr -d '\r' | grep -ci 'windowingMode=pinned\|mode=pinned' >/dev/null; then
      fail_test "PiP window still visible and not covered by the overlay" '.ev=="overlay" and .state=="shown"' 2
    fi
  fi
}
