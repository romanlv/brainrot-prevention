package dev.brainrotprevention.model

import java.time.DayOfWeek

/** Weekday bitmask, Mon=1 … Sun=64, all days = 127 (§4). */
object Weekdays {
    const val MON = 1
    const val TUE = 2
    const val WED = 4
    const val THU = 8
    const val FRI = 16
    const val SAT = 32
    const val SUN = 64
    const val WEEKDAYS = MON or TUE or WED or THU or FRI
    const val WEEKEND = SAT or SUN
    const val ALL = 127

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun contains(mask: Int, day: DayOfWeek): Boolean = mask and bit(day) != 0

    fun of(vararg days: DayOfWeek): Int = days.fold(0) { acc, d -> acc or bit(d) }

    fun days(mask: Int): List<DayOfWeek> = DayOfWeek.entries.filter { contains(mask, it) }

    fun isValid(mask: Int): Boolean = mask in 1..ALL
}
