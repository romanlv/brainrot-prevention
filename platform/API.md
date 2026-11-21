# platform API (for the UI)

Android library, package `dev.brainrotprevention.platform`. Owns the accessibility service (engine host,
enforcement, block overlay), the notification listener, alarms, sensors, Strict Mode activation and the
permission health checks. The UI never talks to Android system services for these; it calls the entry points
below. Everything is reachable from the process-wide graph:

```kotlin
val p: PlatformGraph = context.platform        // dev.brainrotprevention.platform.platform (extension)
p.data            // the core:data DataGraph (same as context.dataGraph)
p.engineHost      // EngineHost
p.health          // PermissionHealth
p.apps            // InstalledApps
p.strict          // StrictActivator
```

`BrpApp` creates the graph in `onCreate` (`PlatformGraph.create(app, dataGraph, clock, debug)`).

## EngineHost (`p.engineHost`)
- `status: StateFlow<HostStatus>` — the latest evaluation for Home:
  `HostStatus(statuses: List<ScheduleStatus> /* engine, Quick Block id = QUICK_BLOCK_ID */, blocking: List<Long>,
  budgets: List<BudgetLeft>, strict: StrictDisplayState, nextInstant: PolicyInstant?, serviceConnected: Boolean,
  evaluatedAt: Long /* policy ms */)`. `HostStatus.statusOf(id)` helper. Updates after every evaluation (the
  service polls every 300 ms while something is armed; otherwise on config change, alarms, screen on).
- `requestEvaluate(reason: String = "ui")` — fire and forget. The host also re-evaluates by itself whenever the
  config snapshot changes, so calling it after a repository write is optional but harmless.
- `suspend fun evaluateNow(reason)` — evaluates and returns the new `HostStatus` (for debug commands/tests).
- `fun now(): Long` — the policy clock (use it for "until" arithmetic: pause 15 min = `now() + 15*60_000`).

**Pause / Start now / Quick Block** go through the data repositories (`p.data.schedules.pause(id, until)`,
`startNow`, `stopNow`, `resume`, `setEnabled`, `p.data.quickBlock.start(until)`/`stop()`), then
`engineHost.requestEvaluate()`. `WriteResult.Locked` → show "Locked by Strict Mode" (tag `locked_by_strict`).

## PermissionHealth (`p.health`)
- `fun check(): HealthReport` (cheap, call on resume) and `report: StateFlow<HealthReport>` (refreshed on
  `refresh()`, service connect, every 6 h).
- `HealthReport(items: List<GrantStatus>, restrictedInstall: Boolean, advancedProtection: Boolean,
  settingsLockedByStrict: Boolean)`; `HealthReport.ok` = every `required` item granted;
  `operator get(kind)`.
- `GrantStatus(kind: Grant, granted: Boolean, required: Boolean, detail: String?)`.
- `enum Grant { ACCESSIBILITY /* enabled AND connected; detail says which */, USAGE_ACCESS,
  NOTIFICATION_LISTENER, POST_NOTIFICATIONS, EXACT_ALARMS, LOCATION_FINE, LOCATION_BACKGROUND,
  BATTERY_OPTIMIZATION, DEVICE_ADMIN }` (`required` = a11y + usage access; location only when a Wi-Fi/location
  trigger exists).
- `fun accessibilityEnabled()`, `fun accessibilityConnected()` (the service is bound right now).
- `fun fixIntent(kind): Intent?` — the Settings screen for that grant (`ACTION_ACCESSIBILITY_SETTINGS`, …).
  POST_NOTIFICATIONS / LOCATION_FINE are runtime permissions: `runtimePermission(kind): String?` gives the
  permission string for `rememberLauncherForActivityResult(RequestPermission())`. Background location on API 30+
  uses `fixIntent` (App info). Device admin: see `StrictActivator.deviceAdminIntent()`.
