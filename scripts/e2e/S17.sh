#!/usr/bin/env bash
# S17 URL bar missing (SPEC §7.2, 1.0.4 fix)
# A browser's own screen without a URL bar (History, Settings …) stays allowed past the 10 s url_bar_missing clock,
# also when the browser is relaunched into it; a site drawn without a URL bar (web app / TWA) is still blocked as an
# unsupported browser.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin

step page
browser_go "example.org"
assert_allowed "$BROWSER" 2

step native-screen
launch "$BROWSER/.NativeScreenActivity"
assert_no_event "blocked(\"$BROWSER\") or (.ev==\"url_bar_missing\")" 12

step relaunch-native
go_home
launch "$BROWSER/.NativeScreenActivity"
assert_allowed "$BROWSER" 3

step app-mode
launch "$BROWSER/.AppModeActivity"
assert_event ".ev==\"url_bar_missing\" and .pkg==\"$BROWSER\"" 13
assert_event "blocked(\"$BROWSER\") and has_reason(\"UNSUPPORTED_BROWSER\")" 2
pass
