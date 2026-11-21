#!/usr/bin/env bash
# S8 Website blocklist (SPEC §14.8)
# DOMAIN example.com, KEYWORD game, KEYWORD games anywhere-in-URL; URL_REWRITE to the blank page; typing without submit is not judged.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin
web_blocked() { # web_blocked <url-substring>
  assert_event "blocked(\"$BROWSER\") and ((.url // \"\") | contains(\"$1\"))" 3
}
launch "$BROWSER_MAIN"

step go-m-example
browser_go https://m.example.com/x
web_blocked m.example.com
assert_event '.ev=="enforced" and .how=="URL_REWRITE"' 3
assert_event ".ev==\"page\" and ((.url // \"\") | startswith(\"content://$PKG.blank/\"))" 3

step go-other
browser_go https://other.org
assert_event '.ev=="page" and .url=="https://other.org/"' 3
assert_no_event "blocked(\"$BROWSER\")" 2

step go-epicgame
browser_go https://epicgame.test
web_blocked epicgame.test

step go-games-path
browser_go https://site.test/r/games
web_blocked site.test

step go-trailing-dot
browser_go https://example.com.
web_blocked example.com

step type-unsubmitted
browser_go https://other.org
assert_event '.ev=="page" and .url=="https://other.org/"' 3
step type-only
browser_type "best game deals"
assert_no_event "blocked(\"$BROWSER\")" 3
pass
