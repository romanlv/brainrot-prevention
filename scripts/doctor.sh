#!/usr/bin/env bash
# scripts/doctor.sh: check (and where safe, fix) the host environment (SPEC §14.1).
# Prints a JSON summary on stdout; exit 0 when every required check is ok.
# Never sends a command to a device: device listing is host-side only.
set -euo pipefail
LIB_NO_RESULT=1
OUT=${OUT:-$(git -C "$(dirname "$0")" rev-parse --show-toplevel)/build/doctor}
source "$(dirname "$0")/e2e/lib.sh"
trap - ERR
trap - EXIT
SCENARIO=doctor
mkdir -p "$OUT"
CHECKS="$OUT/checks.jsonl"
: >"$CHECKS"

check() { # check <name> <ok true|false> <detail> [required true|false]
  jq -cn --arg n "$1" --argjson ok "$2" --arg d "$3" --argjson req "${4:-true}" \
    '{name:$n, ok:$ok, required:$req, detail:$d}' >>"$CHECKS"
  [ "$2" = true ] || echo "doctor: $1: $3" >&2
}

# ---- tools ----------------------------------------------------------------------------------
for t in jq perl git; do
  if command -v "$t" >/dev/null 2>&1; then check "tool:$t" true "$(command -v "$t")"; else check "tool:$t" false "missing"; fi
done
[ "$(uname -m)" = arm64 ] && check cpu true "arm64 (arm64-v8a images only)" || check cpu false "expected arm64, got $(uname -m)"

# ---- JDK ------------------------------------------------------------------------------------
if [ -x "$JAVA_HOME/bin/java" ]; then
  jv=$("$JAVA_HOME/bin/java" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -n 1)
  case "$jv" in 25*) check jdk true "$jv at $JAVA_HOME" ;; *) check jdk false "expected 25.x, got $jv" ;; esac
else
  check jdk false "JAVA_HOME=$JAVA_HOME has no bin/java (brew install openjdk@25)"
fi

