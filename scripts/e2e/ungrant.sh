#!/usr/bin/env bash
# Reverse grant.sh without uninstalling anything (SPEC §14.6), for the no-grant G3 class.
set -euo pipefail
OUT=${OUT:-$(git -C "$(dirname "$0")" rev-parse --show-toplevel)/build/verify/ungrant}
source "$(dirname "$0")/lib.sh"
API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
try() { if ! out=$("$@" 2>&1); then say "warn: '$*' failed: $(printf '%s' "$out" | head -n 1)"; fi; }

try adb shell settings delete secure enabled_accessibility_services
try adb shell settings put secure accessibility_enabled 0
try adb shell cmd notification disallow_listener "$LISTENER_SERVICE"
try adb shell appops set "$PKG" GET_USAGE_STATS default
[ "$API" -ge 33 ] && try adb shell pm revoke "$PKG" android.permission.POST_NOTIFICATIONS
[ "$API" -ge 29 ] && try adb shell pm revoke "$PKG" android.permission.ACCESS_BACKGROUND_LOCATION
try adb shell pm revoke "$PKG" android.permission.ACCESS_FINE_LOCATION
try adb shell pm revoke "$PKG" android.permission.ACCESS_COARSE_LOCATION
try adb shell dumpsys deviceidle whitelist "-$PKG"
try adb shell dpm remove-active-admin --user 0 "$ADMIN_RECEIVER"
pass
