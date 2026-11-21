#!/usr/bin/env bash
# S7 Pause (SPEC §14.8)
# All-day schedule; pause 15 min from Home (driver) -> ALLOW; end-60 s+1 s -> PAUSE_END notice; past the end -> alarm_fired + BLOCK on launch.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin
NAME=Allday
step pause
launch "$MAIN_ACTIVITY" -f 0x10008000 # NEW_TASK|CLEAR_TASK: a fresh Home, no dialog left from a previous run
driver_tap "schedule_menu_$NAME"
driver_tap menu_pause
driver_tap pause_option_15m
go_home
launch "$T_MAIN"
assert_allowed "$T" 2

step pause-end-notice
go_home
clock_advance $((15 * 60000 - 60000 + 1000))
assert_event '.ev=="notif_posted" and .kind=="PAUSE_END"' 2

step pause-over
clock_advance 61000
assert_event '.ev=="alarm_fired" and .kind=="PAUSE_END"' 2
launch "$T_MAIN"
assert_event "blocked(\"$T\")" 1.5
pass
