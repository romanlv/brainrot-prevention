package dev.brainrotprevention.engine

import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.UsageCounter
import dev.brainrotprevention.model.UsageLimit
import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * Ledger state between ticks. [counters] mirror the persisted `usage_counter` rows (by limitId);
 * everything else is in-memory bookkeeping that may be dropped on process start.
 */
@Serializable
data class LedgerState(
    val counters: Map<Long, UsageCounter> = emptyMap(),
    val lastTickElapsed: Long? = null,
    val lastTickInteractive: Boolean = false,
    /** Per schedule: launch-eligible item keys matched at the previous evaluation. */
    val previousKeys: Map<Long, Set<String>> = emptyMap(),
    /** Per schedule: keys whose new launch was refused by the LAUNCHES limit, while they stay in front. */
    val gatedKeys: Map<Long, Set<String>> = emptyMap(),
    val screenOffAt: Long? = null,
    val keysBeforeScreenOff: Map<Long, Set<String>> = emptyMap(),
    val dirty: Boolean = false,
    val lastSaveElapsed: Long? = null,
    val lastTargetKeys: Set<String> = emptySet(),
) {
    fun gated(scheduleId: Long, key: String): Boolean = gatedKeys[scheduleId]?.contains(key) == true
}

/** Per-schedule input for one tick. Keys: package name for apps, `NormalizedUrl.itemKey` for pages. */
data class LedgerSchedule(
    val scheduleId: Long,
    val armed: Boolean,
    val limits: List<UsageLimit>,
    /** Every matched item (foreground members and web targets); drives TIME counting. */
    val matchedKeys: Set<String>,
    /** Matched items that may count as launches (not floating/secondary). */
    val launchKeys: Set<String> = matchedKeys,
)

data class LedgerInput(
    val now: Long,
    val zone: ZoneId,
    /** Policy-elapsed ms. */
    val elapsed: Long,
    val screenInteractive: Boolean,
    val schedules: List<LedgerSchedule>,
    val dayStartHour: Int = 0,
    val pendingDayStartHour: Int? = null,
    val limitWarningMin: Int = 5,
    /** Debug: count the elapsed delta in full instead of clamping to 15 s (§5.4). */
    val countFullDelta: Boolean = false,
)

sealed interface LedgerEvent {
    data class LaunchCounted(val scheduleId: Long, val limitId: Long, val key: String, val used: Long) : LedgerEvent

    data class LaunchBlocked(val scheduleId: Long, val limitId: Long, val key: String) : LedgerEvent

    /** Post the heads-up limit warning once (§5.4). */
    data class LimitWarning(val scheduleId: Long, val limitId: Long, val timeLeftMs: Long) : LedgerEvent

    data class PeriodReset(val limitId: Long, val periodStart: Long) : LedgerEvent

    /** Persist `dayStartHour = hour`, `pendingDayStartHour = null`. */
    data class DayStartHourApplied(val hour: Int) : LedgerEvent
}

data class LedgerOutput(
    val state: LedgerState,
    val events: List<LedgerEvent>,
    /** Non-empty when a save is due: persist these counters (all current ones). */
    val countersToSave: List<UsageCounter>,
)

/** §5.4 as a pure state machine. */
object UsageLedger {
    const val MAX_TICK_DELTA_MS = 15_000L
    const val ANTI_BOUNCE_MS = 30_000L
    const val SAVE_INTERVAL_MS = 10_000L

    /**
     * Reset-on-read (§5.4). Returns the counter for the current period: reset when the period moved on,
     * re-anchored (keeping `used`) when the clock went back.
     */
    fun read(counter: UsageCounter?, limit: UsageLimit, now: Long, zone: ZoneId, dayStartHour: Int): UsageCounter {
        val current = Periods.periodStart(limit.period, now, zone, dayStartHour)
        return when {
            counter == null -> UsageCounter(limitId = limit.id, periodStart = current)
            counter.periodStart < current -> UsageCounter(limit.id, current, 0, counter.lastLaunchAt, null)
            counter.periodStart > current -> counter.copy(
                periodStart = current,
                warnedPeriodStart = if (counter.warnedPeriodStart == counter.periodStart) current else counter.warnedPeriodStart,
            )
            else -> counter
        }
    }

    fun exhausted(limit: UsageLimit, counter: UsageCounter): Boolean = counter.used >= limit.allowance

