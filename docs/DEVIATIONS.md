# Deviations from SPEC.md

## Toolchain
- **compileSdk 37.1** (`compileSdk { version = release(37) { minorApiLevel = 1 } }`), not 37.0: Compose BOM 2026.11.01 (foundation/ui 1.13.0) requires compiling against 37.1. targetSdk stays 37; the emulator image stays `android-37.0;google_apis`.

## Build and test infrastructure
- **e2e-driver is a self-targeting `Instrumentation` APK, not an androidTest JUnit class.** The spec gives
  `am instrument -w -e class …Driver#tap -e res block_close …`. The driver is `:e2e-driver` (an application
  module), and its `<instrumentation android:targetPackage>` is the driver itself:
  `am instrument -w -e cmd tap|wait|text -e res <id> [-e text …] [-e timeout ms] dev.brainrotprevention.e2edriver/.DriverInstrumentation`.
  Why: running against its own package means `am instrument` never force-stops or restarts the app under test,
  and no JUnit runner is involved. Success is `result=OK` with code -1; failure is `result=FAIL` with code 0.
  The driver still sets `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` before using `UiDevice` (§14.4).
- **`ctl_import` over 100 KB** streams the file through `adb shell "run-as <pkg> sh -c 'cat > files/debug/seed.json'"`
  on stdin. It does not use `adb push` plus a copy, because `run-as` can't reliably read `/data/local/tmp`.
- **G0 forbidden-time-call grep uses `git grep --untracked`**, so files that aren't committed yet are checked too.
  `.gitignore`d paths such as `build/` stay excluded. The default real-time provider for the logger lives in
  `platform/…/time/SystemTimeAndroid.kt`, which matches the `SystemTime*.kt` exclusion.
- **G3 milestone filtering** uses `-e filter dev.brainrotprevention.testing.MilestoneFilter -e milestone M<n>`, and
  the no-grant class is selected by the `dev.brainrotprevention.testing.NoGrant` annotation (contract in
  `scripts/e2e/TAGS.md`). Below M3, G3 builds, installs and grants only, because §16 has no G3 tests before M3.
- **G4 without explicit ids runs the scenarios of every milestone up to the current one** (from the §16 table).
- **`grant.sh` installs before `step grant`.** The mark needs the app to be installed. The grants that depend
  on undeclared permissions are best-effort, so they don't abort M0–M2. `SKIP_SERVICE_WAIT=1` skips the
  "a11y service connected" assert while the service doesn't exist yet.
- **Gate caps on a cold run** (no Gradle daemon running) are 3× the usual 3×-budget cap, so the first build doesn't time out.
- **"ALLOW" in the scenarios** is asserted as "no BLOCK decision for the target within the window, and the
  target is on top". `decision` lines are only logged when the result changes (§14.3), so an ALLOW line isn't guaranteed.
- **S6 and S5 pin the policy clock** to Mon 2026-11-23 10:00 before launching, so a run near midnight can't
  reset the daily counters partway through.
- **S13** moves the pre-reboot `brp.jsonl` aside before `start_log --no-clear`. Otherwise `STEP=__all__` would
  also see pre-reboot `BLOCK` and `boot` lines.
- **S14** rebinds the notification listener (`disallow_listener` then `allow_listener`) before asserting
  `service name=listener connected`, so that the event lands in the scenario's own log.

## Open

- **`--full` extras not implemented yet:** the `brp28` AVD (API 28 image not installed) and its 300 s boot check,
  S12b (real `date` jump as root), S17 (`adb emu geo fix`), and the `dumpsys cpuinfo` accessibility-load
  measurement. `verify.sh --full` reports the `FULL` gate as failed with an "open:" message until these exist.

## Engine (core:model, core:engine)
- **DST gap (§5.3):** instants for a local time inside a spring-forward gap resolve to the transition instant (the "first valid instant"), not `ZonedDateTime.ofLocal`'s gap-length shift (02:30 → 03:00, not 03:30). This matches wall-time containment. Window *ends* inside a fall-back overlap use the later offset, so the end covers both copies of the repeated hour.
- **`Until.UnlockCondition`** carries `(method, endsAt?)` instead of free text; the UI renders the wording.
- **Reason combination (§6.2):** precedence UntilStopped > WhileConditions > Ongoing > UnlockCondition > latest At (the spec ranks only the first two against At).
- **Start now plus triggers:** when both `manualUntil` and the triggers hold, until = the later of the two (`At(max)`); if the triggers give WhileConditions/Ongoing, that wins.
- **ALL with Wi-Fi/location holding:** shown as WhileConditions (the conditions may end before the window).
- **Pending dayStartHour (§5.4):** applied (event `DayStartHourApplied`) at the first DAILY reset under the old hour; with no DAILY limits it applies at once. Switching e.g. 0→4 therefore yields one short period (00:00–04:00) the first night. The "clock went back" re-anchor also moves `warnedPeriodStart` so the warning isn't repeated.
- **LAUNCHES anti-bounce with a backward clock:** if policy time is before `lastLaunchAt`, the 30 s rule is treated as passed.
- **Not armed → armed while the item is in front** is not a new launch (keys are tracked regardless of armed), so the LAUNCHES limit never ends a running session.
- **Strict row edits** (method, options, PIN, cooldown, shortening TIMER) are refused whenever Strict Mode is ON, even without LOCK_EDITING; only TIMER extension and followAll false→true are allowed.
- **Tamper bookkeeping** (`lastCheck*`) is kept and skew-adjusted also while Strict Mode is INACTIVE, so the emergency 30-day window resists clock jumps between activations. `Engine.evaluate` never mutates the strict row; the platform calls `StrictPolicy.check` (every minute, TIME_SET, TIMEZONE_CHANGED, shutdown, boot).
- **Backup import:** web-rule values are normalised on import (a DOMAIN with a path keeps only the domain); duplicates after normalisation, duplicate SSIDs/packages, and more than 100 locations in total are rejected. Missing optional JSON fields take the editor defaults. Pref ranges (unspecified in §4): limitWarningMin 0–120, notifyBeforeStartMin 0–120, notifyBeforePauseEndMin 0–60. The export writes `pendingDayStartHour` (if set) as `dayStartHour`.
- **WeakenChecker:** adding another row of an existing trigger type (rows are OR'd) is allowed even under ALL; under ALLOWLIST+OPEN, web-rule edits are free (they have no effect until ONLY_LISTED, which is itself a strengthening).
