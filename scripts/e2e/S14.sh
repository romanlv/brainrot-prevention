#!/usr/bin/env bash
# S14 Notification hiding (SPEC §14.8)
# blockNotifications {T} all day; snooze chunk 5 s; listener connected; posted -> notif_hidden within 1 s; reset -> notification active again within 10 s.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin
ctl snooze-chunk --el ms 5000

step listener
# rebind so the connection event lands in this scenario's log
adb shell cmd notification disallow_listener "$LISTENER_SERVICE" >/dev/null 2>&1 || true
adb shell cmd notification allow_listener "$LISTENER_SERVICE" >/dev/null 2>&1 || true
assert_event '.ev=="service" and .name=="listener" and .state=="connected"' 10

step post
ashell am broadcast -n "$T_POST" --es title brp-s14 >/dev/null
assert_event ".ev==\"notif_hidden\" and .pkg==\"$T\"" 1

step reset-restores
ctl reset
active_has_target() {
  adb shell dumpsys notification --noredact | tr -d '\r' |
    awk -v p="pkg=$T" '/Notification List:/{f=1; next} /[Ss]nooze/{f=0} f && index($0, p){found=1} END{exit !found}'
}
d=$((SECONDS + 10))
until active_has_target; do
  [ "$SECONDS" -lt "$d" ] || fail_test "notification from $T not active again within 10 s after reset" 'dumpsys notification' 10
  sleep_s 0.5
done
pass
