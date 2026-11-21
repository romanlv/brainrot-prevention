#!/usr/bin/env bash
# S10 Wi-Fi trigger (SPEC §14.8)
# SSID AndroidWifi; wifi on -> sensor ssid within 45 s -> BLOCK on launch; wifi off -> ssid null within 20 s -> ALLOW.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
wifi_off() { adb shell svc wifi disable >/dev/null 2>&1 || true; }
scenario_begin
trap 'rc=$?; wifi_off; (exit $rc); _on_exit' EXIT
ctl sensor-real

step wifi-on
adb shell svc wifi enable
assert_event '.ev=="sensor" and .ssid=="AndroidWifi"' 45
launch "$T_MAIN"
assert_event "blocked(\"$T\")" 1.5

step wifi-off
go_home
driver tap block_close 2000 >/dev/null 2>&1 || true
adb shell svc wifi disable
assert_event '.ev=="sensor" and has("ssid") and .ssid==null' 20
launch "$T_MAIN"
assert_allowed "$T" 2
pass
