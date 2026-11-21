#!/usr/bin/env bash
# scripts/verify.sh               G0–G2 (no emulator). Run after every change.
# scripts/verify.sh --device      + G3, G4 on brp37 (boots it if needed)
# scripts/verify.sh --full        + qa build (R8) S1/S8 smoke, release manifest check (brp28, S12b, S17: open)
# scripts/verify.sh --only S5     a single gate (G0…G4) or scenario (S1…S16); comma-separated for several
#                   --repeat N    repeat scenarios N times;  --keep-going  don't stop at the first failing gate
# One verdict: build/verify/report.json (SPEC §14.9), written atomically; complete:false until the end.
set -euo pipefail
LIB_NO_RESULT=1
OUT=${OUT:-$(git -C "$(dirname "$0")" rev-parse --show-toplevel)/build/verify}
source "$(dirname "$0")/e2e/lib.sh"
trap - ERR
trap - EXIT
SCENARIO=verify

ARGS=("$@")
DEVICE=0
FULL=0
ONLY=""
REPEAT=1
KEEP_GOING=0
while [ $# -gt 0 ]; do
  case "$1" in
    --device) DEVICE=1; shift ;;
    --full) DEVICE=1; FULL=1; shift ;;
    --only) ONLY="${ONLY:+$ONLY,}$2"; shift 2 ;;
    --repeat) REPEAT=$2; shift 2 ;;
    --keep-going) KEEP_GOING=1; shift ;;
    -h | --help) sed -n '2,8p' "$0"; exit 0 ;;
    *) echo "verify.sh: unknown argument $1" >&2; exit 64 ;;
  esac
done

VDIR="$ROOT/build/verify"
mkdir -p "$VDIR"
REPORT="$VDIR/report.json"
GATES="$VDIR/.gates.jsonl"
: >"$GATES"
COMMIT=$(git -C "$ROOT" rev-parse --short=7 HEAD 2>/dev/null || echo none)
DIRTY=false
[ -n "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ] && DIRTY=true
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-${COMMIT:0:4}"
STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
COLD=true
pgrep -f GradleDaemon >/dev/null 2>&1 && COLD=false
MILESTONE=$(tr -d ' \n\r' <"$VDIR/milestone" 2>/dev/null || echo M0)
MNUM=${MILESTONE#M}

# ---- plan ------------------------------------------------------------------------------------
PLAN=()
SCEN_IDS=()
if [ -n "$ONLY" ]; then
  for x in $(echo "$ONLY" | tr ',' ' '); do
    case "$x" in
      G0 | G1 | G2 | G3 | G4) PLAN+=("$x") ;;
      S*) SCEN_IDS+=("$x") ;;
      *) echo "verify.sh: --only takes G0…G4 or S1…S16, got $x" >&2; exit 64 ;;
    esac
  done
  if [ ${#SCEN_IDS[@]} -gt 0 ]; then
    case " ${PLAN[*]-} " in *" G4 "*) ;; *) PLAN+=(G4) ;; esac
  fi
else
  PLAN=(G0 G1 G2)
  [ "$DEVICE" = 1 ] && PLAN+=(G3 G4)
  [ "$FULL" = 1 ] && PLAN+=(FULL)
fi

# ---- env -------------------------------------------------------------------------------------
toml() { sed -n "s/^$1 *= *\"\\(.*\\)\"/\\1/p" "$ROOT/gradle/libs.versions.toml" | head -n 1; }
ENV_JSON=$(jq -cn \
  --arg java "$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -n 1)" \
  --arg agp "$(toml agp)" --arg kotlin "$(toml kotlin)" \
  --arg gradle "$(sed -n 's/.*gradle-\([0-9.]*\)-.*/\1/p' "$ROOT/gradle/wrapper/gradle-wrapper.properties")" \
  --arg image "android-37.0;google_apis;arm64-v8a r$(sed -n 's/^Pkg.Revision=//p' "$ANDROID_HOME/system-images/android-37.0/google_apis/arm64-v8a/source.properties" 2>/dev/null)" \
  --arg emulator "$(sed -n 's/^Pkg.Revision=//p' "$ANDROID_HOME/emulator/source.properties" 2>/dev/null)" \
  --arg serial "$ANDROID_SERIAL" --argjson cold "$COLD" --arg milestone "$MILESTONE" \
  '{java:$java,agp:$agp,gradle:$gradle,kotlin:$kotlin,image:$image,emulator:$emulator,serial:$serial,cold:$cold,milestone:$milestone}')

