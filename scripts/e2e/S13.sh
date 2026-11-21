#!/usr/bin/env bash
# S13 Reboot (SPEC §14.8)
# All-day every-day {T}; BLOCK; adb reboot; boot + service connected within 90 s + alarm within 30 s; BLOCK on launch.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin
step before-reboot
launch "$T_MAIN"
assert_event "blocked(\"$T\")" 1.5

step reboot
stop_log
mv "$OUT/brp.jsonl" "$OUT/brp-before-reboot.jsonl"
mv "$OUT/logcat.txt" "$OUT/logcat-before-reboot.txt"
adb reboot
sleep 5
rc=0
wait_boot 240 || rc=$?
[ "$rc" = 0 ] || fail_infra "reboot: boot did not complete (wait_boot rc=$rc)"
start_log --no-clear
STEP=__all__ # [V2-N10]
assert_event '.ev=="boot"' 90
assert_event '.ev=="service" and .name=="a11y" and .state=="connected"' 90
assert_event '.ev=="alarm"' 30

step after-reboot
launch "$T_MAIN"
assert_event "blocked(\"$T\")" 1.5
pass
