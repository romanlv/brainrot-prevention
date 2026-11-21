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
- **The milestone lives in `scripts/milestone` (tracked), not `build/verify/milestone`:** a gitignored file vanished on a
  fresh clone or `rm -rf build`, and the old `|| echo M0` fallback then turned `--device` into a green run of 0 tests and
  0 scenarios. A missing or malformed file now fails `verify.sh` (gate `milestone`) and an id-less `run.sh`.
- **G3 verdict (review round 1, harness 16/17):** before the real passes, G3 dry-runs (`-e log true`) both passes with
  the milestone filter and once without it; the gate reports `planned`, `planned_nogrant`, `planned_granted` and
  `filtered_out` (the tests the milestone filter drops). A pass counts only when `am instrument` exited 0 (142 = timeout,
  97 = adb shim refusal = infra), the output ends with `INSTRUMENTATION_CODE: -1` and an `OK (N tests)` / `Tests run: N`
  line whose N equals the started tests, every started test has a terminal status, and the started tests are exactly the
  planned ones. From M3 the granted pass must plan at least one test. G4 reports `milestone`, `planned` and
  `passed_count`, and fails when run.sh ran other scenarios than it planned or planned none at M3+.
- **G0 never re-creates `app/lint-baseline.xml` on its own:** a missing baseline fails G0 unless `--new-lint-baseline`
  is passed (otherwise the new baseline would absorb every current issue).
- **Negative scenario assertions** (`assert_no_event`) fail as infra when jq fails (a broken filter); lines that aren't
  JSON objects are skipped (`select(type == "object")`) instead of stopping jq.
- **Teardown:** a failed `ctl reset` after a scenario triggers `emu.sh reset` + `grant.sh`; the result records
  `teardown_failed`, and the scenario fails (infra) when that recovery fails too.
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

## Platform (platform, app hooks) and test harness, M3–M8
- **`am instrument` force-stops the app, and Android 17 then lists our accessibility service under "Crashed
  services" and does not rebind it** (`dumpsys accessibility`: `Bound services:{}`, `Crashed services:{…}`).
  The G3 tests rebind it by rewriting `enabled_accessibility_services` as the shell (`ServiceRebind`, with
  UiAutomation obtained with `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` — otherwise UiAutomation itself suppresses
  the service). `verify.sh` re-runs `grant.sh` (no install) after the G3 instrumentation so G4 starts connected,
  and `grant.sh` deletes the setting before writing it, so a reinstall (which rebinds the service before the log
  collector starts) still produces a `service connected` line after the `grant` mark.
- **`ungrant.sh` sets `GET_USAGE_STATS` to `ignore`, not `default`:** the app is installed with `-g`, which grants
  `PACKAGE_USAGE_STATS`, and with app-op mode `default` the system falls back to that permission, so usage access
  stays granted.
- **`scenario_begin` goes home and force-stops the three fixtures before `reset`.** Otherwise a fixture left in
  front by the previous scenario (S5 ends with T on top) is already in the foreground set when S6 starts, so its
  first launch is (correctly, §5.4) not a *new* launch and S6 counts one launch too few.
- **e2e-driver reads the matched object's resource name before tapping.** Tapping `block_close` removes the
  overlay window, and reading it afterwards threw `StaleObjectException`.
- **`strict-start` accepts a TIMER shorter than 15 min** (S11 uses 10 min). The command checks every §9.2
  precondition; the 15 min – 30 days range is the editor's TIMER range (§9.5). The debug path activates with the
  minimum and then sets `endsAt`/`endsAtElapsed` to the requested duration. The UI path keeps the range.
- **Alarm catch-up on every evaluation:** each evaluation first fires (`alarm_fired`, plus the PAUSE_END /
  BEFORE_START notices) every policy instant that passed since the previous evaluation, in chronological order
  (`NextInstants.all` from the last check). The same path serves the exact alarm, the in-process timer, the
  300 ms poll and the clock commands, so a passed instant is reported exactly once whichever wakes the engine first.
  The clock commands run tamper check → catch-up → evaluate → reschedule (reschedule after the evaluation, which
  has the same effect as the §14.3 order).
- **`alarm` is logged on every change of the scheduled alarm, including `next:null`** (nothing to schedule), and
  always once per process start.
- **`decision` BLOCK lines** are written when the result for a target changes and on every enforcement (after
  the 1.5 s debounce), not on every 300 ms evaluation.
