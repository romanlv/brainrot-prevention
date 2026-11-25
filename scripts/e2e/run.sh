#!/usr/bin/env bash
# scripts/e2e/run.sh [--repeat N] [--keep-going] [--json <file>] [S1 S2 …]   (SPEC §14.8, §14.9, §15)
# Runs E2E scenarios on emulator-5554. Without ids: every scenario at or below the current
# milestone (scripts/milestone; missing = error). Each attempt: ctl reset + fresh seed + new log collector,
# per-scenario cap, artefacts in build/verify/<Sx>/. An infra failure triggers emu.sh reset +
# grant.sh and one retry ("retried":true). Writes a JSON array of scenario results to --json
# (default build/verify/g4.json) and the planned ids to <json>.planned. Exit 0 when all passed.
# A teardown (ctl reset) that fails and whose emu.sh reset + grant.sh also fails fails the scenario
# ("teardown_failed":true): the next scenario could start with Strict Mode or the device admin still on.
set -euo pipefail
LIB_NO_RESULT=1
OUT=${OUT:-$(git -C "$(dirname "$0")" rev-parse --show-toplevel)/build/verify/run}
source "$(dirname "$0")/lib.sh"
trap - ERR
SCENARIO=run

REPEAT=1
KEEP_GOING=0
JSON="$ROOT/build/verify/g4.json"
IDS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --repeat) REPEAT=$2; shift 2 ;;
    --keep-going) KEEP_GOING=1; shift ;;
    --json) JSON=$2; shift 2 ;;
    S*) IDS+=("$1"); shift ;;
    *) echo "run.sh: unknown argument $1" >&2; exit 64 ;;
  esac
done

# milestone -> scenarios (SPEC §16)
scenarios_upto() {
  local n=$1 m s
  for m in 3 4 5 6 7 8 9; do
    [ "$m" -le "$n" ] || continue
    case $m in
      3) s="S1 S16" ;;
      4) s="S2 S3 S4 S7 S13" ;;
      5) s="S5 S6" ;;
      6) s="S8 S9 S15 S17" ;;
      7) s="S10 S14" ;;
      8) s="S11 S12" ;;
      9) s="" ;;
    esac
    echo "$s"
  done | tr ' ' '\n' | { grep . || true; } | sort -t S -k2 -n | tr '\n' ' '
}
cap_for() { case $1 in S13) echo 300 ;; S11) echo 120 ;; *) echo 60 ;; esac; }

