# core:data API

Android library, package `dev.brainrotprevention.data` (Room entities/DAOs in `.data.db`). Uses the `core:model`
types; enforcement of the Strict lock uses `core:engine` (`EditGuard`, `ScheduleValidator`, `BackupValidator`).
All mutations are `suspend` and safe to call from any dispatcher. Flows are cold, `distinctUntilChanged`, and
emit the current DB state first.

## Graph (manual DI)
```kotlin
val graph = DataGraph.create(context, policyClock)         // once per process (Application.onCreate)
val test  = DataGraph.inMemory(context, clock, tmpDir)      // Robolectric: in-memory Room, prefs + cache in tmpDir
```
In the app: `BrpApp.graph`, or `context.dataGraph` (`dev.brainrotprevention.dataGraph`). `BrpApp` passes a
`SystemPolicyClock`; the platform swaps in `OffsetPolicyClock` for debug/qa when the clock commands land.
Members: `db: BrpDatabase`, `clock`, `prefsStore`, `snapshots: SnapshotStore`, `strictGate: StrictGate`,
`schedules`, `quickBlock`, `strict`, `settings`, `counters`, `pausePresets`, `notificationCounts`, `backup`,
`suspend wipe()` (debug reset), `close()`. Files: `databases/brp.db`, `files/datastore/brp.preferences_pb`,
`files/snapshot.json`.

## WriteResult (every refusable mutation)
```kotlin
sealed interface WriteResult<out T> {
  Ok(value: T); Locked(refusal: engine.Refusal); Invalid(error: engine.ValidationError /* path = first bad row */);
  NotFound(what: String)
  val isOk; fun describe(): String
}
```
`Locked` = "Locked by Strict Mode" (UI tag `locked_by_strict`). The guard runs inside the repository, under one
process-wide write lock, against the current DB state and the displayed strict state from `StrictGate`.
Nothing is written when the result is not `Ok`.

## StrictGate (displayed strict state for the guard)
`suspend display(snapshot): StrictDisplayState` — INACTIVE when OFF; else `displayOverride()` if non-null; else
`Engine().status(snapshot, clock.read(), counters, sensors()).strict`.
`var sensors: () -> SensorState` (default empty; install the live sensor state) and
`var displayOverride: () -> StrictDisplayState?` (e.g. `{ lastResult?.summary?.strict }` from the engine host).

## ScheduleRepository (`graph.schedules`)
- `schedules: Flow<List<Schedule>>` (sortOrder, id; children included), `schedule(id): Flow<Schedule?>`,
  `suspend getAll()`, `suspend get(id): Schedule?`
- `save(s: Schedule): WriteResult<Long>` — id 0 creates (createdAt=updatedAt=now), else edits (createdAt kept).
  Web rules are normalised (`ScheduleValidator.normalizeRule`), then `ScheduleValidator.validate`, ≤100 locations
  in total, then `EditGuard.checkSchedule`. **Child rows are upserted in place**: limits by `kind`, Wi-Fi by
  `ssid`, apps by package, web rules by `(kind, value)`, windows and locations by id, then by value. Unmatched
  old rows are deleted. So a usage counter survives any edit that keeps its limit kind (PL-M2); removing the
  limit deletes its counter (FK cascade). `pausedUntil`/`manualUntil` are written from `s` as well, except with
  `save(s, keepRuntime = true)` (the editor): then `enabled`, `pausedUntil`, `manualUntil` and location `lastState` come
  from the current row.
- `delete(id)` (checkDelete), `pause(id, until)` (checkPause), `resume(id)`, `setEnabled(id, enabled)` (off also
  clears the pause; checkTurnOff), `startNow(id, until /* Schedule.UNTIL_STOPPED */)`, `stopNow(id)` (MANUAL_SHORTENED
  while locked) — all `WriteResult<Unit>`.
- Unguarded: `reorder(ids)`, `applyCleanup(cleanup: engine.Cleanup, now)` (clears only values ≤ now),
  `setLocationState(rowId, LocationState)`.

## QuickBlockRepository (`graph.quickBlock`)
- `quickBlock: Flow<QuickBlock>`, `suspend get()`.
- `save(new: QuickBlock): WriteResult<Unit>` (items + flags + active/until; validated; `EditGuard.checkQuickBlock`).
- `start(until: Long?)` (null = no timer; `Invalid` if no items), `stop()` (QUICK_BLOCK_STOPPED while locked).
- Unguarded: `markExpired(now = clock.now())` for `cleanup.quickBlockExpired` (only if the stored `until <= now`).
  `save` refuses `active = true` with no items (`Invalid`).

## StrictRepository (`graph.strict`)
- `strict: Flow<StrictModeRow>` (default INACTIVE row when none), `emergencyLog: Flow<List<EmergencyUnblock>>`,
  `suspend get()`, `suspend getEmergencyLog()`.
- `edit(new): WriteResult<Unit>` — user edits (extend TIMER, followAll false→true); `EditGuard.checkStrict`.
- `extendTimer(extraMs): WriteResult<Unit>` — reads the row under the write lock; `NotFound` when no TIMER is running,
  `Invalid` when the end would be more than 30 days from now.
- Unguarded, for rows produced by `StrictPolicy`: `writePolicyResult(row)` (activate, unlock*, wrong-PIN,
  `deactivated` on `cleanup.strictEnd`, `afterBoot`), `writeTamperCheck(t: TamperCheck)` (row + whole log, no-op
  if `!t.changed`), `writeEmergency(row, entry): Long` (row + new log entry, one transaction).