- **`BlankPageProvider` is a `FileProvider`** over `files/blank/blank.html`, copied from the bundled asset when the
  provider starts; the redirect URI is `content://<applicationId>.blank/blank/blank.html`.
- **Status notification** is posted only while the accessibility service is connected (it is "posted by the
  service", §3 rule 2) and cancelled on disconnect.
- **The debug `import` command marks onboarding done** after a successful import (a seeded device counts as set
  up), so `reset` + seed opens Home rather than onboarding in S7 and S11. The backup format itself still carries
  no onboarding state.
- **S7 and S11 launch Home with `-f 0x10008000` (NEW_TASK|CLEAR_TASK).** Our app can't be force-stopped between
  repeats (that unbinds the accessibility service), so a plain `am start` resumed the previous repeat's activity
  with the "Locked by Strict Mode" dialog still open over the card menu.

- **Settings guard list (§9.3)** also has `com.samsung.accessibility` (One UI accessibility settings, where installed
  services are turned off) and `com.huawei.systemmanager`. Not verified on those devices (emulator only).
- **Settings/installer guard:** re-enforces after 700 ms (RECENTS, 400 ms, HOME, one poll) instead of the 1.5 s debounce,
  and the block screen's Close does nothing for the first 1.5 s of a guard block, so Settings can't be reopened from
  Recents inside the debounce window.
- **Picture-in-picture (§10.2 step 3):** a blocked package whose only window is PiP is paused and covered, but gets no HOME,
  no full-screen block and doesn't count toward the loop rule (HOME can't remove a PiP window, so the old path re-showed
  the block every 1.5 s and never let go). The cover is `FLAG_NOT_TOUCHABLE`: touches reach the PiP window, so its close
  button works; expanding it makes it a full window, which is enforced normally. The 10 s Pause timer is keyed on the
  blocked package, not on every re-show.
- **Media re-pause** follows "the package stays blocked" (`EngineHost.isBlockedInBackground`: the decision the package would
  get in front, without LAUNCHES gates and guards), not "the package is in front"; callbacks are dropped on service stop.
- **URL-bar rewrite** is reported as `URL_REWRITE` only when the bar shows the blank page within 1 s; otherwise the intent
  redirect runs. The SET_TEXT content stays the redirect URI (§7.6 step 1.3 and S8 require the `content://` page; the
  sentence "about:blank is used only as SET_TEXT content" contradicts them).
- **Health (§11.6):** "Blocking is off" is also posted when the service is enabled but has not been bound for 30 s (a
  crashed service is not rebound), and the check also runs every minute while the service is unbound and on every
  alarm. Background location is required (and the geofence registration problem shown) when a location trigger exists.
- **Default launcher** is re-resolved at most once a second during evaluations (a change sends no broadcast).
- **Notification sweep** (§10.4) runs when the set of schedules hiding notifications changes, or when the config snapshot
  changes while something hides notifications (an app added to a running Quick Block, review round 2 diff major 1); "N held
  today" counts each notification key once per day.
- **Accessibility shortcut check (§9.2)** reads every shortcut key present on API 37 (framework.jar strings):
  `accessibility_shortcut_target_service`, `_button_targets`, `_qs_targets`, `_gesture_targets` (2/3-finger swipe up),
  `_key_gesture_targets` (keyboard), `_top_row_key_targets` and `_quick_access_targets`. It is checked at activation only.
- **Browser windows (§7.8):** `lastUrl`, the throttle and the rewrite target are per (browser, window); every application
  window of a browser is read before each evaluation and every URL-bar match is tried. The 10 s `url_bar_missing` clock is
  per browser and survives the browser leaving the foreground for 60 s, so relaunching a web app that the browser draws
  without a URL bar doesn't restart it (review round 2, adversarial 3 and 5).
- **Allowlist essentials (§6.3)** also cover the Android 14+ system screens other apps open, checked on the API 37 image:
  the share sheet (`com.android.intentresolver`), the photo picker (`com.google.android.providers.media.module`,
  `com.google.android.photopicker`, and the AOSP names), Credential Manager (`com.android.credentialmanager`) and the
  captive-portal login; plus the packages `ACTION_CHOOSER`, `ACTION_PICK_IMAGES` and `CAPTIVE_PORTAL` resolve to. From Play
  Services only the account picker, Google sign-in and passkey/password sheets are essential, by component (not the whole
  `com.google.android.gms` package).
