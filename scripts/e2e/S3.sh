#!/usr/bin/env bash
# S3 Overnight window (SPEC §14.8)
# Fri 22:00-06:00; Sat 05:59 -> BLOCK (previous-day rule); +120 s -> ALLOW on launch.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin
step sat-0559
clock_set 2026-11-28T05:59:00+01:00
launch "$T_MAIN"
assert_event "blocked(\"$T\")" 1.5

step sat-0601
clock_advance 120000
go_home
launch "$T_MAIN"
assert_allowed "$T" 2
pass
