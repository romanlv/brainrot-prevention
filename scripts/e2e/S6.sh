#!/usr/bin/env bash
# S6 Launch budget (SPEC §14.8)
# 3 launches/day; three launches allowed (the 3rd held 5 s), the 4th -> BLOCK LAUNCHES; relaunch within 30 s counts once.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin
clock_set 2026-11-23T10:00:00+01:00
for i in 1 2 3; do
  step "launch-$i"
  launch "$T_MAIN"
  if [ "$i" = 3 ]; then
    # the running session must not be ended by the LAUNCHES limit
    assert_no_event "blocked(\"$T\")" 5
    top=$(top_package)
    [ "$top" = "$T" ] || fail_test "3rd launch: expected $T on top, got '$top'"
  else
    assert_allowed "$T" 1.5
  fi
  go_home
  clock_advance 31000
done

step launch-4
launch "$T_MAIN"
assert_event "blocked(\"$T\") and has_limit(\"LAUNCHES\")" 1.5

step relaunch-within-30s
go_home
driver tap block_close 2000 >/dev/null 2>&1 || true
ctl reset
ctl_import "$SEEDS/S6.json"
clock_set 2026-11-23T10:00:00+01:00
launch "$T_MAIN"
go_home
clock_advance 10000
launch "$T_MAIN"
# a late second count must not slip in: no used>=2 for a few seconds, then the last usage is used==1
assert_no_event '.ev=="usage" and .used>=2' 4
last=$(last_event '.ev=="usage"')
[ -n "$last" ] || fail_test "no usage event after two launches" '.ev=="usage"' 1.5
[ "$(printf '%s' "$last" | jq '.used')" = 1 ] || fail_test "expected usage used==1, got: $last" '.ev=="usage" and .used==1' 1.5
pass
