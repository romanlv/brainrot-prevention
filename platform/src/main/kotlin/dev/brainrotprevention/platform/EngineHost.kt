package dev.brainrotprevention.platform

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import dev.brainrotprevention.engine.BudgetLeft
import dev.brainrotprevention.engine.Decision
import dev.brainrotprevention.engine.Engine
import dev.brainrotprevention.engine.EngineState
import dev.brainrotprevention.engine.EvaluationInput
import dev.brainrotprevention.engine.EvaluationResult
import dev.brainrotprevention.engine.InstantKind
import dev.brainrotprevention.engine.LedgerEvent
import dev.brainrotprevention.engine.LedgerState
import dev.brainrotprevention.engine.NextInstants
import dev.brainrotprevention.engine.PolicyInstant
import dev.brainrotprevention.engine.ScheduleStatus
import dev.brainrotprevention.engine.StrictPolicy
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.ForegroundApp
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.WebTarget
import dev.brainrotprevention.platform.log.Brp
import dev.brainrotprevention.platform.time.SystemTimeAndroid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The latest evaluation, for Home (see `platform/API.md`). */
data class HostStatus(
    val statuses: List<ScheduleStatus> = emptyList(),
    val blocking: List<Long> = emptyList(),
    val budgets: List<BudgetLeft> = emptyList(),
    val strict: StrictDisplayState = StrictDisplayState.INACTIVE,
    val nextInstant: PolicyInstant? = null,
    val serviceConnected: Boolean = false,
    /** Policy ms of the evaluation; 0 before the first one. */
    val evaluatedAt: Long = 0,
) {
    fun statusOf(scheduleId: Long): ScheduleStatus? = statuses.firstOrNull { it.scheduleId == scheduleId }
}

