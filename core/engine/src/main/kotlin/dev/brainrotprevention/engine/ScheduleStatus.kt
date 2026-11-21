package dev.brainrotprevention.engine

import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.SensorState
import dev.brainrotprevention.model.TriggerType
import dev.brainrotprevention.model.UsageCounter
import dev.brainrotprevention.model.UsageLimit
import java.time.ZoneId

/** One budget with its current-period counter. */
data class LimitStatus(val limit: UsageLimit, val counter: UsageCounter, val periodEnd: Long) {
    val exhausted: Boolean get() = counter.used >= limit.allowance
    val left: Long get() = (limit.allowance - counter.used).coerceAtLeast(0)
}

/** Evaluated state of one schedule (or Quick Block, id [QUICK_BLOCK_ID]) at one instant. */
data class ScheduleStatus(
    val scheduleId: Long,
    val isQuickBlock: Boolean,
    val enabled: Boolean,
    val paused: Boolean,
    val armed: Boolean,
    /** §5.4 `blocking(s)`: armed and (no limits or TIME exhausted). */
    val blocking: Boolean,
    val trigger: TriggerStatus?,
    /** How long the schedule stays armed (null when not armed). */
    val armUntil: Until?,
    /** Until for a blocking reason (null when not blocking). */
    val blockUntil: Until?,
    val time: LimitStatus?,
    val launches: LimitStatus?,
    /** Evaluation threw; BLOCKLIST fails closed, ALLOWLIST keeps its last result (§3 rule 4). */
    val error: Boolean = false,
) {
    val timeExhausted: Boolean get() = time?.exhausted == true
    val launchesExhausted: Boolean get() = launches?.exhausted == true

    /** Until for a LAUNCHES gate reason: min(periodEnd, armEnd). */
    val gateUntil: Until get() = capAt(armUntil, launches?.periodEnd)

    /** Counts as "blocking" for strict ACTIVE/STANDBY and notification hiding (§5.4, §9.5). */
    val blockingOrLaunchesExhausted: Boolean get() = blocking || (armed && launchesExhausted)

    companion object {
        internal fun capAt(arm: Until?, periodEnd: Long?): Until = when {
            periodEnd == null -> arm ?: Until.Ongoing
            arm is Until.At -> Until.At(minOf(arm.instant, periodEnd))
            else -> Until.At(periodEnd)
        }
    }
}

/** Computes [ScheduleStatus] values (§5.2–§5.5, §6.2 "until"). */
object StatusCalculator {
    fun armUntil(s: Schedule, trig: TriggerStatus, now: Long, zone: ZoneId): Until {
        val trigUntil: Until? = when {
            !trig.triggers -> null
            trig.present.isEmpty() -> Until.Ongoing
            trig.holding == setOf(TriggerType.TIME) -> when (val e = ScheduleEvaluator.windowEndOrOngoing(s, now, zone)) {
                is ScheduleEvaluator.WindowEnd.At -> Until.At(e.at)
                else -> Until.Ongoing
            }
            else -> Until.WhileConditions(conditionLabel(trig))
        }
        val manual = s.manualUntil
        if (manual == null || manual <= now) return trigUntil ?: Until.Ongoing
        if (manual == Schedule.UNTIL_STOPPED) return Until.UntilStopped
        return when (trigUntil) {
            null -> Until.At(manual)
            is Until.At -> Until.At(maxOf(manual, trigUntil.instant))
            else -> trigUntil
        }
    }

    fun conditionLabel(trig: TriggerStatus): String =
        (listOfNotNull(trig.matchedSsid?.let { "Wi-Fi $it" }) + trig.matchedLocations).joinToString(", ")

    fun limitStatus(limit: UsageLimit, counter: UsageCounter?, now: Long, zone: ZoneId, dayStartHour: Int): LimitStatus = LimitStatus(
        limit,
        UsageLedger.read(counter, limit, now, zone, dayStartHour),
        Periods.periodEnd(limit.period, now, zone, dayStartHour),
    )

    fun schedule(
        s: Schedule,
        sensors: SensorState,
        counters: Map<Long, UsageCounter>,
        now: Long,
        zone: ZoneId,
        dayStartHour: Int,
    ): ScheduleStatus {
        val trig = ScheduleEvaluator.triggerStatus(s, sensors, now, zone)
        val armed = ScheduleEvaluator.isArmed(s, trig, now)
        val time = s.limit(LimitKind.TIME)?.let { limitStatus(it, counters[it.id], now, zone, dayStartHour) }
        val launches = s.limit(LimitKind.LAUNCHES)?.let { limitStatus(it, counters[it.id], now, zone, dayStartHour) }
        val blocking = armed && (s.limits.isEmpty() || time?.exhausted == true)
        val armUntil = if (armed) armUntil(s, trig, now, zone) else null
        val blockUntil = when {
            !blocking -> null
            s.limits.isEmpty() -> armUntil
            else -> ScheduleStatus.capAt(armUntil, time?.periodEnd)
        }
        return ScheduleStatus(
            scheduleId = s.id,
            isQuickBlock = false,
            enabled = s.enabled,
            paused = ScheduleEvaluator.isPaused(s, now),
            armed = armed,
            blocking = blocking,
            trigger = trig,
            armUntil = armUntil,
            blockUntil = blockUntil,
            time = time,
            launches = launches,
        )
    }

    /** Fail-closed status for a schedule whose evaluation threw (§3 rule 4). */
    fun failed(s: Schedule, lastGoodBlocking: Boolean?): ScheduleStatus {
        val blocking = if (s.mode == Mode.BLOCKLIST) true else lastGoodBlocking == true
        return ScheduleStatus(
            scheduleId = s.id,
            isQuickBlock = false,
            enabled = s.enabled,
            paused = false,
            armed = blocking,
            blocking = blocking,
            trigger = null,
            armUntil = if (blocking) Until.Ongoing else null,
            blockUntil = if (blocking) Until.Ongoing else null,
            time = null,
            launches = null,
            error = true,
        )
    }

    /** §5.5: armed = blocking = active && (until == null || until > now). */
    fun quickBlock(qb: QuickBlock, now: Long): ScheduleStatus {
        val on = qb.active && (qb.until ?: Long.MAX_VALUE) > now
        val until = if (!on) null else qb.until?.let { Until.At(it) } ?: Until.UntilStopped
        return ScheduleStatus(QUICK_BLOCK_ID, true, qb.active, false, on, on, null, until, until, null, null)
    }

    /** Statuses of every schedule plus Quick Block, without mutating anything (UI/status use). */
    fun all(
        snapshot: ConfigSnapshot,
        sensors: SensorState,
        counters: Map<Long, UsageCounter>,
        now: Long,
        zone: ZoneId
    ): List<ScheduleStatus> =
        snapshot.schedules.map { s ->
            try {
                schedule(s, sensors, counters, now, zone, snapshot.prefs.dayStartHour)
            } catch (e: RuntimeException) {
                failed(s, null)
            }
        } + quickBlock(snapshot.quickBlock, now)
}
