package dev.brainrotprevention.engine

import dev.brainrotprevention.model.ClockReading
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.ForegroundApp
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.SensorState
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.UsageCounter
import dev.brainrotprevention.model.WebTarget
import kotlinx.serialization.Serializable

/** Engine memory between evaluations. Only `ledger.counters` must be persisted (as usage_counter rows). */
@Serializable
data class EngineState(
    val ledger: LedgerState = LedgerState(),
    /** Last successful `blocking` per ALLOWLIST schedule, for fail handling (§3 rule 4). */
    val lastGoodBlocking: Map<Long, Boolean> = emptyMap(),
)

data class EvaluationInput(
    val snapshot: ConfigSnapshot,
    val clock: ClockReading,
    val state: EngineState = EngineState(),
    val sensors: SensorState = SensorState(),
    /** Foreground set (§10.1). */
    val foreground: List<ForegroundApp> = emptyList(),
    /** Current `lastUrl` per browser window (§7.8) and WebAPKs are handled from [foreground]. */
    val webTargets: List<WebTarget> = emptyList(),
    val screenInteractive: Boolean = true,
    /** Keyguard locked: every activity is allowlist-essential (§6.3). */
    val keyguardLocked: Boolean = false,
    /** Browser-table packages flagged `url_bar_missing` (§7.2). */
    val urlBarMissing: Set<String> = emptySet(),
    /** Strict activation flow pending: DeviceAdminAdd is exempt (§9.2). */
    val adminActivationPending: Boolean = false,
    /** Debug: count `clock-advance` deltas in full (§5.4). */
    val countFullDelta: Boolean = false,
)

data class PageDecision(val target: WebTarget, val url: NormalizedUrl, val decision: Decision)

/** Budget left for a schedule whose list matches the current target (status notification). */
data class BudgetLeft(val scheduleId: Long, val timeLeftMs: Long?, val launchesLeft: Long?)

data class StatusSummary(
    /** Blocking schedules, Quick Block as [QUICK_BLOCK_ID]. */
    val blocking: List<Long>,
    val budgets: List<BudgetLeft>,
    val strict: StrictDisplayState,
)

enum class StrictEnd { TIMER_EXPIRED, FOLLOWED_SET_EMPTY }

/** Row writes the platform performs after an evaluation (all idempotent). */
data class Cleanup(
    /** `pausedUntil` in the past: set to null. */
    val expiredPauses: List<Long>,
    /** `manualUntil` in the past: set to null. */
    val expiredManual: List<Long>,
    /** Quick Block timer expired: set `active = false`. */
    val quickBlockExpired: Boolean,
    /** Strict Mode must become INACTIVE (`StrictPolicy.deactivated`) and the admin be removed. */
    val strictEnd: StrictEnd?,
)

data class EvaluationResult(
    /** Decision per foreground package. */
    val apps: Map<String, Decision>,
    val pages: List<PageDecision>,
    /** Feed back as `EvaluationInput.state` next time. */
    val state: EngineState,
    val ledgerEvents: List<LedgerEvent>,
    /** Non-empty when a counter save is due. */
    val countersToSave: List<UsageCounter>,
    /** Every schedule, then Quick Block. */
    val statuses: List<ScheduleStatus>,
    val summary: StatusSummary,
    val cleanup: Cleanup,
    val nextInstant: PolicyInstant?,
    /** Schedule ids whose evaluation threw (post "Blocking error, tap to fix"). */
    val errors: List<Long>,
) {
    val anyBlock: Boolean get() = apps.values.any { it.isBlock } || pages.any { it.decision.isBlock }
}

/**
 * The facade. One instance per process; [evaluate] is cheap enough for a 300 ms poll and caches the
 * derived lookup sets per snapshot instance (pass the same object until the configuration changes).
 */
class Engine {
    @Volatile private var cached: PreparedSnapshot? = null

    fun prepare(snapshot: ConfigSnapshot): PreparedSnapshot {
        val c = cached
        if (c != null && c.snapshot === snapshot) return c
        return PreparedSnapshot(snapshot).also { cached = it }
    }