write_report() { # write_report <complete true|false>
  local complete=$1 finished=""
  [ "$complete" = true ] && finished=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  local plan_json
  plan_json=$(printf '%s\n' "${PLAN[@]}" | jq -R . | jq -sc .)
  jq -s --arg run_id "$RUN_ID" --argjson complete "$complete" --arg commit "$COMMIT" --argjson dirty "$DIRTY" \
    --argjson args "$(printf '%s\n' ${ARGS[@]+"${ARGS[@]}"} | jq -R . | jq -sc 'map(select(. != ""))')" \
    --arg started "$STARTED" --arg finished "$finished" --argjson env "$ENV_JSON" --argjson plan "$plan_json" '
    . as $gates
    | ($plan - ($gates | map(.id))) as $missing
    | ([$gates[] | select(.ok != true)] | first) as $bad
    | {schema: 1, run_id: $run_id,
       ok: ($complete and ($missing | length) == 0 and ($bad == null)),
       complete: $complete, commit: $commit, dirty: $dirty, args: $args,
       started: $started, finished: (if $finished == "" then null else $finished end),
       env: $env, gates: $gates}
      + (if ($missing | length) > 0 and $complete then {skipped: $missing} else {} end)
      + {next: (if $bad == null then (if ($missing | length) > 0 then "--only " + $missing[0] else null end)
                elif $bad.id == "G4" then
                  "--only " + ((($bad.scenarios // []) | map(select(.ok != true)) | first | .id) // "G4")
                else "--only " + $bad.id end)}' \
    "$GATES" >"$REPORT.tmp"
  mv "$REPORT.tmp" "$REPORT"
}
finalize() {
  local rc=$?
  trap - EXIT
  write_report true || echo "verify.sh: could not write the report" >&2
  local ok
  ok=$(jq -r .ok "$REPORT" 2>/dev/null || echo false)
  echo "verify.sh: ok=$ok  report=build/verify/report.json  run_id=$RUN_ID" >&2
  jq -c '{ok, next, gates: [.gates[] | {id, ok, kind, ms}]}' "$REPORT" >&2 || true
  [ "$ok" = true ] && exit 0
  [ "$rc" != 0 ] && exit "$rc"
  exit 1
}
write_report false
trap finalize EXIT
trap 'exit 130' INT TERM

add_gate() { printf '%s\n' "$1" | jq -c . >>"$GATES"; }

# ---- helpers ---------------------------------------------------------------------------------
cap() { # cap <budget seconds> -> 3x budget (x3 again on a cold run)
  local c=$(($1 * 3))
  [ "$COLD" = true ] && c=$((c * 3))
  echo "$c"
}

gradle() { # gradle <cap> <log> tasks...
  local c=$1 log=$2
  shift 2
  (cd "$ROOT" && with_timeout "$c" ./gradlew --console=plain "$@") >"$log" 2>&1
}

classify_gradle() { # classify_gradle <log> <rc> -> build|test|timeout
  local log=$1 rc=$2
  if [ "$rc" = "$EXIT_TIMEOUT" ]; then echo timeout; return; fi
  if grep -qE "Compilation error|^e: |Could not resolve|A problem occurred (configuring|evaluating)|Execution failed for task '.*:(compile|ksp|kapt|process|merge|dex|link|package|assemble)|Unresolved reference|Plugin \[.*\] was not found|Task '.*' not found" "$log"; then
    echo build
  else
    echo test
  fi
}

first_errors() { # first_errors <log>: short failure excerpt
  grep -E "^e: |error:|Error:|FAILED|What went wrong|Lint found|Exception|\.kts?:[0-9]+:[0-9]+|not found|> " "$1" 2>/dev/null |
    grep -v '^> Task' | head -n 20 | cut -c1-300 || true
}

# junit_summary <dir>... -> {"tests":N,"failed_tests":[{"test":"class#method","detail":"…"}]}
junit_summary() {
  perl -MJSON::PP -e '
    my ($tests, @failed) = (0);
    for my $dir (@ARGV) {
      for my $f (glob("$dir/*.xml")) {
        local $/; open(my $fh, "<", $f) or next; my $x = <$fh>; close $fh;
        while ($x =~ /<testcase\b([^>]*?)(\/>|>(.*?)<\/testcase>)/sg) {
          my ($attrs, $body) = ($1, $3 // "");
          $tests++;
          my ($name) = $attrs =~ /\bname="([^"]*)"/; my ($cls) = $attrs =~ /\bclassname="([^"]*)"/;
          if ($body =~ /<(failure|error)\b[^>]*>(.*?)<\/\1>/s || $body =~ /<(failure|error)\b([^>]*)\/>/s) {
            my $d = $2; $d =~ s/&lt;/</g; $d =~ s/&gt;/>/g; $d =~ s/&quot;/"/g; $d =~ s/&amp;/&/g;
            my @l = split /\n/, $d; $d = join("\n", @l[0 .. ($#l < 19 ? $#l : 19)]);
            push @failed, { test => "$cls#$name", detail => $d };
          }
        }
      }
    }
    print JSON::PP->new->canonical->encode({ tests => $tests, failed_tests => \@failed });' "$@"
}

# instr_summary <am-instrument -r output> -> same shape as junit_summary (+ "crashed")
instr_summary() {
  perl -MJSON::PP -e '
    local $/; my $x = <STDIN>; my ($tests, @failed, %st, $key) = (0);
    for my $line (split /\n/, $x) {
      $line =~ s/\r$//;
      if ($line =~ /^INSTRUMENTATION_STATUS: (\w+)=(.*)$/) { $key = $1; $st{$key} = $2; next }
      if ($line =~ /^INSTRUMENTATION_STATUS_CODE: (-?\d+)/) {
        my $c = $1;
        $tests++ if $c == 1;
        if ($c == -1 || $c == -2) {
          my @l = split /\n/, ($st{stack} // ""); my $d = join("\n", @l[0 .. ($#l < 19 ? $#l : 19)]);
          push @failed, { test => ($st{class} // "?") . "#" . ($st{test} // "?"), detail => $d };
        }
        %st = (); $key = undef; next;
      }
      if ($line =~ /^INSTRUMENTATION_(RESULT|CODE)/) { $key = undef; next }
      $st{$key} .= "\n$line" if defined $key;
    }
    my $crashed = ($x =~ /INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED/) ? JSON::PP::true : JSON::PP::false;
    print JSON::PP->new->canonical->encode({ tests => $tests, failed_tests => \@failed, crashed => $crashed });'
}

kover_line_ratio() { # kover_line_ratio <report.xml> -> ratio or empty
  [ -f "$1" ] || return 0
  perl -e 'local $/; my $x = <>; my @m = $x =~ /<counter type="LINE" missed="(\d+)" covered="(\d+)"\s*\/>/g;
    exit 0 unless @m; my ($mi, $co) = @m[-2, -1]; printf "%.4f", ($mi + $co) ? $co / ($mi + $co) : 0' "$1"
}

gate_fail_stop() { # stop after a failing gate unless --keep-going
  [ "$KEEP_GOING" = 1 ] || exit 1
}

# ---- G0: lint, ktlint, forbidden time calls --------------------------------------------------
gate_G0() {
  local dir="$VDIR/G0" t0 rc=0 log
  mkdir -p "$dir"
  log="$dir/gradle.log"
  t0=$(now_ms)
  gradle "$(cap 120)" "$log" --continue lint ktlintCheck || rc=$?
  if [ "$rc" != 0 ] && grep -q "Created baseline file" "$log"; then
    echo "verify.sh: G0 lint baseline created; re-running lint once" >&2
    rc=0
    gradle "$(cap 120)" "$log" --continue lint ktlintCheck || rc=$?
  fi
  local forbidden
  forbidden=$(cd "$ROOT" && git grep --untracked -nE 'System\.currentTimeMillis|Instant\.now|ZonedDateTime\.now|LocalDateTime\.now' \
    -- '*.kt' ':!**/PolicyClock*.kt' ':!**/SystemTime*.kt' 2>/dev/null || true)
  printf '%s\n' "$forbidden" >"$dir/forbidden-time-calls.txt"
  local ok=true kind="" msg=""
  if [ "$rc" != 0 ]; then
    ok=false
    kind=$(classify_gradle "$log" "$rc")
    msg=$(first_errors "$log")
  fi
  if [ -n "$forbidden" ]; then
    ok=false
    kind=${kind:-test}
    msg="${msg:+$msg
}forbidden time calls (use PolicyClock/SystemTime):
$(printf '%s\n' "$forbidden" | head -n 20)"
  fi
  add_gate "$(jq -n --argjson ok "$ok" --arg kind "$kind" --arg msg "$msg" --argjson ms $(($(now_ms) - t0)) \
    --arg forb "$forbidden" --arg log "build/verify/G0/gradle.log" '
    {id: "G0", ok: $ok, ms: $ms, log: $log,
     forbidden_time_calls: ($forb | split("\n") | map(select(. != "")))}
    + (if $ok then {} else {kind: $kind, message: $msg} end)')"
  [ "$ok" = true ] || gate_fail_stop
}

# ---- G1 / G2: unit tests ---------------------------------------------------------------------
unit_gate() { # unit_gate <id> <budget> <results-dirs (space separated)> tasks...
  local id=$1 budget=$2 dirs=$3
  shift 3
  local dir="$VDIR/$id" t0 rc=0 log
  mkdir -p "$dir"
  log="$dir/gradle.log"
  t0=$(now_ms)
  gradle "$(cap "$budget")" "$log" "$@" || rc=$?
  local abs=() d
  for d in $dirs; do abs+=("$ROOT/$d"); done
  local sum
  sum=$(junit_summary "${abs[@]}")
  local ok=true kind="" msg=""
  if [ "$rc" != 0 ]; then
    ok=false
    if [ "$(printf '%s' "$sum" | jq '.failed_tests | length')" -gt 0 ]; then kind=test; else kind=$(classify_gradle "$log" "$rc"); fi
    msg=$(first_errors "$log")
  fi
  local cov='{}'
  if [ "$id" = G1 ]; then
    local r
    r=$(kover_line_ratio "$ROOT/core/engine/build/reports/kover/report.xml")
    [ -n "$r" ] && cov=$(jq -cn --argjson r "$r" '{"core:engine": $r}')
  fi
  add_gate "$(jq -n --arg id "$id" --argjson ok "$ok" --arg kind "$kind" --arg msg "$msg" --argjson ms $(($(now_ms) - t0)) \
    --argjson sum "$sum" --argjson cov "$cov" --arg log "build/verify/$id/gradle.log" '
    {id: $id, ok: $ok, ms: $ms, tests: $sum.tests, failed_tests: $sum.failed_tests, log: $log}
    + (if $id == "G1" then {coverage: $cov} else {} end)
    + (if $ok then {} else {kind: $kind, message: $msg} end)')"
  [ "$ok" = true ] || gate_fail_stop
}

gate_G1() {
  local tasks=(:core:engine:test :core:model:test koverVerify)
  grep -q kover "$ROOT/core/engine/build.gradle.kts" 2>/dev/null && tasks+=(:core:engine:koverXmlReport)
  rm -rf "$ROOT/core/engine/build/test-results/test" "$ROOT/core/model/build/test-results/test"
  unit_gate G1 60 "core/engine/build/test-results/test core/model/build/test-results/test" "${tasks[@]}"
}
gate_G2() {
  rm -rf "$ROOT/core/data/build/test-results/testDebugUnitTest"
  unit_gate G2 120 "core/data/build/test-results/testDebugUnitTest" :core:data:testDebugUnitTest
}

# ---- device preparation (shared by G3, G4, FULL) ---------------------------------------------
PREPARED=0
PREP_KIND=""
PREP_MSG=""
device_prep() { # boots brp37, builds every APK, installs + grants. Sets PREP_KIND/PREP_MSG on failure.
  [ "$PREPARED" = 1 ] && return 0
  local dir="$VDIR/G3" rc=0
  mkdir -p "$dir"
  if ! "$ROOT/scripts/emu.sh" up >"$dir/emu.log" 2>&1; then
    PREP_KIND=infra
    PREP_MSG="emu.sh up failed: $(tail -n 3 "$dir/emu.log")"
    return 1
  fi
  gradle "$(cap 300)" "$dir/gradle.log" :app:assembleDebug :app:assembleDebugAndroidTest :e2e-driver:assembleDebug \
    :fixture-apps:target:assembleDebug :fixture-apps:browser:assembleDebug :fixture-apps:browser2:assembleDebug || rc=$?
  if [ "$rc" != 0 ]; then
    PREP_KIND=$(classify_gradle "$dir/gradle.log" "$rc")
    [ "$PREP_KIND" = test ] && PREP_KIND=build
    PREP_MSG=$(first_errors "$dir/gradle.log")
    return 1
  fi
  rc=0
  OUT="$VDIR/grant" "$ROOT/scripts/e2e/grant.sh" >"$dir/grant.log" 2>&1 || rc=$?
  if [ "$rc" != 0 ]; then
    PREP_KIND=$(jq -r '.kind // "infra"' "$VDIR/grant/result.json" 2>/dev/null || echo infra)
    PREP_MSG="grant.sh failed: $(jq -r '.message // empty' "$VDIR/grant/result.json" 2>/dev/null || tail -n 3 "$dir/grant.log")"
    return 1
  fi
  if [ -f "$APK_TEST" ]; then
    adb install -r -t -g "$APK_TEST" >"$dir/install-test.log" 2>&1 || {
      PREP_KIND=infra
      PREP_MSG="install of the androidTest APK failed: $(tail -n 2 "$dir/install-test.log")"
      return 1
    }
  fi
  PREPARED=1
}

# ---- G3: instrumentation ---------------------------------------------------------------------
TEST_RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
instrument() { # instrument <log> [am instrument -e args...]
  local log=$1
  shift
  ADB_TIMEOUT=$(cap 480) ashell am instrument -w -r -e milestone "$MILESTONE" \
    -e filter dev.brainrotprevention.testing.MilestoneFilter "$@" "$TEST_RUNNER" >"$log" 2>&1 || true
}

gate_G3_once() {
  local dir="$VDIR/G3" t0
  t0=$(now_ms)
  if ! device_prep; then
    add_gate "$(jq -n --arg kind "$PREP_KIND" --arg msg "$PREP_MSG" --argjson ms $(($(now_ms) - t0)) \
      '{id: "G3", ok: false, ms: $ms, kind: $kind, message: $msg, tests: 0, failed_tests: []}')"
    return 1
  fi
  if [ "$MNUM" -lt 3 ]; then
    # G3 tests start at M3 (SPEC §16); below that G3 is build + install + grant only.
    add_gate "$(jq -n --argjson ms $(($(now_ms) - t0)) --arg m "$MILESTONE" \
      '{id: "G3", ok: true, ms: $ms, tests: 0, failed_tests: [], note: ("no instrumentation tests below M3 (milestone " + $m + "); built, installed, granted")}')"
    return 0
  fi
  # order: ungrant -> no-grant class -> grant -> service connected -> the rest (SPEC §14.4)
  OUT="$VDIR/ungrant" "$ROOT/scripts/e2e/ungrant.sh" >"$dir/ungrant.log" 2>&1 || true
  instrument "$dir/instrument-nogrant.txt" -e annotation dev.brainrotprevention.testing.NoGrant
  local rc=0
  SKIP_INSTALL=1 OUT="$VDIR/grant" "$ROOT/scripts/e2e/grant.sh" >"$dir/grant2.log" 2>&1 || rc=$?
  if [ "$rc" != 0 ]; then
    add_gate "$(jq -n --arg msg "grant.sh after the no-grant class failed" --argjson ms $(($(now_ms) - t0)) \
      '{id: "G3", ok: false, ms: $ms, kind: "infra", message: $msg, tests: 0, failed_tests: []}')"
    return 1
  fi
  instrument "$dir/instrument.txt" -e notAnnotation dev.brainrotprevention.testing.NoGrant
  local s1 s2
  s1=$(instr_summary <"$dir/instrument-nogrant.txt")
  s2=$(instr_summary <"$dir/instrument.txt")
  local ok kind="" msg=""
  ok=$(jq -n --argjson a "$s1" --argjson b "$s2" '($a.failed_tests + $b.failed_tests | length) == 0 and ($a.crashed | not) and ($b.crashed | not)')
  if [ "$ok" != true ]; then
    kind=test
    device_ok || kind=infra
    msg=$(grep -hE 'INSTRUMENTATION_FAILED|Process crashed|FAILURES!!!|Error' "$dir/instrument-nogrant.txt" "$dir/instrument.txt" | head -n 10 || true)
  fi
  add_gate "$(jq -n --argjson ok "$ok" --arg kind "$kind" --arg msg "$msg" --argjson ms $(($(now_ms) - t0)) \
    --argjson a "$s1" --argjson b "$s2" '
    {id: "G3", ok: $ok, ms: $ms, tests: ($a.tests + $b.tests), failed_tests: ($a.failed_tests + $b.failed_tests),
     log: "build/verify/G3/instrument.txt"}
    + (if $ok then {} else {kind: $kind, message: $msg} end)')"
  [ "$ok" = true ]
}

gate_G3() {
  local before
  before=$(wc -l <"$GATES")
  if gate_G3_once; then return 0; fi
  if [ "$(tail -n 1 "$GATES" | jq -r .kind)" = infra ]; then
    echo "verify.sh: G3 infra failure; emu.sh reset and one retry" >&2
    # drop the failed entry, retry once
    head -n "$before" "$GATES" >"$GATES.tmp" && mv "$GATES.tmp" "$GATES"
    PREPARED=0
    "$ROOT/scripts/emu.sh" reset >"$VDIR/G3/emu-reset.log" 2>&1 || true
    if gate_G3_once; then
      tail -n 1 "$GATES" | jq -c '. + {retried: true}' >"$GATES.last"
      head -n "$before" "$GATES" >"$GATES.tmp" && cat "$GATES.last" >>"$GATES.tmp" && mv "$GATES.tmp" "$GATES" && rm -f "$GATES.last"
      return 0
    fi
    tail -n 1 "$GATES" | jq -c '. + {retried: true}' >"$GATES.last"
    head -n "$before" "$GATES" >"$GATES.tmp" && cat "$GATES.last" >>"$GATES.tmp" && mv "$GATES.tmp" "$GATES" && rm -f "$GATES.last"
  fi
  gate_fail_stop
}

# ---- G4: scenarios ---------------------------------------------------------------------------
gate_G4() {
  local t0 rc=0
  t0=$(now_ms)
  if ! device_prep; then
    add_gate "$(jq -n --arg kind "$PREP_KIND" --arg msg "$PREP_MSG" --argjson ms $(($(now_ms) - t0)) \
      '{id: "G4", ok: false, ms: $ms, kind: $kind, message: $msg, scenarios: []}')"
    gate_fail_stop
    return 0
  fi
  local args=(--json "$VDIR/g4.json" --repeat "$REPEAT")
  [ "$KEEP_GOING" = 1 ] && args+=(--keep-going)
  with_timeout $(($(cap 1500) * REPEAT)) "$ROOT/scripts/e2e/run.sh" "${args[@]}" ${SCEN_IDS[@]+"${SCEN_IDS[@]}"} \
    >"$VDIR/g4.log" 2>&1 || rc=$?
  local scen='[]'
  [ -s "$VDIR/g4.json" ] && scen=$(jq -c . "$VDIR/g4.json")
  local ok=true kind="" msg=""
  if [ "$rc" != 0 ]; then
    ok=false
    if [ "$rc" = "$EXIT_TIMEOUT" ]; then kind=timeout; msg="G4 exceeded its cap"; else
      kind=$(printf '%s' "$scen" | jq -r '[.[] | select(.ok != true) | .kind] | first // "test"')
      msg=$(printf '%s' "$scen" | jq -r '[.[] | select(.ok != true) | "\(.id): \(.message // "")"] | join("; ")')
    fi
  fi
  add_gate "$(jq -n --argjson ok "$ok" --arg kind "$kind" --arg msg "$msg" --argjson ms $(($(now_ms) - t0)) \
    --argjson scen "$scen" --arg log "build/verify/g4.log" '
    {id: "G4", ok: $ok, ms: $ms, scenarios: $scen, log: $log}
    + (if $ok then {} else {kind: $kind, message: $msg} end)')"
  [ "$ok" = true ] || gate_fail_stop
}

# ---- FULL: release manifest check, qa smoke, open items --------------------------------------
gate_FULL() {
  local dir="$VDIR/FULL" t0 rc=0 ok=true kind="" msg=""
  mkdir -p "$dir"
  t0=$(now_ms)
  gradle "$(cap 600)" "$dir/gradle.log" :app:assembleRelease :app:assembleQa || rc=$?
  if [ "$rc" != 0 ]; then
    ok=false
    kind=$(classify_gradle "$dir/gradle.log" "$rc")
    msg=$(first_errors "$dir/gradle.log")
  else
    local rel apkan="$ANDROID_HOME/cmdline-tools/latest/bin/apkanalyzer"
    rel=$(ls "$ROOT"/app/build/outputs/apk/release/app-release*.apk 2>/dev/null | head -n 1)
    if [ -z "$rel" ]; then
      ok=false; kind=build; msg="no release APK"
    elif [ "$("$apkan" manifest print "$rel" | grep -c DebugControlReceiver || true)" != 0 ] ||
      [ "$("$apkan" files list "$rel" | grep -c browsers.debug.json || true)" != 0 ]; then
      ok=false; kind=test; msg="release APK contains debug hooks (DebugControlReceiver or browsers.debug.json)"
    fi
    if [ "$ok" = true ]; then
      # qa (R8) smoke: S1 and S8 against the minified build
      if PKG=dev.brainrotprevention.qa OUT="$VDIR/grant-qa" "$ROOT/scripts/e2e/grant.sh" >"$dir/grant-qa.log" 2>&1 &&
        PKG=dev.brainrotprevention.qa "$ROOT/scripts/e2e/run.sh" --json "$VDIR/qa.json" S1 S8 >"$dir/qa.log" 2>&1; then :; else
        ok=false; kind=test; msg="qa smoke (S1, S8) failed; see build/verify/qa.json"
      fi
    fi
  fi
  # Not implemented yet (docs/DEVIATIONS.md "Open"): brp28 AVD, S12b, S17, a11y CPU measurement
  ok=false
  kind=${kind:-test}
  msg="${msg:+$msg; }open: brp28 boot check, S12b, S17 and the cpuinfo measurement are not implemented yet"
  local qa='[]'
  [ -s "$VDIR/qa.json" ] && qa=$(jq -c . "$VDIR/qa.json")
  add_gate "$(jq -n --argjson ok "$ok" --arg kind "$kind" --arg msg "$msg" --argjson ms $(($(now_ms) - t0)) --argjson qa "$qa" '
    {id: "FULL", ok: $ok, ms: $ms, qa_scenarios: $qa} + (if $ok then {} else {kind: $kind, message: $msg} end)')"
  [ "$ok" = true ] || gate_fail_stop
}

# ---- run -------------------------------------------------------------------------------------
for g in "${PLAN[@]}"; do
  echo "verify.sh: $g" >&2
  "gate_$g"
  write_report false
done
