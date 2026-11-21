package dev.brainrotprevention.engine

import dev.brainrotprevention.model.Combine
import dev.brainrotprevention.model.LocationCondition
import dev.brainrotprevention.model.LocationState
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.SensorState
import dev.brainrotprevention.model.TimeWindow
import dev.brainrotprevention.model.TriggerType
import dev.brainrotprevention.model.Weekdays
import java.time.LocalDateTime
import java.time.ZoneId

/** Trigger evaluation of one schedule at one instant (§5.2). */
data class TriggerStatus(
    val present: Set<TriggerType>,
    /** Present trigger types that currently hold. */
    val holding: Set<TriggerType>,
    /** Combined trigger result (true when no triggers: "Always on"). */
    val triggers: Boolean,
    val matchedSsid: String?,
    val matchedLocations: List<String>,
)

/** One concrete occurrence of a window, as instants [start, end). */
data class WindowOccurrence(val start: Long, val end: Long)

/** §5.2–§5.3. Pure functions of (schedule, sensors, now, zone). */
object ScheduleEvaluator {
    /** How far the "until" merge scans (§5.3). */
    const val MERGE_HORIZON_DAYS = 8L

    fun contains(window: TimeWindow, local: LocalDateTime): Boolean {
        val m = local.hour * 60 + local.minute
        val day = local.dayOfWeek
        return if (window.startMin < window.endMin) {
            Weekdays.contains(window.days, day) && m >= window.startMin && m < window.endMin
        } else {
            (Weekdays.contains(window.days, day) && m >= window.startMin) ||
                (Weekdays.contains(window.days, day.minus(1)) && m < window.endMin)
        }
    }

    fun timeOk(schedule: Schedule, now: Long, zone: ZoneId): Boolean {
        if (schedule.timeWindows.isEmpty()) return true
        val local = Periods.localDateTime(now, zone)
        return schedule.timeWindows.any { contains(it, local) }
    }

    fun locationSatisfied(row: LocationCondition, state: LocationState): Boolean =
        (state == LocationState.INSIDE && !row.inverted) || (state == LocationState.OUTSIDE && row.inverted)

    fun triggerStatus(schedule: Schedule, sensors: SensorState, now: Long, zone: ZoneId): TriggerStatus {
        val present = schedule.triggerTypes
        val holding = mutableSetOf<TriggerType>()
        if (TriggerType.TIME in present && timeOk(schedule, now, zone)) holding += TriggerType.TIME
        val ssid = sensors.ssid
        val matchedSsid = if (ssid != null && schedule.wifi.any { it.ssid == ssid }) ssid else null
        if (matchedSsid != null) holding += TriggerType.WIFI
        val matchedLocs = schedule.locations.filter { locationSatisfied(it, sensors.locations[it.id] ?: it.lastState) }
        if (matchedLocs.isNotEmpty()) holding += TriggerType.LOCATION
        val triggers = when {
            present.isEmpty() -> true
            schedule.combine == Combine.ALL -> holding.containsAll(present)
            else -> holding.isNotEmpty()
        }
        return TriggerStatus(present, holding, triggers, matchedSsid, matchedLocs.map { it.label })
    }

    fun isPaused(schedule: Schedule, now: Long): Boolean = schedule.pausedUntil?.let { it > now } ?: false

    fun manualActive(schedule: Schedule, now: Long): Boolean = schedule.manualUntil?.let { it > now } ?: false

    fun isArmed(schedule: Schedule, sensors: SensorState, now: Long, zone: ZoneId): Boolean =
        isArmed(schedule, triggerStatus(schedule, sensors, now, zone), now)

    fun isArmed(schedule: Schedule, trig: TriggerStatus, now: Long): Boolean =
        schedule.enabled && !isPaused(schedule, now) && (trig.triggers || manualActive(schedule, now))

    /** All window occurrences overlapping local dates [from, from + days]. Sorted by start. */
    fun occurrences(
        schedule: Schedule,
        now: Long,
        zone: ZoneId,
        daysBack: Long = 1,
        daysForward: Long = MERGE_HORIZON_DAYS
    ): List<WindowOccurrence> {
        val today = Periods.localDateTime(now, zone).toLocalDate()
        val out = ArrayList<WindowOccurrence>()
        var d = today.minusDays(daysBack)
        val last = today.plusDays(daysForward)
        while (!d.isAfter(last)) {
            for (w in schedule.timeWindows) {
                if (!Weekdays.contains(w.days, d.dayOfWeek)) continue
                val start = Periods.resolveStart(Periods.atMinute(d, w.startMin), zone)
                val endDate = if (w.overnight) d.plusDays(1) else d
                val end = Periods.resolveEnd(Periods.atMinute(endDate, w.endMin), zone)
                if (end > start) out += WindowOccurrence(start, end)
            }
            d = d.plusDays(1)
        }
        out.sortBy { it.start }
        return out
    }

    /**
     * End of the continuous window run containing now, merging windows that overlap or touch (§5.3).
     * Returns null when the run is still continuous at 8 days ("all week") or no window contains now.
     * Use [windowEndOrOngoing] to tell the two apart.
     */
    fun windowEnd(schedule: Schedule, now: Long, zone: ZoneId): Long? = (windowEndOrOngoing(schedule, now, zone) as? WindowEnd.At)?.at

    sealed interface WindowEnd {
        data class At(val at: Long) : WindowEnd

        data object Ongoing : WindowEnd

        data object NotInWindow : WindowEnd
    }

    fun windowEndOrOngoing(schedule: Schedule, now: Long, zone: ZoneId): WindowEnd {
        val occ = occurrences(schedule, now, zone)
        var end: Long? = null
        for (o in occ) {
            if (end == null) {
                if (o.start <= now && now < o.end) end = o.end
            } else if (o.start <= end) {
                if (o.end > end) end = o.end
            } else {
                break
            }
        }
        if (end == null) {
            // Containment is by local wall time; the instants may disagree inside DST transitions.
            return if (timeOk(schedule, now, zone) &&
                schedule.timeWindows.isNotEmpty()
            ) {
                WindowEnd.At(nextMinute(now))
            } else {
                WindowEnd.NotInWindow
            }
        }
        return if (end - now >= MERGE_HORIZON_DAYS * Periods.DAY) WindowEnd.Ongoing else WindowEnd.At(end)
    }

    private fun nextMinute(now: Long): Long = (now / Periods.MINUTE + 1) * Periods.MINUTE

    /** Next window start strictly after now (within the merge horizon), or null. */
    fun nextWindowStart(schedule: Schedule, now: Long, zone: ZoneId): Long? =
        occurrences(schedule, now, zone, daysBack = 0).map { it.start }.filter { it > now }.minOrNull()

    /** Next window end strictly after now (within the merge horizon), or null. */
    fun nextWindowEnd(schedule: Schedule, now: Long, zone: ZoneId): Long? =
        occurrences(schedule, now, zone).map { it.end }.filter { it > now }.minOrNull()
}