    fun step(state: LedgerState, input: LedgerInput): LedgerOutput {
        val events = ArrayList<LedgerEvent>()
        val counters = HashMap<Long, UsageCounter>()
        var changed = false
        var dailyReset = false
        var hasDaily = false

        val delta = if (state.lastTickElapsed != null && state.lastTickInteractive && input.screenInteractive) {
            val d = (input.elapsed - state.lastTickElapsed).coerceAtLeast(0)
            if (input.countFullDelta) d else d.coerceAtMost(MAX_TICK_DELTA_MS)
        } else {
            0L
        }

        var screenOffAt = state.screenOffAt
        var keysBeforeOff = state.keysBeforeScreenOff
        if (!input.screenInteractive && (state.lastTickInteractive || screenOffAt == null)) {
            screenOffAt = input.now
            keysBeforeOff = state.previousKeys
        }

        val newPrev = HashMap<Long, Set<String>>()
        val newGated = HashMap<Long, Set<String>>()
        val targetKeys = HashSet<String>()

        for (s in input.schedules) {
            val launchKeys = if (input.screenInteractive) s.launchKeys else emptySet()
            val matched = if (input.screenInteractive) s.matchedKeys else emptySet()
            targetKeys += matched
            for (limit in s.limits) {
                if (limit.period == Period.DAILY) hasDaily = true
                val before = state.counters[limit.id]
                var c = read(before, limit, input.now, input.zone, input.dayStartHour)
                if (before != null && before.periodStart < c.periodStart) {
                    events += LedgerEvent.PeriodReset(limit.id, c.periodStart)
                    if (limit.period == Period.DAILY) dailyReset = true
                }
                if (c != before) changed = true
                if (limit.kind == LimitKind.TIME && s.armed && matched.isNotEmpty() && delta > 0) {
                    c = c.copy(used = c.used + delta)
                    changed = true
                    val left = limit.allowance - c.used
                    val warnMs = input.limitWarningMin * Periods.MINUTE
                    if (warnMs > 0 && left in 1..warnMs && c.warnedPeriodStart != c.periodStart) {
                        c = c.copy(warnedPeriodStart = c.periodStart)
                        events += LedgerEvent.LimitWarning(s.scheduleId, limit.id, left)
                    }
                }
                counters[limit.id] = c
            }

            val prev = state.previousKeys[s.scheduleId].orEmpty()
            val gated = state.gatedKeys[s.scheduleId].orEmpty().intersect(launchKeys).toMutableSet()
            val launchLimit = s.limits.firstOrNull { it.kind == LimitKind.LAUNCHES }
            if (launchLimit != null && s.armed) {
                for (key in (launchKeys - prev).sorted()) {
                    var c = counters.getValue(launchLimit.id)
                    if (!isNewLaunch(c, key, input.now, screenOffAt, keysBeforeOff[s.scheduleId])) continue
                    if (c.used >= launchLimit.allowance) {
                        gated += key
                        events += LedgerEvent.LaunchBlocked(s.scheduleId, launchLimit.id, key)
                    } else {
                        c = c.copy(used = c.used + 1, lastLaunchAt = input.now)
                        counters[launchLimit.id] = c
                        changed = true
                        events += LedgerEvent.LaunchCounted(s.scheduleId, launchLimit.id, key, c.used)
                    }
                }
            }
            if (launchKeys.isNotEmpty()) newPrev[s.scheduleId] = launchKeys
            if (gated.isNotEmpty()) newGated[s.scheduleId] = gated
        }

        if (input.pendingDayStartHour != null && (dailyReset || !hasDaily)) {
            events += LedgerEvent.DayStartHourApplied(input.pendingDayStartHour)
        }

        val dirty = state.dirty || changed
        val saveDue = dirty &&
            (
                state.lastSaveElapsed == null ||
                    input.elapsed - state.lastSaveElapsed >= SAVE_INTERVAL_MS ||
                    input.elapsed < state.lastSaveElapsed ||
                    targetKeys != state.lastTargetKeys ||
                    events.any { it !is LedgerEvent.DayStartHourApplied }
                )
        val newState = LedgerState(
            counters = counters,
            lastTickElapsed = input.elapsed,
            lastTickInteractive = input.screenInteractive,
            previousKeys = newPrev,
            gatedKeys = newGated,
            screenOffAt = screenOffAt,
            keysBeforeScreenOff = keysBeforeOff,
            dirty = dirty && !saveDue,
            lastSaveElapsed = if (saveDue) input.elapsed else state.lastSaveElapsed,
            lastTargetKeys = targetKeys,
        )
        return LedgerOutput(newState, events, if (saveDue) counters.values.sortedBy { it.limitId } else emptyList())
    }

    /** §5.4: ≥ 30 s since lastLaunchAt, and not the item that was in front right before a screen-off < 30 s ago. */
    private fun isNewLaunch(c: UsageCounter, key: String, now: Long, screenOffAt: Long?, beforeOff: Set<String>?): Boolean {
        val last = c.lastLaunchAt
        if (last != null && now >= last && now - last < ANTI_BOUNCE_MS) return false
        if (screenOffAt != null &&
            now >= screenOffAt &&
            now - screenOffAt < ANTI_BOUNCE_MS &&
            beforeOff?.contains(key) == true
        ) {
            return false
        }
        return true
    }
}