# ---- SDK ------------------------------------------------------------------------------------
SDKM=("$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "--sdk_root=$ANDROID_HOME")
need=()
[ -d "$ANDROID_HOME/platforms/android-37.0" ] || need+=("platforms;android-37.0")
[ -d "$ANDROID_HOME/build-tools/37.0.0" ] || need+=("build-tools;37.0.0")
[ -x "$ANDROID_HOME/platform-tools/adb" ] || need+=("platform-tools")
[ -x "$ANDROID_HOME/emulator/emulator" ] || need+=("emulator")
[ -d "$ANDROID_HOME/system-images/android-37.0/google_apis/arm64-v8a" ] || need+=("system-images;android-37.0;google_apis;arm64-v8a")
if [ ${#need[@]} -gt 0 ]; then
  echo "doctor: installing ${need[*]}" >&2
  yes 2>/dev/null | "${SDKM[@]}" --licenses >/dev/null 2>&1 || true
  "${SDKM[@]}" --install "${need[@]}" 2>&1 | grep -vi deprecated >&2 || true
fi
for p in platforms/android-37.0 build-tools/37.0.0 platform-tools/adb emulator/emulator system-images/android-37.0/google_apis/arm64-v8a; do
  if [ -e "$ANDROID_HOME/$p" ]; then check "sdk:$p" true "present"; else check "sdk:$p" false "missing under $ANDROID_HOME"; fi
done
[ -e "$ANDROID_HOME/platforms/android-37.1" ] && check "sdk:platforms/android-37.1" true "present (compileSdk 37.1, docs/DEVIATIONS.md)" ||
  check "sdk:platforms/android-37.1" false "missing; AGP installs it on the first build" false

# local.properties
printf 'sdk.dir=%s\n' "$ANDROID_HOME" >"$ROOT/local.properties"
check local.properties true "sdk.dir=$ANDROID_HOME"

# ---- adb shim -------------------------------------------------------------------------------
if [ "$(command -v adb)" = "$ROOT/scripts/bin/adb" ] && [ -x "$ROOT/scripts/bin/adb" ]; then
  check adb-shim true "first on PATH: scripts/bin/adb -> $ANDROID_HOME/platform-tools/adb"
else
  check adb-shim false "scripts/bin/adb is not first on PATH ($(command -v adb))"
fi
# host-side listing only; physical devices are ignored and refused by the shim
others=$(ADB_TIMEOUT=10 adb devices 2>/dev/null | awk 'NR>1 && NF>=2 && $1 !~ /^emulator-/ {print $1}' | tr '\n' ' ' || true)
if [ -n "$others" ]; then
  check physical-devices true "attached and ignored (refused by the shim): $others" false
else
  check physical-devices true "none" false
fi

# ---- emulator acceleration, disk ------------------------------------------------------------
if acc=$(with_timeout 60 "$ANDROID_HOME/emulator/emulator" -accel-check 2>&1); then
  check accel true "$(printf '%s' "$acc" | grep -iE 'accel|hvf|usable' | head -n 2 | tr '\n' ' ')"
else
  check accel false "$(printf '%s' "$acc" | tail -n 2 | tr '\n' ' ')"
fi
free_gb=$(df -g "$ROOT" | awk 'NR==2 {print $4}')
if [ "${free_gb:-0}" -ge 15 ]; then check disk true "${free_gb} GB free"; else check disk false "only ${free_gb} GB free (need 15)"; fi

# ---- git ------------------------------------------------------------------------------------
if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  check git true "repo at $ROOT ($(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo 'no commits'))"
else
  check git false "$ROOT is not a git repository"
fi

# ---- Gradle / AGP sanity --------------------------------------------------------------------
glog="$OUT/gradle-help.log"
grc=0
(cd "$ROOT" && with_timeout 600 ./gradlew --console=plain -q :app:help) >"$glog" 2>&1 || grc=$?
if [ "$grc" != 0 ] && grep -q "Failed to find target" "$glog"; then
  pkg=$(sed -n "s/.*Failed to find target with hash string '\([^']*\)'.*/\1/p" "$glog" | head -n 1)
  [ -n "$pkg" ] && case "$pkg" in android-*) pkg="platforms;$pkg" ;; esac
  if [ -n "$pkg" ]; then
    echo "doctor: installing $pkg (requested by AGP)" >&2
    "${SDKM[@]}" --install "$pkg" 2>&1 | grep -vi deprecated >&2 || true
    grc=0
    (cd "$ROOT" && with_timeout 600 ./gradlew --console=plain -q :app:help) >"$glog" 2>&1 || grc=$?
  fi
fi
if [ "$grc" = 0 ]; then check gradle true ":app:help ok"; else check gradle false "$(tail -n 5 "$glog" | tr '\n' ' ')"; fi
# resolved KGP version ("2.2.10 -> 2.4.21" means 2.4.21)
kgp=$(cd "$ROOT" && with_timeout 300 ./gradlew --console=plain -q buildEnvironment 2>/dev/null |
  grep -E 'org\.jetbrains\.kotlin:kotlin-gradle-plugin:[0-9]' | head -n 1 |
  sed -E 's/.*kotlin-gradle-plugin:([0-9.]+)( -> ([0-9.]+))?.*/kotlin-gradle-plugin:\1\2/; s/:[0-9.]+ -> /:/' || true)
case "$kgp" in *2.4.21*) check kotlin-gradle-plugin true "$kgp" ;; *) check kotlin-gradle-plugin false "expected 2.4.21, got '${kgp:-none}'" ;; esac

# ---- summary --------------------------------------------------------------------------------
jq -s '{ok: (map(select(.required and (.ok | not))) | length == 0), checks: .}' "$CHECKS" | tee "$OUT/doctor.json"
jq -e .ok "$OUT/doctor.json" >/dev/null
