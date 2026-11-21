#!/usr/bin/env bash
# S2 Window starts while in the app (SPEC §14.8)
# Mon-Fri 09:00-10:00 {T}; 08:59:50 launch T -> ALLOW; advance 11 s -> alarm_fired WINDOW_START, then BLOCK without relaunch.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin
step before-window
clock_set 2026-11-23T08:59:50+01:00
launch "$T_MAIN"
assert_allowed "$T" 2

step window-start
clock_advance 11000
assert_event '.ev=="alarm_fired" and .kind=="WINDOW_START"' 1.5
assert_event "blocked(\"$T\")" 1.5
pass
