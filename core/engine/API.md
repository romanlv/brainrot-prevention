# core:model + core:engine API

Pure Kotlin (no Android). Packages: `dev.brainrotprevention.model`, `dev.brainrotprevention.engine`.
All instants are epoch ms (policy wall clock); "elapsed" is policy-elapsed ms. The engine never reads a clock:
pass a `ClockReading` (`PolicyClock.read()`).

## Model (`core:model`)
- Enums (serialised by name): `Mode`, `Combine`, `AllowlistWeb`, `LimitKind`, `Period`, `WebRuleKind`, `StrictMethod`,
  `StrictState` (stored INACTIVE/ON), `StrictDisplayState` (INACTIVE/ACTIVE/STANDBY), `LocationState`, `TriggerType`.
- `Weekdays` (MON=1 … SUN=64, `ALL`=127, `bit(day)`, `contains(mask, day)`, `of(...)`, `isValid`).
- `StrictOptions` (`LOCK_EDITING`=1, `BLOCK_SETTINGS`=2, `BLOCK_UNINSTALL`=4, `has`, `isValid` = uninstall needs settings).
- Rows (all `@Serializable`, mirror §4.1): `Schedule(id, name, icon, sortOrder, enabled, mode, combine, allowlistWeb,
  blockNotifications, blockUnsupportedBrowsers, pausedUntil, manualUntil, strictFollowed, createdAt, updatedAt,
  timeWindows: List<TimeWindow>, limits: List<UsageLimit>, wifi: List<WifiCondition>, locations: List<LocationCondition>,
  apps: List<String>, webRules: List<WebRule>)`; `Schedule.UNTIL_STOPPED = Long.MAX_VALUE`.
  `TimeWindow(id, days, startMin, endMin)`, `UsageLimit(id, kind, period, allowance)`, `WifiCondition(id, ssid)`,
  `LocationCondition(id, label, lat, lon, radiusM, inverted, lastState)`, `WebRule(id, kind, value, anywhereInUrl)`,
  `QuickBlock(active, until, blockNotifications, apps, webRules)`, `StrictModeRow(...)` (all §4.1 columns, Base64 pin hash/salt),
  `EmergencyUnblock(id, at)`, `UsageCounter(limitId, periodStart, used, lastLaunchAt, warnedPeriodStart)`, `Prefs(...)`.
- `BrowserEntry(packageName /*json "package"*/, urlBarIds: List<UrlBarId(id, kind, source, reliable)>, canEditUrl)` = `browsers.json`;
  `StrictGuards(settings, installer)` = `strict_guards.json` (defaults = §9.3 lists).
- `PackageDefaults`: built-in always-exempt packages/components and allowlist essentials; the engine unions them with the snapshot sets.
- `ConfigSnapshot` (serialisable → `files/snapshot.json`): `schedules, quickBlock, strict, emergencyLog, excludedBrowsers, prefs,
  browsers, strictGuards, exemptPackages, exemptComponents, allowlistEssentials, unsupportedBrowsers,
  webApps: Map<pkg, startUrl>, ownPackage, redirectUriPrefix`. The platform resolves launcher, IME, dialer, InCallService UIs,
  clock app, unsupported browsers (§7.7) and WebAPKs (§7.9). Pass the **same instance** until config changes (it is cached).
- `SensorState(ssid, locations: Map<rowId, LocationState>, chargerConnected)` (missing rows fall back to `lastState`).
- `ForegroundApp(packageName, component: "pkg/cls"?, secondary)`; `WebTarget(browserPackage, url, reliable, windowId)`.
- Clocks: `PolicyClock { now(); zone(); elapsed(); toSystemMillis(t); read() }`, `SystemTime { currentTimeMillis(); elapsedRealtime();
  uptimeMillis() }`. `SystemPolicyClock(systemTime)` (release), `OffsetPolicyClock(systemTime)` (debug: `advance`, `jump`, `setWall`,
  `real`, `wallOffset` to persist, `elapsedOffset` reset on boot). The Android `SystemTime` must live in a file named `SystemTime*.kt`
  (e.g. `SystemTimeAndroid.kt`) to pass the G0 grep.
- Backup (§4.3): `Backup`, `BackupSchedule`, `BackupTimeWindow{days,startMin,endMin}`, `BackupLimit`, `BackupLocation`,
  `BackupWebRule{kind,value,anywhereInUrl}`, `BackupQuickBlock`, `BackupPrefs`. Optional fields have defaults (seeds may omit them);
  unknown keys are errors.
  - `BackupCodec.encode(backup): String`, `decode(text): Backup` (throws `BackupFormatException` with a readable message).
  - `BackupCodec.fromConfig(schedules, quickBlock, excludedBrowsers, prefs, exportedAt): Backup` (stable ordering for S16).
  - `BackupCodec.toImportPlan(validBackup, now): ImportPlan(schedules /*ids 0, not paused/started*/, quickBlockApps,
    quickBlockWebRules, quickBlockNotifications, excludedBrowsers, prefs)` — import replaces all schedules and Quick Block items.