    fun evaluate(input: EvaluationInput): EvaluationResult {
        val p = prepare(input.snapshot)
        val snap = input.snapshot
        val now = input.clock.now
        val zone = input.clock.zone
        val interactive = input.screenInteractive
        val dayStartHour = snap.prefs.dayStartHour
        val foreground = if (interactive) input.foreground else emptyList()
        val pages = if (interactive) input.webTargets.map { it to UrlNormalizer.normalizeDetected(it.url) } else emptyList()
        val errors = ArrayList<Long>()

        // 1. Triggers / armed per schedule, and list-matched item keys for the ledger.
        val ledgerSchedules = ArrayList<LedgerSchedule>()
        val failed = HashSet<Long>()
        val keysBySchedule = HashMap<Long, Set<String>>()
        for (s in p.effective) {
            val sched = s.schedule ?: continue
            val armed = try {
                ScheduleEvaluator.isArmed(sched, input.sensors, now, zone)
            } catch (e: RuntimeException) {
                failed += s.id
                errors += s.id
                continue
            }
            val matched = HashSet<String>()
            val launch = HashSet<String>()
            for (app in foreground) {
                if (DecisionMaker.isExempt(p, app) ||
                    !DecisionMaker.appListMatch(p, s, app.packageName, input.keyguardLocked, app.component)
                ) {
                    continue
                }
                matched += app.packageName
                if (!app.secondary) launch += app.packageName
            }
            for ((t, url) in pages) {
                if (DecisionMaker.pageMatch(p, s, t, url)) {
                    val key = DecisionMaker.pageKey(url)
                    matched += key
                    launch += key
                }
            }
            keysBySchedule[s.id] = matched
            if (sched.limits.isNotEmpty()) ledgerSchedules += LedgerSchedule(s.id, armed, sched.limits, matched, launch)
        }

        // 2. Ledger tick.
        val ledgerOut = UsageLedger.step(
            input.state.ledger,
            LedgerInput(
                now = now,
                zone = zone,
                elapsed = input.clock.elapsed,
                screenInteractive = interactive,
                schedules = ledgerSchedules,
                dayStartHour = dayStartHour,
                pendingDayStartHour = snap.prefs.pendingDayStartHour,
                limitWarningMin = snap.prefs.limitWarningMin,
                countFullDelta = input.countFullDelta,
            ),
        )
        val ledger = ledgerOut.state

        // 3. Statuses.
        val lastGood = HashMap(input.state.lastGoodBlocking)
        val statuses = ArrayList<ScheduleStatus>(p.effective.size)
        for (s in p.effective) {
            val sched = s.schedule
            if (sched == null) {
                statuses += StatusCalculator.quickBlock(snap.quickBlock, now)
                continue
            }
            val st = if (s.id in failed) {
                StatusCalculator.failed(sched, input.state.lastGoodBlocking[s.id])
            } else {
                try {
                    StatusCalculator.schedule(sched, input.sensors, ledger.counters, now, zone, dayStartHour)
                } catch (e: RuntimeException) {
                    errors += s.id
                    StatusCalculator.failed(sched, input.state.lastGoodBlocking[s.id])
                }
            }
            if (sched.mode == Mode.ALLOWLIST && !st.error) lastGood[s.id] = st.blocking
            statuses += st
        }
        val byId = statuses.associateBy { it.scheduleId }

        // 4. Strict display state and decisions.
        val strictDisplay = StrictPolicy.displayState(snap.strict, snap.schedules, byId)
        val ctx = DecisionContext(
            prepared = p,
            statuses = byId,
            strictDisplay = strictDisplay,
            gated = { id, key -> ledger.gated(id, key) },
            keyguardLocked = input.keyguardLocked,
            urlBarMissing = input.urlBarMissing,
            adminActivationPending = input.adminActivationPending,
        )
        val apps = LinkedHashMap<String, Decision>()
        for (app in foreground) {
            val d = DecisionMaker.decideApp(ctx, app)
            val prev = apps[app.packageName]
            if (prev == null || (!prev.isBlock && d.isBlock)) apps[app.packageName] = d
        }
        val pageDecisions = pages.map { (t, url) -> PageDecision(t, url, DecisionMaker.decideWeb(ctx, t, url)) }

        // 5. Summary, cleanup, next instant.
        val budgets = ArrayList<BudgetLeft>()
        val counting = ArrayList<CountingBudget>()
        for (st in statuses) {
            if (!st.armed || st.blocking || (st.time == null && st.launches == null)) continue
            if (keysBySchedule[st.scheduleId].isNullOrEmpty()) continue
            budgets += BudgetLeft(st.scheduleId, st.time?.left, st.launches?.left)
            st.time?.let { counting += CountingBudget(st.scheduleId, it.left, it.counter.warnedPeriodStart == it.counter.periodStart) }
        }
        val summary = StatusSummary(statuses.filter { it.blocking }.map { it.scheduleId }, budgets, strictDisplay)
        val strictEnd = when {
            StrictPolicy.timerExpired(snap.strict, now, input.clock.elapsed) -> StrictEnd.TIMER_EXPIRED
            StrictPolicy.shouldEndForEmptyFollowedSet(snap.strict, snap.schedules) -> StrictEnd.FOLLOWED_SET_EMPTY
            else -> null
        }
        val qb = snap.quickBlock
        val cleanup = Cleanup(
            expiredPauses = snap.schedules.filter { (it.pausedUntil ?: Long.MAX_VALUE) <= now }.map { it.id },
            expiredManual = snap.schedules.filter { (it.manualUntil ?: Long.MAX_VALUE) <= now }.map { it.id },
            quickBlockExpired = qb.active && (qb.until ?: Long.MAX_VALUE) <= now,
            strictEnd = strictEnd,
        )
        return EvaluationResult(
            apps = apps,
            pages = pageDecisions,
            state = EngineState(ledger, lastGood),
            ledgerEvents = ledgerOut.events,
            countersToSave = ledgerOut.countersToSave,
            statuses = statuses,
            summary = summary,
            cleanup = cleanup,
            nextInstant = NextInstants.next(snap, now, zone, counting),
            errors = errors.distinct(),
        )
    }