mkdir -p "$(dirname "$JSON")"
RESULTS="$JSON.parts"
: >"$RESULTS"
: >"$JSON.planned"
if [ ${#IDS[@]} -eq 0 ]; then
  MILESTONE=$(read_milestone) || { echo '[]' >"$JSON"; exit 64; }
  MNUM=${MILESTONE#M}
  # shellcheck disable=SC2207
  IDS=($(scenarios_upto "$MNUM"))
  if [ ${#IDS[@]} -eq 0 ]; then
    echo "run.sh: no scenarios at milestone $MILESTONE" >&2
    echo '[]' >"$JSON"
    [ "$MNUM" -lt 3 ] && exit 0
    exit 1
  fi
fi
printf '%s\n' "${IDS[@]}" >"$JSON.planned"

ensure_device() {
  device_ok || "$ROOT/scripts/emu.sh" up || return 3
  if ! installed "$PKG" || ! installed "$T" || ! installed "$DRIVER"; then
    OUT="$ROOT/build/verify/grant" "$ROOT/scripts/e2e/grant.sh" || return $?
  fi
}

teardown() {
  OUT="$ROOT/build/verify/teardown" LIB_NO_RESULT=1 bash -c '
    source "$ROOT/scripts/e2e/lib.sh"; trap - ERR
    ctl_raw reset
    if [ "$CTL_RES" != 1 ]; then
      adb shell dpm remove-active-admin --user 0 "$ADMIN_RECEIVER" >/dev/null 2>&1 || true
      ctl_raw reset
    fi
    [ "$CTL_RES" = 1 ]' >/dev/null 2>&1
}

hard_reset() {
  echo "run.sh: emu.sh reset + grant.sh" >&2
  "$ROOT/scripts/emu.sh" reset && OUT="$ROOT/build/verify/grant" "$ROOT/scripts/e2e/grant.sh"
}

# after_attempt <Sx>: teardown; if it fails, emu.sh reset + grant.sh; record it in the result, and fail the
# attempt (kind infra) when the hard reset fails too.
after_attempt() {
  local id=$1 f="$ROOT/build/verify/$1/result.json"
  teardown && return 0
  echo "run.sh: $id teardown failed" >&2
  if hard_reset; then
    jq '. + {teardown_failed: true, teardown_recovered: true}' "$f" >"$f.tmp" && mv "$f.tmp" "$f"
  else
    jq '. + {teardown_failed: true, teardown_recovered: false}
        | if .ok then .ok = false | .kind = "infra" | .message = "passed, but teardown and emu.sh reset failed" else . end' \
      "$f" >"$f.tmp" && mv "$f.tmp" "$f"
    A_OK=false
    A_KIND=infra
  fi
}

# run_attempt <Sx> -> sets A_OK A_KIND A_MS; result in build/verify/<Sx>/result.json
run_attempt() {
  local id=$1 dir="$ROOT/build/verify/$1" rc=0 t0
  rm -rf "$dir"
  mkdir -p "$dir"
  t0=$(now_ms)
  OUT=$dir SCENARIO=$id with_timeout "$(cap_for "$id")" bash "$ROOT/scripts/e2e/$id.sh" || rc=$?
  A_MS=$(($(now_ms) - t0))
  # shellcheck disable=SC2046
  [ -f "$dir/.collectors" ] && kill $(cat "$dir/.collectors") 2>/dev/null || true
  rm -f "$dir/.collectors"
  if [ "$rc" = "$EXIT_TIMEOUT" ]; then
    jq -n --arg rel "build/verify/$id" --argjson cap "$(cap_for "$id")" \
      '{ok:false,kind:"timeout",message:("scenario exceeded its \($cap) s cap"),timeout_s:$cap,
        artefacts:{events:($rel+"/brp.jsonl"),logcat:($rel+"/logcat.txt")}}' >"$dir/result.json"
  elif [ ! -s "$dir/result.json" ]; then
    jq -n --argjson rc "$rc" '{ok:($rc==0),kind:"test",message:"scenario exited \($rc) without a result"}' >"$dir/result.json"
  elif [ "$rc" != 0 ] && [ "$(jq -r .ok "$dir/result.json")" = true ]; then
    jq --argjson rc "$rc" '.ok=false | .kind="test" | .message="exited \($rc) after pass"' "$dir/result.json" >"$dir/r.tmp" && mv "$dir/r.tmp" "$dir/result.json"
  fi
  A_OK=$(jq -r .ok "$dir/result.json")
  A_KIND=$(jq -r '.kind // ""' "$dir/result.json")
}

all_ok=1
for id in "${IDS[@]}"; do
  [ -f "$ROOT/scripts/e2e/$id.sh" ] || { echo "run.sh: no scenario $id" >&2; exit 64; }
  attempts=0
  retried=false
  total_ms=0
  ok=true
  rep=0
  while [ "$rep" -lt "$REPEAT" ]; do
    rep=$((rep + 1))
    infra_rc=0
    ensure_device || infra_rc=$?
    if [ "$infra_rc" != 0 ]; then
      A_OK=false
      A_KIND=infra
      A_MS=0
      mkdir -p "$ROOT/build/verify/$id"
      jq -n '{ok:false,kind:"infra",message:"emulator not available or install failed"}' >"$ROOT/build/verify/$id/result.json"
    else
      attempts=$((attempts + 1))
      echo "run.sh: $id (repeat $rep/$REPEAT, attempt $attempts)" >&2
      run_attempt "$id"
      after_attempt "$id"
    fi
    total_ms=$((total_ms + A_MS))
    if [ "$A_OK" != true ] && [ "$A_KIND" = infra ] && [ "$retried" = false ]; then
      retried=true
      hard_reset || true
      attempts=$((attempts + 1))
      echo "run.sh: $id infra failure; retrying once" >&2
      run_attempt "$id"
      after_attempt "$id"
      total_ms=$((total_ms + A_MS))
    fi
    if [ "$A_OK" != true ]; then
      ok=false
      break
    fi
  done
  jq -c --arg id "$id" --argjson ok "$ok" --argjson attempts "$attempts" --argjson retried "$retried" \
    --argjson ms "$total_ms" --argjson rep "$REPEAT" '
      {id: $id, ok: $ok, attempts: $attempts, ms: $ms}
      + (if $retried then {retried: true} else {} end)
      + (if $rep > 1 then {repeat: $rep} else {} end)
      + (if $ok then {} else del(.ok) end)
      + with_entries(select(.key == "teardown_failed" or .key == "teardown_recovered"))' "$ROOT/build/verify/$id/result.json" >>"$RESULTS"
  echo "run.sh: $id $([ "$ok" = true ] && echo PASS || echo FAIL)" >&2
  if [ "$ok" != true ]; then
    all_ok=0
    [ "$KEEP_GOING" = 1 ] || break
  fi
done

jq -s . "$RESULTS" >"$JSON.tmp" && mv "$JSON.tmp" "$JSON"
rm -f "$RESULTS"
[ "$all_ok" = 1 ]
