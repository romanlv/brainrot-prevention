package dev.brainrotprevention.engine

import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictMethod
import java.time.ZoneId

enum class InstantKind {
    WINDOW_START,
    WINDOW_END,
    PAUSE_END,
    PAUSE_END_WARNING,
    MANUAL_END,
    QUICK_BLOCK_END,
    STRICT_TIMER_END,
    DAILY_RESET,
    HOURLY_RESET,
    BEFORE_START,
    LIMIT_WARNING,
}

/** An upcoming policy instant (wall ms). Schedule it with `PolicyClock.toSystemMillis(at)`. */
data class PolicyInstant(val at: Long, val kind: InstantKind, val scheduleId: Long? = null)

/** A TIME budget currently being counted (for the predictable LIMIT_WARNING point). */
data class CountingBudget(val scheduleId: Long, val timeLeftMs: Long, val warned: Boolean)

/** §11.4: every upcoming instant the alarm scheduler tracks. */
object NextInstants {
    fun all(snapshot: ConfigSnapshot, now: Long, zone: ZoneId, counting: List<CountingBudget> = emptyList()): List<PolicyInstant> {
        val out = ArrayList<PolicyInstant>()
        val prefs = snapshot.prefs
        for (s in snapshot.schedules) {
            if (!s.enabled) continue
            try {
                scheduleInstants(s, now, zone, prefs.notifyBeforeStartMin, prefs.notifyBeforePauseEndMin, out)
            } catch (e: RuntimeException) {
                // A broken schedule is reported by Engine.evaluate; it must not stop other alarms.
            }
        }
        val qb = snapshot.quickBlock
        val qbUntil = qb.until
        if (qb.active && qbUntil != null && qbUntil > now) out += PolicyInstant(qbUntil, InstantKind.QUICK_BLOCK_END, QUICK_BLOCK_ID)
        val strict = snapshot.strict
        val endsAt = strict.endsAt
        if (strict.isOn && strict.method == StrictMethod.TIMER && endsAt != null && endsAt > now) {
            out += PolicyInstant(endsAt, InstantKind.STRICT_TIMER_END)
        }
        val periods = snapshot.schedules.filter { it.enabled }.flatMap { s -> s.limits.map { it.period } }.toSet()
        if (Period.DAILY in periods) out += PolicyInstant(Periods.dailyEnd(now, zone, prefs.dayStartHour), InstantKind.DAILY_RESET)
        if (Period.HOURLY in periods) out += PolicyInstant(Periods.hourlyEnd(now, zone), InstantKind.HOURLY_RESET)
        val warnMs = prefs.limitWarningMin * Periods.MINUTE
        if (warnMs > 0) {
            for (c in counting) {
                if (!c.warned &&
                    c.timeLeftMs > warnMs
                ) {
                    out += PolicyInstant(now + c.timeLeftMs - warnMs, InstantKind.LIMIT_WARNING, c.scheduleId)
                }
            }
        }
        out.sortWith(compareBy({ it.at }, { it.kind }))
        return out
    }

    fun next(snapshot: ConfigSnapshot, now: Long, zone: ZoneId, counting: List<CountingBudget> = emptyList()): PolicyInstant? =
        all(snapshot, now, zone, counting).firstOrNull()

    private fun scheduleInstants(
        s: Schedule,
        now: Long,
        zone: ZoneId,
        beforeStartMin: Int,
        beforePauseEndMin: Int,
        out: MutableList<PolicyInstant>,
    ) {
        if (s.timeWindows.isNotEmpty()) {
            val start = ScheduleEvaluator.nextWindowStart(s, now, zone)
            if (start != null) {
                out += PolicyInstant(start, InstantKind.WINDOW_START, s.id)
                if (beforeStartMin > 0) {
                    ScheduleEvaluator.occurrences(s, now, zone, daysBack = 0).map { it.start - beforeStartMin * Periods.MINUTE }
                        .filter { it > now }.minOrNull()
                        ?.let { out += PolicyInstant(it, InstantKind.BEFORE_START, s.id) }
                }
            }
            ScheduleEvaluator.nextWindowEnd(s, now, zone)?.let { out += PolicyInstant(it, InstantKind.WINDOW_END, s.id) }
        }
        val pause = s.pausedUntil
        if (pause != null && pause > now) {
            out += PolicyInstant(pause, InstantKind.PAUSE_END, s.id)
            val warn = pause - beforePauseEndMin * Periods.MINUTE
            if (beforePauseEndMin > 0 && warn > now) out += PolicyInstant(warn, InstantKind.PAUSE_END_WARNING, s.id)
        }
        val manual = s.manualUntil
        if (manual != null && manual > now && manual != Schedule.UNTIL_STOPPED) out += PolicyInstant(manual, InstantKind.MANUAL_END, s.id)
    }
}