/**
 * Runs the engine (§3 rule 2): all evaluations are serialised on one dispatcher. Inputs come from the
 * accessibility service when it is connected (foreground set, URL bar); without it the host still evaluates
 * (empty foreground set) so Home, alarms, cleanup and Strict Mode stay current.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineHost internal constructor(private val app: Context, private val g: PlatformGraph) {
    private val engine = Engine()
    internal val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val requests = Channel<String>(Channel.CONFLATED)
    private val _status = MutableStateFlow(HostStatus())
    val status: StateFlow<HostStatus> = _status.asStateFlow()

    // --- confined to [dispatcher] ---
    private var state: EngineState? = null
    private var last: EvaluationResult? = null
    private var lastSnapshot: ConfigSnapshot? = null
    private var lastStrictDisplay: StrictDisplayState? = null
    private val lastDecision = HashMap<String, Boolean>() // target key → blocked
    private var lastAlarmCheck: Long? = null
    private var timerJob: Job? = null
    private var timerTarget: Long? = null
    private var lastTamperCheckRt = 0L
    private var errorsPosted = emptyList<Long>()

    /** Last evaluation result (read from any thread; may be slightly stale). */
    @Volatile var lastResult: EvaluationResult? = null
        private set

    fun now(): Long = g.clock.now()

    internal fun start() {
        g.scope.launch(dispatcher) {
            for (r in requests) {
                try {
                    evaluateLocked(r)
                } catch (t: Throwable) {
                    Brp.event("error", "where" to "evaluate", "msg" to t.toString())
                }
            }
        }
        // Re-evaluate whenever the configuration changes (repository writes rebuild the snapshot).
        g.scope.launch {
            g.data.snapshots.snapshot.filterNotNull().collect { requestEvaluate("config") }
        }
        // Strict tamper check every minute (§9.6), also while INACTIVE.
        g.scope.launch {
            while (true) {
                delay(60_000)
                runCatching { withContext(dispatcher) { tamperCheckLocked("minute") } }
            }
        }
        requestEvaluate("start")
    }

    /** Fire and forget. */
    fun requestEvaluate(reason: String = "ui") {
        requests.trySend(reason)
    }

    /** Evaluates now and returns the new status. */
    suspend fun evaluateNow(reason: String = "now"): HostStatus = withContext(dispatcher) {
        evaluateLocked(reason)
        _status.value
    }

    /** Runs [block] on the engine dispatcher (strict writes, clock commands). */
    internal suspend fun <T> confined(block: suspend () -> T): T = withContext(dispatcher) { block() }

    /**
     * The time-changed path (TIME_SET, TIMEZONE_CHANGED, debug clock commands, §14.3 item 1):
     * tamper check → fire every due alarm kind in order → reschedule → evaluate.
     */
    suspend fun onTimeChanged(reason: String) = withContext(dispatcher) {
        tamperCheckLocked(reason)
        evaluateLocked(reason)
    }

    /** An exact alarm or the in-process timer fired. */
    suspend fun onAlarm(reason: String) = withContext(dispatcher) { evaluateLocked(reason) }

    /** Boot / package replaced: re-anchor strict elapsed deadlines, then evaluate. */
    suspend fun onBoot() = withContext(dispatcher) {
        val row = g.data.strict.get()
        val clock = g.clock.read()
        val t = StrictPolicy.check(row, g.data.strict.getEmergencyLog(), clock.now, clock.elapsed)
        if (t.changed) g.data.strict.writeTamperCheck(t)
        val after = StrictPolicy.afterBoot(g.data.strict.get(), clock.now, clock.elapsed)
        if (after.isOn) g.data.strict.writePolicyResult(after)
        evaluateLocked("boot")
    }

    internal suspend fun tamperCheckLocked(reason: String) {
        val clock = g.clock.read()
        val row = g.data.strict.get()
        val t = StrictPolicy.check(row, g.data.strict.getEmergencyLog(), clock.now, clock.elapsed)
        lastTamperCheckRt = SystemTimeAndroid.elapsedRealtime()
        if (t.changed) {
            g.data.strict.writeTamperCheck(t)
            if (t.skewApplied != 0L) Brp.event("tamper", "reason" to reason, "skew" to t.skewApplied, "boot" to t.bootDetected)
        }
    }

    private suspend fun evaluateLocked(reason: String) {
        val snapshot = g.data.snapshots.current()
        val st = state ?: EngineState(LedgerState(counters = g.data.counters.loadAll())).also { state = it }
        val svc = g.service
        val clock = g.clock.read()

        // Alarm catch-up: every instant passed since the last check fires in chronological order (§11.4, §14.3).
        fireDueAlarms(snapshot, clock.now)

        val pm = app.getSystemService(PowerManager::class.java)
        val km = app.getSystemService(KeyguardManager::class.java)
        val interactive = pm?.isInteractive ?: true
        val foreground: List<ForegroundApp> = svc?.foregroundSet() ?: emptyList()
        val webTargets: List<WebTarget> = svc?.webTargets(foreground) ?: emptyList()
        val result = engine.evaluate(
            EvaluationInput(
                snapshot = snapshot,
                clock = clock,
                state = st,
                sensors = g.sensors.state(),
                foreground = foreground,
                webTargets = webTargets,
                screenInteractive = interactive,
                keyguardLocked = km?.isKeyguardLocked ?: false,
                urlBarMissing = svc?.urlBarMissing() ?: emptySet(),
                adminActivationPending = g.strict.adminActivationPending(),
                countFullDelta = g.debug,
            ),
        )
        state = result.state
        last = result
        lastResult = result
        lastSnapshot = snapshot

        persist(result, snapshot)
        logDecisions(result, foreground)
        svc?.enforce(result, foreground)
        g.listener?.onEvaluated(result, snapshot)

        val strict = result.summary.strict
        if (strict != lastStrictDisplay) {
            if (lastStrictDisplay != null || strict != StrictDisplayState.INACTIVE) Brp.event("strict", "state" to strict.name)
            lastStrictDisplay = strict
        }
        g.alarms.schedule(result.nextInstant)
        armTimer(result.nextInstant, interactive)
        if (result.errors != errorsPosted) {
            errorsPosted = result.errors
            if (result.errors.isNotEmpty()) g.notifier.postError(result.errors)
        }
        g.notifier.updateStatus(result, snapshot, svc != null)

        _status.value = HostStatus(
            statuses = result.statuses,
            blocking = result.summary.blocking,
            budgets = result.summary.budgets,
            strict = strict,
            nextInstant = result.nextInstant,
            serviceConnected = svc != null,
            evaluatedAt = clock.now,
        )
        svc?.onEvaluated(result, snapshot)
    }

    private suspend fun fireDueAlarms(snapshot: ConfigSnapshot, now: Long) {
        val from = lastAlarmCheck
        lastAlarmCheck = now
        if (from == null || now <= from) return
        val due = NextInstants.all(snapshot, from, g.clock.zone(), countingFromLast()).filter { it.at <= now }
        for (inst in due) {
            Brp.event("alarm_fired", "kind" to inst.kind.name, "at" to inst.at, "scheduleId" to inst.scheduleId)
            when (inst.kind) {
                InstantKind.PAUSE_END_WARNING -> {
                    val s = snapshot.schedules.firstOrNull { it.id == inst.scheduleId }
                    val until = s?.pausedUntil
                    if (s != null && until != null && until > now) g.notifier.postPauseEnd(s, until)
                }
                InstantKind.BEFORE_START -> {
                    val s = snapshot.schedules.firstOrNull { it.id == inst.scheduleId }
                    if (s != null) g.notifier.postBeforeStart(s)
                }
                else -> {}
            }
        }
    }

    private fun countingFromLast(): List<dev.brainrotprevention.engine.CountingBudget> {
        val r = last ?: return emptyList()
        return r.statuses.mapNotNull { st ->
            val t = st.time ?: return@mapNotNull null
            if (!st.armed || st.blocking) return@mapNotNull null
            if (r.summary.budgets.none { it.scheduleId == st.scheduleId }) return@mapNotNull null
            dev.brainrotprevention.engine.CountingBudget(st.scheduleId, t.left, t.counter.warnedPeriodStart == t.counter.periodStart)
        }
    }

    private suspend fun persist(r: EvaluationResult, snapshot: ConfigSnapshot) {
        if (r.countersToSave.isNotEmpty()) {
            g.data.counters.save(r.countersToSave)
            for (c in r.countersToSave) Brp.event("usage", "limitId" to c.limitId, "used" to c.used, "periodStart" to c.periodStart)
        }
        for (e in r.ledgerEvents) {
            when (e) {
                is LedgerEvent.LimitWarning -> {
                    val s = snapshot.schedules.firstOrNull { it.id == e.scheduleId }
                    g.notifier.postLimitWarning(s, e.timeLeftMs)
                }
                is LedgerEvent.DayStartHourApplied -> g.data.settings.applyDayStartHour(e.hour)
                is LedgerEvent.LaunchCounted -> Brp.event("launch", "scheduleId" to e.scheduleId, "key" to e.key, "used" to e.used)
                is LedgerEvent.LaunchBlocked -> Brp.event("launch_blocked", "scheduleId" to e.scheduleId, "key" to e.key)
                is LedgerEvent.PeriodReset -> Brp.event("period_reset", "limitId" to e.limitId, "periodStart" to e.periodStart)
            }
        }
        val c = r.cleanup
        val now = g.clock.now()
        if (c.expiredPauses.isNotEmpty() || c.expiredManual.isNotEmpty()) g.data.schedules.applyCleanup(c, now)
        if (c.quickBlockExpired) g.data.quickBlock.markExpired()
        if (c.strictEnd != null) {
            val row = g.data.strict.get()
            if (row.isOn) {
                g.data.strict.writePolicyResult(StrictPolicy.deactivated(row))
                Brp.event("strict_end", "why" to c.strictEnd!!.name)
                g.strict.removeAdmin()
            }
        }
    }

    private fun logDecisions(r: EvaluationResult, foreground: List<ForegroundApp>) {
        val seen = HashSet<String>()
        for ((pkg, d) in r.apps) {
            seen += pkg
            val blocked = d.isBlock
            if (lastDecision[pkg] != blocked) {
                lastDecision[pkg] = blocked
                // BLOCK lines are (also) written by the enforcer on every enforcement; log changes here.
                Events.decision(pkg, null, d)
            }
        }
        for (p in r.pages) {
            val key = "web:${p.target.browserPackage}:${p.target.windowId}:${p.target.url}"
            seen += key
            val blocked = p.decision.isBlock
            if (lastDecision[key] != blocked) {
                lastDecision[key] = blocked
                Events.decision(p.target.browserPackage, p.target.url, p.decision)
            }
        }
        lastDecision.keys.retainAll(seen)
        if (foreground.isEmpty() && r.apps.isEmpty()) lastDecision.clear()
    }

    /** Forget the per-target decision memory, so the next result for [pkg] is logged again. */
    internal fun forgetDecision(pkg: String) {
        g.scope.launch(dispatcher) { lastDecision.keys.removeAll { it == pkg || it.startsWith("web:$pkg:") } }
    }

    private fun armTimer(next: PolicyInstant?, interactive: Boolean) {
        val target = next?.at
        if (!interactive || target == null) {
            timerJob?.cancel()
            timerJob = null
            timerTarget = null
            return
        }
        if (target == timerTarget && timerJob?.isActive == true) return
        timerJob?.cancel()
        timerTarget = target
        timerJob = g.scope.launch {
            val wait = g.clock.toSystemMillis(target) - SystemTimeAndroid.currentTimeMillis()
            if (wait > 0) delay(wait)
            requestEvaluate("timer")
        }
    }

    /** For `state`/debug: is anything armed or blocking, or Strict ACTIVE (the poll condition, §10.1)? */
    fun needsPolling(): Boolean {
        val r = lastResult ?: return true
        return r.statuses.any { it.armed || it.blocking } || r.summary.strict == StrictDisplayState.ACTIVE
    }

    /** Debug reset: forget engine memory (counters are re-seeded from the DB). */
    internal suspend fun resetMemory() = withContext(dispatcher) {
        state = null
        last = null
        lastResult = null
        lastDecision.clear()
        lastAlarmCheck = null
        lastStrictDisplay = null
        errorsPosted = emptyList()
    }

    /** Notification-hiding until for [pkg] from [r] (§10.4); null = don't hide. */
    fun notificationBlock(snapshot: ConfigSnapshot, r: EvaluationResult, pkg: String): dev.brainrotprevention.engine.Until? =
        engine.notificationBlock(snapshot, r.statuses, pkg)

    /** True when the last decision for [pkg] was a block (used by the overlay to decide Close). */
    internal fun isBlocked(pkg: String): Boolean = lastResult?.apps?.get(pkg)?.isBlock == true

    internal fun decisionFor(pkg: String): Decision? = lastResult?.apps?.get(pkg)
}