## Facade (`Engine`) — accessibility service, every 300 ms
```kotlin
val engine = Engine()   // one per process
val r: EvaluationResult = engine.evaluate(EvaluationInput(
    snapshot, clock = policyClock.read(), state = lastResult.state, sensors, foreground, webTargets,
    screenInteractive, keyguardLocked, urlBarMissing, adminActivationPending, countFullDelta /*debug*/))
```
- First call: `state = EngineState(LedgerState(counters = usageCounterRowsByLimitId))` — **seed counters from the DB**, or
  the first read treats them as new. Afterwards always feed back `r.state`. Pass `screenInteractive=false` on SCREEN_OFF (one call)
  so the screen-off launch rule and the "no TIME across screen-off" rule work.
- `r.apps: Map<pkg, Decision>`; `r.pages: List<PageDecision(target, url: NormalizedUrl, decision)>`; `r.anyBlock`.
- `Decision.Allow | Decision.Block(reasons: List<Reason>, until: Until)`; `Reason(source, limit: LimitKind?, until)`;
  `ReasonSource.Schedule(id) | QuickBlock | StrictSettings | StrictUninstall | UnsupportedBrowser(scheduleId?/*null=QB*/)`.
  `Until.At(instant) | WhileConditions(label) | UntilStopped | UnlockCondition(method, endsAt?) | Ongoing`.
  `Decision.Block.until` is already combined (§6.2); each reason chip has its own `until`.
- `r.ledgerEvents`: `LaunchCounted`, `LaunchBlocked`, `LimitWarning(scheduleId, limitId, timeLeftMs)` (post the heads-up once),
  `PeriodReset`, `DayStartHourApplied(hour)` (persist `dayStartHour=hour, pendingDayStartHour=null`).
- `r.countersToSave`: non-empty ⇒ upsert these `usage_counter` rows now (≥10 s, target change, launch, reset).
- `r.statuses: List<ScheduleStatus>` (schedules then Quick Block with id `QUICK_BLOCK_ID = -1`): `armed`, `blocking`, `paused`,
  `armUntil`, `blockUntil`, `time/launches: LimitStatus(limit, counter, periodEnd){exhausted,left}`, `error`.
- `r.summary: StatusSummary(blocking: List<id>, budgets: List<BudgetLeft(scheduleId, timeLeftMs?, launchesLeft?)>, strict)`
  — status notification ("2 schedules blocking", time/launches left for the current target).
- `r.cleanup: Cleanup(expiredPauses, expiredManual, quickBlockExpired, strictEnd: StrictEnd?)` — write nulls/`active=false`;
  on `strictEnd` (TIMER_EXPIRED / FOLLOWED_SET_EMPTY) save `StrictPolicy.deactivated(row)` and `removeActiveAdmin`.
- `r.nextInstant: PolicyInstant?` — earliest instant for the exact alarm / in-process timer. `r.errors`: schedule ids that threw
  (post "Blocking error, tap to fix"; BLOCKLIST fails closed, ALLOWLIST keeps its last result).
- `engine.status(snapshot, clock, counters, sensors): StatusView(statuses, strict, nextInstant)` — no ledger tick (UI, editor,
  alarm receiver, boot).
- `engine.notificationBlock(snapshot, statuses, pkg): Until?` — null ⇒ don't hide.

## Notifications (§10.4)
`NotificationPolicy.decide(NotificationFacts(pkg, isOwn, ongoing, noClear, foregroundService, category, hasFullScreenIntent),
block = engine.notificationBlock(...), systemNow, toSystemMillis = policyClock::toSystemMillis, snoozeChunkMs = 15 min)`
→ `NotificationAction.Leave | Snooze(durationMs)`. Re-run on re-post; count each Snooze.

## Alarms (§11.4)
`NextInstants.all(snapshot, now, zone, counting = emptyList()): List<PolicyInstant(at, kind, scheduleId?)>`, `next(...)`.
Kinds: WINDOW_START, WINDOW_END, PAUSE_END, PAUSE_END_WARNING, MANUAL_END, QUICK_BLOCK_END, STRICT_TIMER_END, DAILY_RESET,
HOURLY_RESET, BEFORE_START, LIMIT_WARNING. Schedule with `setExactAndAllowWhileIdle(RTC_WAKEUP, clock.toSystemMillis(at))`.

## Building blocks (also usable directly)
- `ScheduleEvaluator`: `contains(window, local)`, `triggerStatus(s, sensors, now, zone)`, `isArmed(...)`, `isPaused`,
  `windowEndOrOngoing(s, now, zone)` (8-day merge), `nextWindowStart/End`, `occurrences`.
