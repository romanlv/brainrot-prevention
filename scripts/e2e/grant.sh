#!/usr/bin/env bash
# Install the app, fixtures and e2e-driver and grant everything without UI (SPEC §14.6).
# Env: PKG (default dev.brainrotprevention.debug), APK (default by PKG), SKIP_INSTALL=1,
#      SKIP_SERVICE_WAIT=1 (skip the "a11y service connected" assert, e.g. before the service exists)
set -euo pipefail
OUT=${OUT:-$(git -C "$(dirname "$0")" rev-parse --show-toplevel)/build/verify/grant}
source "$(dirname "$0")/lib.sh"
mkdir -p "$OUT"
: >"$OUT/brp.jsonl"
: >"$OUT/logcat.txt"

API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
if [ -z "${APK:-}" ]; then
  if [ "$QA" = 1 ]; then APK=$APK_QA; else APK=$APK_DEBUG; fi
fi

install_one() { # install_one <apk> <package>
  local apk=$1 pkg=$2 out rc=0
  [ -f "$apk" ] || fail_build "missing $apk (build it first)"
  out=$(adb install -r -t -g "$apk" 2>&1) || rc=$?
  if [ "$rc" != 0 ] && printf '%s' "$out" | grep -qE 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|INSTALL_FAILED_VERSION_DOWNGRADE'; then
    say "reinstalling $pkg (incompatible update)"
    adb uninstall "$pkg" >/dev/null 2>&1 || true
    rc=0
    out=$(adb install -r -t -g "$apk" 2>&1) || rc=$?
  fi
  if [ "$rc" != 0 ] || ! printf '%s' "$out" | grep -q Success; then
    if printf '%s' "$out" | grep -qE 'INSUFFICIENT_STORAGE|offline|no devices|device not found|closed'; then
      fail_infra "install $pkg failed: $out"
    fi
    device_ok || fail_infra "install $pkg failed, device gone: $out"
    fail_build "install $pkg failed: $out"
  fi
  say "installed $pkg"
}

try() { # best-effort grant step: logs, never aborts
  if ! out=$("$@" 2>&1); then say "warn: '$*' failed: $(printf '%s' "$out" | head -n 1)"; fi
}

if [ "${SKIP_INSTALL:-0}" != 1 ]; then
  install_one "$APK" "$PKG"
  install_one "$APK_TARGET" "$T"
  install_one "$APK_BROWSER" "$BROWSER"
  install_one "$APK_BROWSER2" "$BROWSER2"
  install_one "$APK_DRIVER" "$DRIVER"
fi

# A reinstall rebinds an already-enabled service before the collector starts; turn it off first so the
# "connected" event of this grant lands after the mark.
try adb shell settings delete secure enabled_accessibility_services
sleep_s 1

# [V2-N12]: the control receiver works before the service binds
start_log
step grant

try adb shell appops set "$PKG" GET_USAGE_STATS allow
[ "$API" -ge 33 ] && try adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
try adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION
[ "$API" -ge 29 ] && try adb shell pm grant "$PKG" android.permission.ACCESS_BACKGROUND_LOCATION
[ "$API" -ge 33 ] && try adb shell pm grant "$T" android.permission.POST_NOTIFICATIONS
try adb shell cmd notification allow_listener "$LISTENER_SERVICE"
adb shell settings put secure enabled_accessibility_services "$A11Y_SERVICE"
adb shell settings put secure accessibility_enabled 1
try adb shell dumpsys deviceidle whitelist "+$PKG"

if [ "${SKIP_SERVICE_WAIT:-0}" = 1 ]; then
  say "SKIP_SERVICE_WAIT=1: not waiting for the accessibility service"
else
  assert_event '.ev=="service" and .name=="a11y" and .state=="connected"' 30
  adb shell dumpsys accessibility | grep -c BlockerAccessibilityService >/dev/null || fail_infra "a11y not bound"
fi
pass
