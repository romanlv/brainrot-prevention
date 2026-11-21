#!/usr/bin/env bash
# S4 Day mask (SPEC §14.8)
# Weekdays 09:00-17:00; Sat 09:30 -> ALLOW.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin
step sat-0930
clock_set 2026-11-28T09:30:00+01:00
launch "$T_MAIN"
assert_allowed "$T" 2
pass
