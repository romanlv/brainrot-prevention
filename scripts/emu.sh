#!/usr/bin/env bash
# scripts/emu.sh up|down|reset|rebuild|status   (SPEC §14.5)
# One emulator only: AVD brp37 on port 5554, snapshot "clean".
# Exit codes: 0 ok, 3 infra.
set -euo pipefail
LIB_NO_RESULT=1
OUT=${OUT:-$(git -C "$(dirname "$0")" rev-parse --show-toplevel)/build/emu}
source "$(dirname "$0")/e2e/lib.sh"
trap - ERR
SCENARIO=emu

# BRP_AVD=brp37u selects the Google Play (user build, 37.1, 16 KB pages) image, which matches real phones:
# hidden-settings and other restrictions that debuggable google_apis images skip are enforced there.
AVD=${BRP_AVD:-brp37}
case "$AVD" in
  brp37) DEFAULT_IMAGE="system-images;android-37.0;google_apis;arm64-v8a" ;;
  brp37u) DEFAULT_IMAGE="system-images;android-37.1;google_apis_playstore_ps16k;arm64-v8a" ;;
  *) DEFAULT_IMAGE="" ;;
esac
IMAGE=${BRP_IMAGE:-$DEFAULT_IMAGE}
[ -n "$IMAGE" ] || { echo "emu.sh: INFRA: set BRP_IMAGE for AVD $AVD" >&2; exit 3; }
IMAGE_DIR="$ANDROID_HOME/$(echo "$IMAGE" | tr ';' '/')"
AVD_DIR="$HOME/.android/avd/$AVD.avd"
META="$AVD_DIR/brp-snapshot.json"
GPU=swiftshader_indirect
EMU_LOG="$ROOT/build/emu.log"
EMU_FLAGS=(-avd "$AVD" -port 5554 -no-window -no-audio -no-boot-anim -no-metrics
  -gpu "$GPU" -camera-back none -camera-front none -timezone Europe/Berlin)
mkdir -p "$ROOT/build"

die_infra() { echo "emu.sh: INFRA: $*" >&2; exit 3; }
info() { echo "emu.sh: $*" >&2; }

emulator_rev() { sed -n 's/^Pkg.Revision=//p' "$ANDROID_HOME/emulator/source.properties" | tr -d '\r'; }
image_rev() { sed -n 's/^Pkg.Revision=//p' "$IMAGE_DIR/source.properties" | tr -d '\r'; }
want_meta() { jq -cn --arg e "$(emulator_rev)" --arg i "$(image_rev)" --arg g "$GPU" '{emulator_rev:$e,image_rev:$i,gpu:$g}'; }

# host-side device list (no device command is sent)
listed() { ADB_TIMEOUT=10 adb devices 2>/dev/null | awk 'NR>1 && $1=="emulator-5554"{print $2}'; }
ours() { [ "$(ADB_TIMEOUT=10 adb emu avd name 2>/dev/null | tr -d '\r' | head -n 1)" = "$AVD" ]; }

port_check() {
  # emulator-5554 already present or port 5554 taken, and it isn't our brp37 -> infra
  if [ -n "$(listed)" ] || lsof -nP -iTCP:5554 -sTCP:LISTEN >/dev/null 2>&1; then
    if ours; then return 0; fi
    die_infra "emulator-5554 / port 5554 is in use by something that isn't $AVD"
  fi
  return 1
}

snapshot_ok() {
  [ -d "$AVD_DIR/snapshots/clean" ] && [ -f "$META" ] && [ "$(jq -c . "$META" 2>/dev/null)" = "$(want_meta)" ]
}

start_emulator() { # start_emulator <extra flags...>
  info "starting $AVD $*"
  : >"$EMU_LOG"
  nohup emulator "${EMU_FLAGS[@]}" "$@" >>"$EMU_LOG" 2>&1 &
  echo $! >"$ROOT/build/emu.pid"
  # wait for the device to appear before the shim's qemu check can pass
  local d=$((SECONDS + 60))
  while [ "$SECONDS" -lt "$d" ]; do
    [ -n "$(listed)" ] && return 0
    qemu_alive || die_infra "emulator process died (see build/emu.log)"
    sleep 1
  done
  die_infra "emulator-5554 never appeared in adb devices"
}

