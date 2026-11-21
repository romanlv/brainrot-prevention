#!/usr/bin/env bash
# S11 Strict Mode (SPEC §14.8)
# Admin first; TIMER 10 min, options 7; delete refused (locked_by_strict); installer and Settings blocked; uninstall fails; timer end -> INACTIVE + admin removed.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
cleanup() {
  local rc=$?
  adb shell dpm remove-active-admin --user 0 "$ADMIN_RECEIVER" >/dev/null 2>&1 || true
  (exit $rc)
  _on_exit
}
scenario_begin
trap cleanup EXIT
NAME=Strictly
step admin
adb shell dpm set-active-admin --user 0 "$ADMIN_RECEIVER" | grep -qi success || fail_test "dpm set-active-admin failed"

step strict-start
ctl strict-start --es method TIMER --el ms 600000 --ei options 7
assert_event '.ev=="strict" and .state=="ACTIVE"' 3

step delete-refused
launch "$MAIN_ACTIVITY" -f 0x10008000 # NEW_TASK|CLEAR_TASK: a fresh Home, no dialog left from a previous run
driver_tap "schedule_menu_$NAME"
driver_tap menu_delete
driver_wait locked_by_strict 5000
go_home

step uninstall-guard
ashell am start -a android.intent.action.DELETE -d "package:$PKG" >/dev/null 2>&1 || true
assert_event '.ev=="decision" and .result=="BLOCK" and has_reason("STRICT_UNINSTALL")' 3
sleep_s 1
top=$(top_package)
case "$top" in *packageinstaller*) fail_test "the package installer is on top ($top)" ;; esac

step settings-guard
ashell am start -a android.settings.SETTINGS >/dev/null 2>&1 || true
assert_event '.ev=="decision" and .result=="BLOCK" and has_reason("STRICT_SETTINGS")' 3

step uninstall-refused
adb shell dumpsys device_policy | tr -d '\r' | grep -c "$PKG" >/dev/null || fail_test "device admin is not active"
out=$(adb shell pm uninstall --user 0 "$PKG" 2>&1 | tr -d '\r' || true)
printf '%s' "$out" | grep -q DELETE_FAILED || fail_test "pm uninstall did not fail: $out"

step timer-end
clock_advance 600001
assert_event '.ev=="strict" and .state=="INACTIVE"' 5
assert_event '(.ev=="admin" and (.state=="removed" or .admin=="removed")) or (.ev=="strict" and .admin=="removed")' 10
pass
