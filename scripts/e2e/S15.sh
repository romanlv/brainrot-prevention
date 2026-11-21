#!/usr/bin/env bash
# S15 Unsupported browser (SPEC §14.8)
# Blocklist with a web rule + blockUnsupportedBrowsers; reinstall browser2 -> browsers unsupported contains it; launch -> BLOCK UNSUPPORTED_BROWSER; 'Not a browser? Allow' -> ALLOW.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin
step reinstall-browser2
adb uninstall "$BROWSER2" >/dev/null 2>&1 || true
adb install -r -t "$APK_BROWSER2" | grep -q Success || fail_infra "install browser2 failed"
assert_event ".ev==\"browsers\" and ((.unsupported // []) | index(\"$BROWSER2\") != null)" 5

step launch-browser2
launch "$BROWSER2_MAIN"
assert_event "blocked(\"$BROWSER2\") and has_reason(\"UNSUPPORTED_BROWSER\")" 1.5

step allow-browser
driver_tap block_allow_browser
go_home
launch "$BROWSER2_MAIN"
assert_allowed "$BROWSER2" 2
pass
