#!/usr/bin/env bash
# S16 Backup round trip (SPEC §14.8)
# import seed -> export a -> reset -> import a -> export b; a == b without exportedAt; then qb-start and S1's assertions.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin
step export-a
ctl_export "$OUT/a.json"
step reimport
ctl reset
ctl_import "$OUT/a.json"
step export-b
ctl_export "$OUT/b.json"
if ! diff <(jq -S 'del(.exportedAt)' "$OUT/a.json") <(jq -S 'del(.exportedAt)' "$OUT/b.json") >"$OUT/roundtrip.diff"; then
  fail_test "export after re-import differs (see $OUT/roundtrip.diff)"
fi
# the seed's content must survive too (names, items, prefs)
[ "$(jq -c '[.schedules[].name] | sort' "$OUT/a.json")" = "$(jq -c '[.schedules[].name] | sort' "$SEEDS/S16.json")" ] ||
  fail_test "exported schedule names differ from the seed"

step qb-start
ctl qb-start
quick_block_assertions
pass