- **Home "Blocking is off"** banner (with Fix) replaces the red "Blocking now" headline ("Would be blocking now: …") when a
  required grant is missing or the accessibility service isn't connected; the displayed statuses stay the UI's own.
- **Poll loop** sleeps (no 300 ms wakeups) while the screen is off or nothing is armed, until SCREEN_ON or an evaluation.

## Open
- **Two windows of one browser on a device (review round 2, adversarial 3):** the reader, engine and enforcer handle
  every window (engine test `Round2RegressionTest.everyBrowserWindowIsJudged`), but there is no G3/scenario for it: the
  fixture browser is `singleTask` (one window), and a same-package split screen needs a multi-instance fixture activity
  plus a scripted split-screen launch on the phone emulator. Which split stage `getWindows()` lists first was not checked.
- **WebAPK / TWA drawn by Chrome (adversarial 5):** a Chrome window without a URL bar is judged only through the 10 s
  `url_bar_missing` rule (now not reset on relaunch, 60 s grace). Detecting the WebAPK that owns Chrome's task (to block
  at once, or as the listed app) was not done: minting a WebAPK needs Play Services' WebAPK server, which the emulator
  image can't reach offline, so the task/component shape couldn't be verified.
- **Shortcut assigned while Strict Mode is ON without BLOCK_SETTINGS (adversarial 2, second part):** accepted by §17;
  no health notice is posted for it.
- **External displays / desktop windows** (review round 1, platform minor 1): the foreground set uses `getWindows()` (default
  display) and HOME/overlay act on the default display only. Supporting other displays needs `getWindowsOnAllDisplays()`
  plus a per-display overlay window and a way to close an app window on another display (there is no per-display HOME);
  not done in v1.
- **Location fallback without Play Services (§11.5)** is not implemented: location triggers need the Play Services
  geofencing API.

- **`pm archive` under an active device admin (§9.3) is not settled:** on the emulator `pm archive
  dev.brainrotprevention.debug` fails with `NameNotFoundException: No installer found` (the app was installed with
  `adb install`, so there is no installer to archive it), before any device-policy check. Archive UI is therefore not
  added to the installer guard.