- `fun appDetailsIntent(): Intent` — App info (restricted settings: ⋮ → *Allow restricted settings*).
- `fun open(activity: Activity, kind: Grant): FixOutcome` — `Opened | LockedByStrict | Unavailable`.
  **Always use this for Settings deep links** (§9.3): while Strict Mode is ACTIVE with BLOCK_SETTINGS it does not
  open anything and returns `LockedByStrict` → show "Locked by Strict Mode" (tag `locked_by_strict`).
- `fun settingsLockedByStrict(): Boolean`.

## InstalledApps (`p.apps`)
- `suspend fun launchable(): List<AppEntry>` — launcher apps for the picker, sorted by label, **without** the
  always-exempt packages (own app, launcher, IME, dialer, System UI, …). `AppEntry(packageName, label,
  isSystem: Boolean)`.
- `fun icon(pkg): Drawable?`, `fun label(pkg): String` (falls back to the package name).
- `suspend fun unsupportedBrowsers(): List<AppEntry>` — §7.7 (for the Strict activation screen's exclude list;
  exclude via `p.data.settings.setExcludedBrowsers`).
- `fun isExempt(pkg): Boolean`, `suspend fun allowlistEssentials(): List<AppEntry>` (editor, allowlist mode).
- `appDomains: Map<String, List<String>>` — bundled `app_domains.json` (package → domains, "Also block
  instagram.com?").

## StrictActivator (`p.strict`) — the normal activation path (UI and `strict-start` debug command)
- `suspend fun facts(): StrictFacts` and `suspend fun preflight(req: StrictActivation): ActivationRefusal?`.
- `suspend fun activate(req: StrictActivation): ActivationOutcome` → `Activated | Refused(reason, message) |
  NeedsDeviceAdmin(intent)`. With BLOCK_UNINSTALL and no admin yet it returns `NeedsDeviceAdmin`: launch the intent
  (`startActivityForResult`/`ActivityResultContracts.StartActivityForResult`), and call `activate` again after the
  result (`isAdminActive()` true) — the admin is always active before the ON row is saved. Calling
  `deviceAdminIntent()` marks the 2-minute "activation pending" window (DeviceAdminAdd exempt from the guard).
- `fun isAdminActive()`, `fun deviceAdminIntent(): Intent`.
- `suspend fun pausedOrOff(): List<Schedule>` + `suspend fun resumeAll()` (the "Resume all" prompt),
  `suspend fun notCovered(): List<Schedule>` (ALLOWLIST, "not covered by Strict Mode").
- Unlock (all return `UnlockOutcome = Unlocked | WrongPin(lockedUntil) | Refused(reason, remainingMs)`):
  - `suspend fun unlockWithCharger()` (reads the sticky `ACTION_BATTERY_CHANGED` now)
  - `suspend fun unlockWithPin(pin)`
  - COOLDOWN: `startCooldown(): Long /*startedElapsed*/`, `cooldownRemaining(started)`, `suspend fun
    finishCooldown(started)`; cancel = forget `started` (on `ON_STOP`). Keep the screen on while waiting.
  - SCHEDULES in STANDBY: `startStandbyTurnOff()`, `suspend fun finishStandbyTurnOff(started)` (fixed 5 min).
  - `suspend fun extendTimer(extraMs): WriteResult<Unit>`.
- Emergency (§9.7): `suspend fun emergencyAvailableAt(): Long?` (null = available), `PLEDGE: String` (retype
  exactly), `startEmergencyCountdown(): Long`, `emergencyRemaining(started)`, `suspend fun
  finishEmergency(started): EmergencyResult`.
- `elapsedNow()` (policy elapsed) for countdown UIs. After any unlock the platform removes the device admin.

## Block screen (platform-owned)
`dev.brainrotprevention.platform.ui.BlockScreen(model: BlockModel, actions: BlockActions)` — the full-bleed
Material 3 composable shown in the accessibility overlay. Tags: `block_close`, `block_pause`, `pause_15m`,
`block_allow_browser`. The UI agent may reuse it for previews; it is not shown from `MainActivity`.

## Debug-only
`dev.brainrotprevention.platform.debug.DebugPlatform` (clock commands, sensor overrides, snooze chunk) is used
by `app/src/hooks`; the UI doesn't need it.
