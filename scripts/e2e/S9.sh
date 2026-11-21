#!/usr/bin/env bash
# S9 Allowlist web (SPEC §14.8)
# ALLOWLIST {browser}, ONLY_LISTED {wikipedia.org}; T blocked; wikipedia allowed; example.com blocked once, no loop on the redirect page.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin
step launch-target
launch "$T_MAIN"
assert_event "blocked(\"$T\")" 1.5

step wikipedia
go_home
driver tap block_close 2000 >/dev/null 2>&1 || true
launch "$BROWSER_MAIN"
browser_go https://en.wikipedia.org/wiki/X
assert_event '.ev=="page" and .url=="https://en.wikipedia.org/wiki/X"' 3
assert_no_event "blocked(\"$BROWSER\")" 2

step example
browser_go https://example.com
assert_event "blocked(\"$BROWSER\") and ((.url // \"\") | contains(\"example.com\"))" 3
assert_event ".ev==\"page\" and ((.url // \"\") | startswith(\"content://$PKG.blank/\"))" 3
step after-redirect
assert_no_event "blocked(\"$BROWSER\")" 3
pass