- `Periods`: `dailyStart/End(now, zone, dayStartHour)`, `hourlyStart/End`, `periodStart/End`, `endOfToday` (pause "until end of
  today"), `resolveStart/End(local, zone)` (DST).
- `UsageLedger.step(LedgerState, LedgerInput): LedgerOutput(state, events, countersToSave)`; `UsageLedger.read(counter, limit, now,
  zone, dayStartHour)` (reset-on-read).
- `DecisionMaker.decideApp(DecisionContext, ForegroundApp)`, `decideWeb(ctx, WebTarget)`, `isExempt`.
- `UrlNormalizer.normalizeDetected(text): NormalizedUrl(text, scheme, hostAscii, hostUnicode, urlDecoded){hasHost,itemKey}`;
  `UrlMatcher.matches(rule, url)`, `matchesAny`.

## Editor / UI
- Rule input: `UrlNormalizer.normalizeRuleInput(kind, input, anywhereInUrl)` → `RuleInput.Ok(rule) | Choice(domain, keyword)`
  (URL with a path: offer "Block the whole site" vs "pages containing this text") `| Invalid(reason)`;
  `UrlNormalizer.suggest(input)`; `UrlNormalizer.bulkAdd(text, existing): BulkAddResult(added, duplicates, invalid)`.
- Validation: `ScheduleValidator.validate(schedule): ValidationError(path, message)?` (§4.1 ranges, no limits in ALLOWLIST,
  ALLOWLIST not strictFollowed, rules normalised & unique); `validateApps`, `validateRules`, `normalizeRule`.
- Import: `EditGuard.checkImport(strictRow)` first (refused while ON), then `BackupValidator.validateText(json)` →
  `BackupValidation.Valid(normalisedBackup) | Invalid(ValidationError /* first offending row, e.g. schedules[2].webRules[0] */)`.
- Strict lock before every save (`display` = `r.summary.strict` or `engine.status(...).strict`):
  `EditGuard.checkSchedule(row, display, schedules, old?, new?, now)`, `checkPause(…, s, until, now)`, `checkTurnOff`,
  `checkDelete`, `checkQuickBlock(row, display, schedules, old, new, now)`, `checkExcludedBrowsers(row, display, old, new)`,
  `checkPrefs(row, display, old, new)`, `checkStrict(oldRow, newRow)`, `canExcludeBrowserFromBlockScreen(row)`,
  `isLocked(row, display, schedules, scheduleId)`, `scope(row, display): LockScope(NONE/FOLLOWED/ALL)`.
  Result `EditCheck.Allowed | Refused(Refusal)` → show "Locked by Strict Mode". Pure table: `WeakenChecker.*`.
  Quick Block start with zero items is refused by the UI (`QuickBlock.isEmpty`).

## Strict Mode (`StrictPolicy`)
- `checkActivation(StrictActivation(method, options, durationMs?, pin?, cooldownMin?, followAllSchedules), StrictFacts(
  accessibilityConnected, usageAccessGranted, inAccessibilityShortcut, adminActive), schedules, currentRow): ActivationRefusal?`
- `activate(req, facts, schedules, currentRow, wallNow, elapsedNow): ActivationResult.Activated(row) | Refused(reason)`.
- `followed(row, schedules): FollowedSet`, `notCovered(schedules)` (ALLOWLIST ids), `pausedOrOff(schedules, now)`,
  `displayState(row, schedules, statusesById)`.
- Tamper (§9.6): call `check(row, emergencyLog, wallNow, elapsedNow): TamperCheck(row, emergencyLog, changed, skewApplied,
  bootDetected)` on TIME_SET, TIMEZONE_CHANGED, every minute, ACTION_SHUTDOWN and after boot (reboot is detected from elapsed
  going back) — **persist row and log**. Runs while INACTIVE too. `timerExpired`, `timerRemaining`, `extendTimer(row, extraMs)`.
- Unlock → `UnlockResult.Unlocked(row) | WrongPin(row, lockedUntil?) | Refused(UnlockRefusal, remainingMs?)`:
  `unlockWithCharger(row, plugged)`, `unlockWithPin(row, pin, wall, elapsed)` (PBKDF2-SHA256 120k; 5 failures → 5 min, doubling to
  1 h; persist the returned row), `unlockAfterCooldown(row, startedElapsed, nowElapsed)`, `turnOffFromStandby(row, display,
  startedElapsed, nowElapsed)` (fixed 5 min). Waits are on-screen elapsed waits; cancel = discard `startedElapsed`.
- Emergency (§9.7): `emergencyAvailableAt(log, wallNow): Long?` (null = available), `emergencyUnblock(row, log, wallNow,
  countdownStartedElapsed, elapsedNow): Done(row, entry) | NotAvailableUntil(at) | CountdownRunning(ms) | NotOn`.
- After any unlock: save the INACTIVE row, `removeActiveAdmin`.