## SettingsRepository (`graph.settings`)
- `prefs: Flow<Prefs>`, `excludedBrowsers: Flow<Set<String>>`, `lastPackageSnapshot: Flow<Set<String>>`,
  `suspend getPrefs()`, `suspend getExcludedBrowsers()`.
- `savePrefs(new: Prefs): WriteResult<Unit>` — pass the wanted day start in `new.dayStartHour`; a change is
  stored as `pendingDayStartHour` (§5.4); ranges as `BackupValidator.validatePrefs`; `EditGuard.checkPrefs`
  (SETTING_LOCKED); `onboardingDone` is not changed here.
- `applyDayStartHour(hour)` (engine event `DayStartHourApplied`), `setOnboardingDone(b)`,
  `setLastPackageSnapshot(set)` — unguarded.
- `setExcludedBrowsers(set): WriteResult<Unit>` (adding = EXCLUDED_BROWSER_ADDED while ON with LOCK_EDITING, ACTIVE or
  STANDBY; `savePrefs` likewise refuses a `dayStartHour` change).
- `excludeFromBlockScreen(pkg): WriteResult<Unit>` — "Not a browser? Allow"; `Locked(EXCLUDED_BROWSER_ADDED)`
  whenever Strict Mode is ON (§7.7).

## UsageCounterStore (`graph.counters`) — engine ledger persistence
```kotlin
val first = EngineState(LedgerState(counters = graph.counters.loadAll()))   // Map<limitId, UsageCounter>
if (r.countersToSave.isNotEmpty()) graph.counters.save(r.countersToSave)    // upsert; rows of deleted limits dropped
```
`counters: Flow<Map<Long, UsageCounter>>` for the UI. Counters are not in the snapshot (no rebuild on save).

## Small stores
- `pausePresets`: `presets: Flow<List<PausePreset(id, durationMin, lastUsedAt)>>` (newest first),
  `getAll()`, `recordUse(durationMin, at)` (keeps the last 3 distinct durations).
- `notificationCounts`: `countsFor(day "yyyy-MM-dd"): Flow<Map<pkg, Int>>`, `get(pkg, day)`,
  `increment(pkg, day)`, `pruneBefore(day)`.

## BackupRepository (`graph.backup`)
- `suspend export(): Backup`, `suspend exportText(): String` (`BackupCodec.fromConfig`, exportedAt = clock.now()).
  The format holds no counters, pause/Start now, Quick Block active/until, strict row, emergency log, presets,
  notification counts or onboarding state.
- `import(text): WriteResult<ImportSummary(schedules, quickBlockApps, quickBlockWebRules)>`:
  `Locked(IMPORT_LOCKED)` while Strict is ON → `Invalid(first offending row)` for a bad file (nothing written)
  → one Room transaction replaces every schedule (and so every counter), the Quick Block items and flag
  (active/until kept), and the excluded browsers → then prefs (dayStartHour applied directly, pending cleared).

## SnapshotStore (`graph.snapshots`) — engine input (§3 rule 4)
- `data class PlatformFacts(browsers, strictGuards, exemptPackages, exemptComponents, allowlistEssentials,
  unsupportedBrowsers, webApps, ownPackage, redirectUriPrefix)`; `PlatformFacts.of(snapshot)`.
- `snapshot: StateFlow<ConfigSnapshot?>` (null until first load). A **new instance only when content changes**,
  so pass `snapshot.value` straight to `Engine.evaluate` (its per-instance cache stays valid).
- `suspend current()` (memory, else `load()`), `suspend load()` (rebuild; on failure the last good snapshot,
  else `files/snapshot.json`, else `ConfigSnapshot()`), `suspend rebuild()` (throws on DB failure),
  `suspend setPlatformFacts(f)` (rebuilds), `readCache()`, `cacheFile`.
- Every repository mutation rebuilds before returning. Until the platform calls `setPlatformFacts`, the facts
  come from the cache file (cold start keeps the launcher/IME exempt).

## Debug commands (app `src/hooks`, debug + qa)
`import --es b64 <base64>` | `--es path files/debug/seed.json`, `export` (→ `files/debug/export.json`),
`reset` (`graph.wipe()` then `ResetHooks`), `state` (→ `files/debug/state.json` =
`{config: ConfigSnapshot, counters: [UsageCounter], strict: StrictModeRow, …}`). Result 1 = done; 0 + reason
(`WriteResult.describe()`) when refused/invalid. Extension points in `dev.brainrotprevention.debug`:
`ResetHooks.add { /* Context receiver */ }` (remove admin, clock-real, debug sensors, snooze chunk) and
`StateContributors.add { "statuses" to jsonElement }`.

## Schema
Room v2 (auto-migration from v1: nullable `usage_counter.period`, `usage_counter.holdUntil`, `strict_mode.bootCount`),
`schemas/dev.brainrotprevention.data.db.BrpDatabase/{1,2}.json`; enums stored by name; singleton rows
`quick_block.id = 1`, `strict_mode.id = 1`; `quick_block_web` PK `(kind, value)`; `notification_count` PK
`(packageName, day)`. Never `REPLACE` a schedule (it would cascade-delete children). A schema change bumps the
version, exports the new JSON and adds a case to `MigrationTest`.