    /** Statuses and strict display state without a ledger tick (UI, editor, alarms). */
    fun status(
        snapshot: ConfigSnapshot,
        clock: ClockReading,
        counters: Map<Long, UsageCounter>,
        sensors: SensorState = SensorState()
    ): StatusView {
        val statuses = StatusCalculator.all(snapshot, sensors, counters, clock.now, clock.zone)
        val byId = statuses.associateBy { it.scheduleId }
        return StatusView(
            statuses = statuses,
            strict = StrictPolicy.displayState(snapshot.strict, snapshot.schedules, byId),
            nextInstant = NextInstants.next(snapshot, clock.now, clock.zone),
        )
    }

    /** Schedules hiding notifications in [statuses] (§10.4): the listener sweeps only when this set changes. */
    fun notificationHidingIds(snapshot: ConfigSnapshot, statuses: List<ScheduleStatus>): Set<Long> =
        NotificationPolicy.hidingIds(prepare(snapshot), statuses.associateBy { it.scheduleId })

    /**
     * Whether [pkg] would be blocked if it were in front, from the statuses of an evaluation (§10.2 step 1:
     * media is re-paused "while the package stays blocked", also after it left the foreground). A LAUNCHES gate
     * only refuses new launches, so it doesn't count here; the Settings/installer guards don't either.
     */
    fun backgroundBlock(snapshot: ConfigSnapshot, statuses: List<ScheduleStatus>, strict: StrictDisplayState, pkg: String): Decision {
        val ctx = DecisionContext(prepare(snapshot), statuses.associateBy { it.scheduleId }, strict)
        val d = DecisionMaker.decideApp(ctx, ForegroundApp(pkg))
        if (d !is Decision.Block) return d
        val reasons = d.reasons.filter { it.source != ReasonSource.StrictSettings && it.source != ReasonSource.StrictUninstall }
        return Decision.of(reasons)
    }

    /** Notification-hiding until for [pkg] (§10.4), from the statuses of the last evaluation. */
    fun notificationBlock(snapshot: ConfigSnapshot, statuses: List<ScheduleStatus>, pkg: String): Until? =
        NotificationPolicy.blockFor(prepare(snapshot), statuses.associateBy { it.scheduleId }, pkg)
}

data class StatusView(val statuses: List<ScheduleStatus>, val strict: StrictDisplayState, val nextInstant: PolicyInstant?)
