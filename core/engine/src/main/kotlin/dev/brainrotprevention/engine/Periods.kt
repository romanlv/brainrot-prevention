package dev.brainrotprevention.engine

import dev.brainrotprevention.model.Period
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Local-time ↔ instant helpers and DAILY/HOURLY periods (§5.3, §5.4). */
object Periods {
    const val MINUTE = 60_000L
    const val HOUR = 3_600_000L
    const val DAY = 86_400_000L

    /**
     * Instant for a local start time. In a DST gap: the first valid instant (the transition).
     * In an overlap: the earlier offset.
     */
    fun resolveStart(local: LocalDateTime, zone: ZoneId): Long {
        val trans = zone.rules.getTransition(local)
        if (trans != null && trans.isGap) return trans.instant.toEpochMilli()
        return ZonedDateTime.ofLocal(local, zone, null).toInstant().toEpochMilli()
    }

    /**
     * Instant for a local end time. In a DST gap: the first valid instant. In an overlap: the later
     * offset, because containment is active in both copies of a repeated hour (§5.3).
     */
    fun resolveEnd(local: LocalDateTime, zone: ZoneId): Long {
        val trans = zone.rules.getTransition(local)
        if (trans != null && trans.isGap) return trans.instant.toEpochMilli()
        return ZonedDateTime.ofLocal(local, zone, null).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
    }

    fun localDateTime(now: Long, zone: ZoneId): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)

    fun atMinute(date: LocalDate, minute: Int): LocalDateTime =
        if (minute >= 1440) date.plusDays(1).atStartOfDay() else date.atTime(minute / 60, minute % 60)

    /** Start of the current DAILY period: today's `hour:00`, or yesterday's if now is earlier. */
    fun dailyStart(now: Long, zone: ZoneId, dayStartHour: Int): Long {
        val date = localDateTime(now, zone).toLocalDate()
        val today = resolveStart(date.atTime(dayStartHour, 0), zone)
        return if (now >= today) today else resolveStart(date.minusDays(1).atTime(dayStartHour, 0), zone)
    }

    /** End (exclusive) of the DAILY period containing now: the next `hour:00`. */
    fun dailyEnd(now: Long, zone: ZoneId, dayStartHour: Int): Long {
        val start = dailyStart(now, zone, dayStartHour)
        val startDate = localDateTime(start, zone).toLocalDate()
        return resolveStart(startDate.plusDays(1).atTime(dayStartHour, 0), zone)
    }

    fun hourlyStart(now: Long, zone: ZoneId): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()

    fun hourlyEnd(now: Long, zone: ZoneId): Long =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zone).truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()

    fun periodStart(period: Period, now: Long, zone: ZoneId, dayStartHour: Int): Long = when (period) {
        Period.DAILY -> dailyStart(now, zone, dayStartHour)
        Period.HOURLY -> hourlyStart(now, zone)
    }

    fun periodEnd(period: Period, now: Long, zone: ZoneId, dayStartHour: Int): Long = when (period) {
        Period.DAILY -> dailyEnd(now, zone, dayStartHour)
        Period.HOURLY -> hourlyEnd(now, zone)
    }

    /** Pause option "until the end of today": the next `dayStartHour:00` minus 1 ms (§8). */
    fun endOfToday(now: Long, zone: ZoneId, dayStartHour: Int): Long = dailyEnd(now, zone, dayStartHour) - 1
}
