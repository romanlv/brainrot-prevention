#!/usr/bin/env bash
# S12 Clock tamper (SPEC §14.8)
# TIMER 30 min, options 1; jump +1 d and -1 d keep it ACTIVE; advancing 30 min ends it. (S12b with a real clock change: --full only, not implemented.)
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin
step strict-start
ctl strict-start --es method TIMER --el ms 1800000 --ei options 1
assert_event '.ev=="strict" and .state=="ACTIVE"' 3

step jump-forward
clock_jump 86400000
assert_no_event '.ev=="strict" and .state=="INACTIVE"' 2

step jump-back
clock_jump -86400000
assert_no_event '.ev=="strict" and .state=="INACTIVE"' 2

step advance
clock_advance 1800001
assert_event '.ev=="strict" and .state=="INACTIVE"' 5
pass