boot_or_die() {
  local rc=0
  wait_boot "$1" || rc=$?
  case $rc in
    0) ;;
    1) die_infra "emulator process died during boot (see build/emu.log)" ;;
    *) die_infra "boot timed out after $1 s" ;;
  esac
}

clock_drift() { # prints |device - host| in seconds
  local dev host
  dev=$(adb shell date +%s | tr -d '\r')
  host=$(date +%s)
  echo $(((dev > host) ? dev - host : host - dev))
}

cmd_down() {
  if [ -z "$(listed)" ] && ! qemu_alive; then info "not running"; return 0; fi
  if [ -n "$(listed)" ]; then
    ours || die_infra "emulator-5554 is not $AVD; not stopping it"
    ADB_TIMEOUT=15 adb emu kill >/dev/null 2>&1 || true
  fi
  local d=$((SECONDS + 60))
  while [ "$SECONDS" -lt "$d" ]; do
    qemu_alive || { info "stopped"; rm -f "$ROOT/build/emu.pid"; return 0; }
    sleep 1
  done
  info "emu kill did not stop it; sending SIGTERM to $AVD's qemu"
  pkill -f -- "-avd $AVD( |$)" || true
  sleep 3
  pkill -9 -f -- "-avd $AVD( |$)" || true
}

configure_clean() {
  adb shell settings put global window_animation_scale 0
  adb shell settings put global transition_animation_scale 0
  adb shell settings put global animator_duration_scale 0
  adb shell svc power stayon true
  adb shell locksettings set-disabled true || true
  adb shell settings put secure show_ime_with_hard_keyboard 0
  adb shell cmd location set-location-enabled true || true
  adb shell svc wifi disable || true
  adb shell svc data disable || true
  adb shell settings put global package_verifier_user_consent -1 || true
  adb logcat -G 16M
}

cmd_rebuild() {
  cmd_down
  info "creating AVD $AVD"
  echo no | avdmanager create avd -n "$AVD" -k "$IMAGE" -d pixel_8 --force >/dev/null
  printf 'hw.ramSize=4096\ndisk.dataPartition.size=6G\nhw.keyboard=yes\n' >>"$AVD_DIR/config.ini"
  start_emulator -no-snapshot-load -no-snapshot-save
  boot_or_die 240
  configure_clean
  sleep 5
  info "saving snapshot clean"
  ADB_TIMEOUT=300 adb emu avd snapshot save clean | tr -d '\r' | grep -q OK || die_infra "snapshot save failed"
  want_meta >"$META"
  cmd_down
  info "rebuilt $AVD with snapshot clean ($(cat "$META"))"
}

cmd_up() {
  if port_check; then
    info "$AVD already running"
    boot_or_die 120
    return 0
  fi
  snapshot_ok || { info "snapshot clean missing or stale; rebuilding"; cmd_rebuild; }
  local attempt
  for attempt in 1 2; do
    start_emulator -snapshot clean -no-snapshot-save
    boot_or_die 120
    if grep -qiE 'snapshot.*(fail|error|invalid)|cold boot|Loading snapshot .* failed' "$EMU_LOG"; then
      [ "$attempt" = 2 ] && die_infra "snapshot load failed twice (see build/emu.log)"
      info "snapshot load failed / cold boot; rebuilding"
      cmd_down
      cmd_rebuild
      continue
    fi
    local drift
    drift=$(clock_drift)
    if [ "$drift" -gt 2 ]; then
      [ "$attempt" = 2 ] && die_infra "device clock differs from host by ${drift}s after restart"
      info "clock drift ${drift}s; cold-booting once"
      cmd_down
      start_emulator -no-snapshot-load -no-snapshot-save
      boot_or_die 240
      drift=$(clock_drift)
      [ "$drift" -le 2 ] || die_infra "device clock differs from host by ${drift}s after a cold boot"
    fi
    info "up ($(adb shell getprop ro.build.version.release | tr -d '\r'), API $(adb shell getprop ro.build.version.sdk | tr -d '\r'))"
    return 0
  done
}

cmd_status() {
  if [ -n "$(listed)" ] && ours; then echo "running $AVD"; else echo "down"; fi
}

case "${1:-}" in
  up) cmd_up ;;
  down) cmd_down ;;
  reset) cmd_down; cmd_up ;;
  rebuild) cmd_rebuild ;;
  status) cmd_status ;;
  *) echo "usage: $0 up|down|reset|rebuild|status" >&2; exit 64 ;;
esac
