#!/usr/bin/env bash
# S5 Time budget (SPEC §14.8)
# 5 min/day on T, limitWarningMin=1, Mon 10:00; 3x60 s -> used>=180000, no warning; +60 s -> LIMIT_WARNING; +60 s -> BLOCK TIME; Tue 00:01 -> ALLOW.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin
step mon-1000
clock_set 2026-11-23T10:00:00+01:00
launch "$T_MAIN"
assert_allowed "$T" 1

step three-minutes
clock_advance 60000
clock_advance 60000
clock_advance 60000
assert_event '.ev=="usage" and (.used // 0) >= 180000' 2
assert_no_event '.ev=="notif_posted" and .kind=="LIMIT_WARNING"' 1

step after-4th-advance
clock_advance 60000
assert_event '.ev=="notif_posted" and .kind=="LIMIT_WARNING"' 2

step after-5th-advance
clock_advance 60000
assert_event "blocked(\"$T\") and has_limit(\"TIME\")" 2

step tue-0001
clock_set 2026-11-24T00:01:00+01:00
go_home
launch "$T_MAIN"
assert_allowed "$T" 2
pass