- **Re-alert of re-posted snoozed notifications (§10.4)** is not verified; `notif_realert` is not logged.

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
- **Clock back then forward (§5.4 [PL-M3]):** the re-anchor keeps `used` *and* stores `holdUntil` (the end of the period
  that `used` belongs to). Until policy time reaches it, a later period change re-anchors again instead of resetting, so
  moving the clock back and then forward never refills a budget. Cost: a clock that stays wrong delays the next real
  reset by up to the size of the jump. `usage_counter` also stores `period`; a different period resets the counter (the
  spec's "changing `period` resets the counter", which the re-anchor rule used to swallow for HOURLY→DAILY).
- **Restart and LAUNCHES (§5.4):** after a process start or an accessibility-service (re)connect the platform seeds
  `LedgerState(previousKeysKnown = false)`, and the first tick takes the items in front as running sessions (not counted,
  not gated). An app launched in the same instant as the restart is therefore not counted.
- **Ledger carry-over:** counters of limits not processed in a tick (a schedule whose evaluation threw, an empty fallback
  snapshot) are kept unchanged instead of being dropped and later rebuilt at 0.
- **Tamper check (§9.6):** a reboot is also detected from `Settings.Global.BOOT_COUNT` (stored as `strict_mode.bootCount`),
  not only from elapsed time going back, so a short previous uptime can't make the time the device was off look like a
  forward jump. A skew of 60 s or less is not applied but carried in `lastCheckWall` (it moves by the elapsed time only),
  so repeated small jumps add up and are applied once they pass 60 s.
- **Strict row edits** are compared column by column: only `endsAt`/`endsAtElapsed` (TIMER, extend only) and
  `followAllSchedules` (false→true) may differ; PIN lockout, tamper bookkeeping, `activatedAt` and `bootCount` can't be
  edited while ON. `StrictRepository.extendTimer` reads the row inside the write lock and returns `NotFound` when no TIMER
  is running (it ended between the tap and the write).
- **LOCK_EDITING in STANDBY:** "these rules apply to followed schedules only" is read as covering the global settings
  that weaken followed schedules too: adding an excluded browser and changing `dayStartHour` are refused in STANDBY as
  well as ACTIVE (the block-screen "Not a browser? Allow" is refused whenever ON anyway).
- **Anywhere-in-URL keywords** also match the host (Unicode and ASCII), so widening a host keyword never unblocks a site
  (an IDN host such as `xn--bcher-kva.de` matched `bücher` only through `hostUnicode`).
- **Coverage bitmap with no windows on either side:** the [V2-N7] no-window rule is skipped when neither version has a
  time window (the combine and trigger-row checks decide); ALL→ANY with only Wi-Fi or only location rows is allowed.
- **URL normalisation:** the percent-decoded text (and a decoded Samsung refer target) is NFC-normalised and lower-cased
  again; trailing dots are stripped after IDN mapping too (`facebook.com。` → `facebook.com`).
- **Exempt components:** the credential prompt is `com.android.settings/.password.ConfirmDeviceCredentialActivity`
  (plus its `$InternalActivity`), and `ADD_DEVICE_ADMIN` resolves to
  `.applications.specialaccess.deviceadmin.DeviceAdminAdd` (both checked with `cmd package query-activities` on the API 37
  emulator); the names in §9.3 without the `.password.`/`.applications…` packages don't exist on API 28+.
- **Strict SCHEDULES lock-in (review round 2, adversarial 7):** activation with LOCK_EDITING is refused
  (`UNBOUNDED_FOLLOWED`) while a followed item blocks with no end of its own: Quick Block without a timer, Start now
  "until I stop it", or an enabled blocklist schedule whose triggers always hold (no triggers, or time windows covering the
  whole week when time alone arms it) and that has no budget. While SCHEDULES + LOCK_EDITING is ON, starting such an item on
  a followed schedule / Quick Block (followAll) is refused (`UNBOUNDED_UNDER_STRICT`): Quick Block "No timer", Start now
  "until I stop it", turning on or creating an always-on schedule, removing all windows. Without LOCK_EDITING the user can
  still stop the item, so nothing is refused. Spec §9.5 doesn't foresee this; without it Strict Mode never reaches STANDBY.
- **TIMER extension** is capped so the end stays within 30 days of now (§9.5 range); the UI asks before extending.
- **Proxy URLs (adversarial 4):** DOMAIN rules and host keywords also match the original host of a Google Translate proxy
  (`<host-with-dashes>.translate.goog`), an AMP cache URL (`<host>.cdn.ampproject.org`, `cdn.ampproject.org/c|v|i/[s/]<host>/…`)
  and Google's AMP viewer (`google.<tld>/amp/[s/]<host>/…`). The subdomain encoding is `-` for `.` and `--` for `-`;
  punycode hosts in that encoding are not decoded.
- **Web rules per schedule (adversarial 1):** at most 2,000 websites and keywords per schedule and in Quick Block, enforced
  by `ScheduleValidator` (save, import before parsing) and by bulk add (lines past the cap are counted, not parsed). §4.1
  sets no limit; the cap keeps a pasted hosts file from freezing the one process that also hosts the accessibility service.
  The service is not moved to its own process (DataStore and Room are single-process here).
- **WeakenChecker:** adding another row of an existing trigger type (rows are OR'd) is allowed even under ALL; under ALLOWLIST+OPEN, web-rule edits are free (they have no effect until ONLY_LISTED, which is itself a strengthening).

## Data layer (core:data)
- **Schema v2** (auto-migration from v1): `usage_counter.period`, `usage_counter.holdUntil`, `strict_mode.bootCount`, all
  nullable. `QuickBlockRepository.markExpired(now)` is conditional (`until <= now`), and `save()` refuses an active Quick
  Block without items, like `start()`.
- **Import is two writes:** schedules, Quick Block items and excluded browsers are replaced in one Room transaction; the
  prefs (DataStore) are written right after the commit. The whole file is validated before either write, so only an
  I/O failure could split them.
- **Import applies `dayStartHour` directly** (pending cleared) instead of going through `pendingDayStartHour`: every
  usage counter is deleted with the replaced schedules, so there is no running period to protect. Import keeps Quick
  Block `active`/`until` (not part of the backup) and only replaces its items and `blockNotifications`.
- **Upsert keys (§4 "natural key"):** limits match by `kind`, Wi-Fi by `ssid`, apps by package, web rules by
  `(kind, value)` — the editor's row id is ignored for these, so a counter can never move to a limit of another kind.
  Time windows and locations (no unique key in §4.1) match by id first, then by all their values.
- **EditGuard's displayed strict state** is computed in the data layer (`StrictGate`: `Engine.status` on the current
  snapshot and counters, with platform-installed sensors or the engine host's last evaluation as an override), so UI and
  debug commands get the same refusal.
- **`state.json`** is `{config: ConfigSnapshot, counters, strict, …}`; later milestones add keys via `StateContributors`.

## UI (app `ui/**`)
- **Platform seam:** the UI talks to the platform only through `ui/platform/Ports.kt` (Engine/Health/Apps/Strict ports);
  `PlatformPorts.of(platform)` is the single adapter, tests pass fakes plus `LocalStrictPort` (StrictPolicy + core:data).
  The "current location" button reads `LocationManager.getLastKnownLocation` in that adapter (TODO: no platform entry point
  yet). The Wi-Fi suggestion is `Sensors.state().ssid`.
- **Displayed status** (state pills, "Blocking now", Strict ACTIVE/STANDBY, `locked_by_strict` unlock text) is
  `Engine.status(snapshot, clock, counters, sensors)` recomputed every second in the UI (one shared upstream), the same
  computation as `StrictGate`, rather than `EngineHost.status` — so it also works while the service is off.
- **Allowlist and budgets (§5.4 "the editor refuses them"):** switching a schedule with a budget to "Allow only selected"
  asks first; confirming removes the budget (and `strictFollowed`, §6.3), cancelling keeps BLOCKLIST. The Budget section is
  hidden in allowlist mode, so an allowlist schedule can't get a limit from the UI; the repository still rejects one.
- **Empty blocklist:** saving a BLOCKLIST schedule with no apps and no sites is refused by the editor ("add at least one app
  or site"); the spec doesn't forbid it, but it would block nothing.
- **Quick Block timer chips** are No timer, 25 min, 1 h, 2 h, Custom (the spec lists the last four; "no timer" maps to
  `until = null`, which §5.5 allows).
- **Pause sheet:** the "used recently" presets skip 15 min and 1 h (already fixed options). Custom pause and custom Quick
  Block durations are 1 min – 24 h; "until a date and time" takes any future instant. Times are shown in 24-hour format.
- **Strict tab, Schedules method:** "Follow all schedules" is on by default; with it off, the per-schedule switches write
  `strictFollowed` immediately (allowed while INACTIVE). Timer presets are 15 min, 1 h, 4 h, 1 day, 7 days plus a stepper
  (15 min steps below a day, 6 h steps above, 15 min – 30 days).
- **Device admin** has no Fix row in Settings or onboarding: it is only added by Strict activation (§9.2) and removed by the
  platform after unlock.
- **Runtime permissions** (notifications, location) are requested once from the row's button; afterwards the button opens
  the platform's fix screen (App info), because Android stops showing the prompt after a second denial.
- **testTagsAsResourceId** is set on debug and qa builds (`BUILD_TYPE != "release"`) on the activity root and on every
  dialog, dropdown menu and bottom sheet (each is its own window), so the e2e driver sees `menu_pause`, `pause_option_15m`
  and `locked_by_strict`.
- **G3 helpers** (`@Milestone`, `@NoGrant`, `MilestoneFilter`) live in `testing/fixtures` (main source set, consumed as an
  `androidTestImplementation`). Tests without `@Milestone` are dropped when a milestone filter is given. UI tests are tagged
  M4 (editor, pause, allowlist), M8 (Strict) and M9 (onboarding, SAF backup); `OnboardingNoGrantTest` is the `@NoGrant` class.
- **Editor saved state (review round 2, diff minor 3/4/5):** the editor's Save takes `enabled`, `pausedUntil`,
  `manualUntil` and location `lastState` from the current row (`ScheduleRepository.save(s, keepRuntime = true)`), so a draft
  restored after process death can't revert them. All editor keys together stay under 64 KB of Parcel bytes (UTF-16); an
  existing schedule's starting point is re-read from the database instead of being stored; draft writes are debounced
  (250 ms). The website field is Compose snapshot state written synchronously. The bulk-paste dialog keeps at most 64,000
  characters; bulk and CSV parsing run on `Dispatchers.Default`; the rule list shows 50 rows at a time ("Show more").
- **Start now on an Off schedule** says it stays on (and can't be turned off while Strict Mode locks editing).
- **Strict form, non-SCHEDULES methods:** "Allow only selected" schedules are listed as "locked too" (with "Settings is blocked
  while they run" under BLOCK_SETTINGS) instead of "Not covered".
- **UI text** is in Kotlin (English only in v1); `strings.xml` holds the app name.

