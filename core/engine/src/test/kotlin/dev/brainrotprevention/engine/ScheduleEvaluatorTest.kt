package dev.brainrotprevention.engine

import dev.brainrotprevention.model.Combine
import dev.brainrotprevention.model.LocationCondition
import dev.brainrotprevention.model.LocationState
import dev.brainrotprevention.model.SensorState
import dev.brainrotprevention.model.TriggerType
import dev.brainrotprevention.model.Weekdays
import dev.brainrotprevention.model.Weekdays.ALL
import dev.brainrotprevention.model.Weekdays.FRI
import dev.brainrotprevention.model.Weekdays.MON
import dev.brainrotprevention.model.Weekdays.SAT
import dev.brainrotprevention.model.Weekdays.SUN
import dev.brainrotprevention.model.Weekdays.WEEKDAYS
import java.time.DayOfWeek
import java.time.LocalDateTime
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleEvaluatorTest {
    private data class Case(val days: Int, val start: String, val end: String, val local: String, val expected: Boolean)

    @Test
    fun windowContainment() {
        // 2026-11-23 is a Monday; 2026-11-28 Saturday.
        val cases = listOf(
            Case(WEEKDAYS, "09:00", "17:00", "2026-11-23T09:00", true),
            Case(WEEKDAYS, "09:00", "17:00", "2026-11-23T08:59", false),
            Case(WEEKDAYS, "09:00", "17:00", "2026-11-23T17:00", false),
            Case(WEEKDAYS, "09:00", "17:00", "2026-11-23T16:59", true),
            Case(WEEKDAYS, "09:00", "17:00", "2026-11-28T09:30", false), // S4 day mask
            Case(FRI, "22:00", "06:00", "2026-11-27T22:00", true), // Friday evening
            Case(FRI, "22:00", "06:00", "2026-11-28T05:59", true), // S3: previous-day rule (Sat)
            Case(FRI, "22:00", "06:00", "2026-11-28T06:00", false),
            Case(FRI, "22:00", "06:00", "2026-11-27T05:00", false), // Fri morning: Thu not in mask
            Case(SAT, "22:00", "06:00", "2026-11-29T01:00", true), // Sat night spills into Sunday
            Case(SUN, "22:00", "06:00", "2026-11-23T03:00", true), // Sunday → Monday wraps the week
            Case(ALL, "21:00", "24:00", "2026-11-23T23:59", true),
            Case(ALL, "21:00", "24:00", "2026-11-24T00:00", false),
            Case(SUN, "00:00", "24:00", "2026-11-29T12:00", true),
        )
        for (c in cases) {
            val w = win(c.days, c.start, c.end)
            assertEquals(c.toString(), c.expected, ScheduleEvaluator.contains(w, LocalDateTime.parse(c.local)))
        }
    }

    @Test
    fun weekdayMaskHelpers() {
        assertEquals(1, Weekdays.bit(DayOfWeek.MONDAY))
        assertEquals(64, Weekdays.bit(DayOfWeek.SUNDAY))
        assertEquals(MON or SUN, Weekdays.of(DayOfWeek.MONDAY, DayOfWeek.SUNDAY))
        assertEquals(listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), Weekdays.days(Weekdays.WEEKEND))
        assertFalse(Weekdays.isValid(0))
        assertTrue(Weekdays.isValid(127))
        assertFalse(Weekdays.isValid(128))
    }

    @Test
    fun dstSpringForwardBerlin() {
        // 2026-03-29: 02:00 → 03:00.
        val s = schedule { copy(timeWindows = listOf(win(ALL, "02:30", "04:00"))) }
        val now = at("2026-03-29T01:59")
        // Gap start resolves to the first valid instant (03:00 CEST).
        assertEquals(at("2026-03-29T03:00"), ScheduleEvaluator.nextWindowStart(s, now, BERLIN))
        assertTrue(ScheduleEvaluator.timeOk(s, at("2026-03-29T03:00"), BERLIN))
        assertEquals(at("2026-03-29T04:00"), ScheduleEvaluator.windowEnd(s, at("2026-03-29T03:10"), BERLIN))
        // A window ending in the gap ends at the transition.
        val s2 = schedule { copy(timeWindows = listOf(win(ALL, "01:00", "02:30"))) }
        assertEquals(at("2026-03-29T03:00"), ScheduleEvaluator.windowEnd(s2, at("2026-03-29T01:30"), BERLIN))
        // Daily period start in a gap.
        assertEquals(at("2026-03-29T03:00"), Periods.dailyStart(at("2026-03-29T10:00"), BERLIN, 2))
    }

    @Test
    fun dstFallBackBerlin() {
        // 2026-10-25: 03:00 CEST → 02:00 CET; 02:00–03:00 happens twice.
        val s = schedule { copy(timeWindows = listOf(win(ALL, "02:00", "02:30"))) }
        val firstCopy = at("2026-10-25T02:10") // earlier offset
        val secondCopy = firstCopy + Periods.HOUR
        assertTrue(ScheduleEvaluator.timeOk(s, firstCopy, BERLIN))
        assertTrue(ScheduleEvaluator.timeOk(s, secondCopy, BERLIN))
        assertFalse(ScheduleEvaluator.timeOk(s, at("2026-10-25T02:40"), BERLIN))
        // The end is the later copy's 02:30.
        val laterEnd = at("2026-10-25T02:30") + Periods.HOUR
        assertEquals(laterEnd, ScheduleEvaluator.windowEnd(s, firstCopy, BERLIN))
        // The fall-back day is 25 h long for DAILY periods.
        val start = Periods.dailyStart(at("2026-10-25T12:00"), BERLIN, 0)
        assertEquals(25 * Periods.HOUR, Periods.dailyEnd(at("2026-10-25T12:00"), BERLIN, 0) - start)
    }

    @Test
    fun combineAllAny() {
        data class C(val combine: Combine, val time: Boolean, val ssid: String?, val loc: LocationState, val expected: Boolean)
        val cases = listOf(
            C(Combine.ANY, true, null, LocationState.OUTSIDE, true),
            C(Combine.ANY, false, "Home", LocationState.OUTSIDE, true),
            C(Combine.ANY, false, null, LocationState.INSIDE, true),
            C(Combine.ANY, false, "Other", LocationState.UNKNOWN, false),
            C(Combine.ALL, true, "Home", LocationState.INSIDE, true),
            C(Combine.ALL, true, "Home", LocationState.OUTSIDE, false),
            C(Combine.ALL, true, null, LocationState.INSIDE, false),
            C(Combine.ALL, false, "Home", LocationState.INSIDE, false),
        )
        for (c in cases) {
            val s = schedule {
                copy(
                    combine = c.combine,
                    timeWindows = listOf(win(MON, "09:00", "17:00")),
                    wifi = wifi("Home", "Work"),
                    locations = listOf(LocationCondition(id = 7, label = "Gym", lat = 0.0, lon = 0.0, radiusM = 100)),
                )
            }
            val now = if (c.time) at("2026-11-23T10:00") else at("2026-11-23T08:00")
            val sensors = SensorState(ssid = c.ssid, locations = mapOf(7L to c.loc))
            assertEquals(c.toString(), c.expected, ScheduleEvaluator.isArmed(s, sensors, now, BERLIN))
        }
    }

    @Test
    fun locationRows() {
        val inside = LocationCondition(id = 1, label = "A", lat = 0.0, lon = 0.0, radiusM = 100)
        val notHere = inside.copy(inverted = true)
        assertTrue(ScheduleEvaluator.locationSatisfied(inside, LocationState.INSIDE))
        assertFalse(ScheduleEvaluator.locationSatisfied(inside, LocationState.OUTSIDE))
        assertTrue(ScheduleEvaluator.locationSatisfied(notHere, LocationState.OUTSIDE))
        assertFalse(ScheduleEvaluator.locationSatisfied(notHere, LocationState.INSIDE))
        assertFalse(ScheduleEvaluator.locationSatisfied(inside, LocationState.UNKNOWN))
        assertFalse(ScheduleEvaluator.locationSatisfied(notHere, LocationState.UNKNOWN))
        // Falls back to the row's lastState when the sensor map has no entry.
        val s = schedule { copy(locations = listOf(inside.copy(lastState = LocationState.INSIDE))) }
        assertTrue(ScheduleEvaluator.isArmed(s, SensorState(), 0, BERLIN))
    }

    @Test
    fun armedRules() {
        val now = at("2026-11-23T08:00")
        val base = schedule { copy(timeWindows = listOf(win(MON, "09:00", "10:00"))) }
        assertFalse(ScheduleEvaluator.isArmed(base, SensorState(), now, BERLIN))
        // Start now overrides triggers…
        assertTrue(ScheduleEvaluator.isArmed(base.copy(manualUntil = now + 1), SensorState(), now, BERLIN))
        // …but not a pause.
        assertFalse(ScheduleEvaluator.isArmed(base.copy(manualUntil = now + 1, pausedUntil = now + 1), SensorState(), now, BERLIN))
        // An expired pause doesn't pause.
        val always = schedule()
        assertTrue(ScheduleEvaluator.isArmed(always.copy(pausedUntil = now), SensorState(), now, BERLIN))
        assertFalse(ScheduleEvaluator.isArmed(always.copy(enabled = false), SensorState(), now, BERLIN))
        assertTrue(ScheduleEvaluator.triggerStatus(always, SensorState(), now, BERLIN).present.isEmpty())
    }

    @Test
    fun untilMergeAndEightDayCap() {
        val now = at("2026-11-23T10:00")
        // Touching windows merge: 09–12 and 12–15.
        val s = schedule { copy(timeWindows = listOf(win(MON, "09:00", "12:00"), win(MON, "12:00", "15:00"))) }
        assertEquals(at("2026-11-23T15:00"), ScheduleEvaluator.windowEnd(s, now, BERLIN))
        // Overlapping overnight chain: Mon 08–24 and Mon 22 – Tue 09.
        val s2 = schedule { copy(timeWindows = listOf(win(MON, "08:00", "24:00"), win(MON, "22:00", "09:00"))) }
        assertEquals(at("2026-11-24T09:00"), ScheduleEvaluator.windowEnd(s2, now, BERLIN))
        // All day every day: continuous past 8 days → Ongoing.
        val s3 = schedule { copy(timeWindows = listOf(win(ALL, "00:00", "24:00"))) }
        assertEquals(ScheduleEvaluator.WindowEnd.Ongoing, ScheduleEvaluator.windowEndOrOngoing(s3, now, BERLIN))
        assertNull(ScheduleEvaluator.windowEnd(s3, now, BERLIN))
        // Every day but Sunday, all day: the run ends at Sunday 00:00.
        val s4 = schedule { copy(timeWindows = listOf(win(ALL and SUN.inv(), "00:00", "24:00"))) }
        assertEquals(at("2026-11-29T00:00"), ScheduleEvaluator.windowEnd(s4, now, BERLIN))
        // Not in window.
        assertEquals(ScheduleEvaluator.WindowEnd.NotInWindow, ScheduleEvaluator.windowEndOrOngoing(s, at("2026-11-23T16:00"), BERLIN))
    }

    @Test
    fun triggerHoldingSets() {
        val s = schedule {
            copy(timeWindows = listOf(win(MON, "09:00", "17:00")), wifi = wifi("Home"))
        }
        val t = ScheduleEvaluator.triggerStatus(s, SensorState(ssid = "Home"), at("2026-11-23T10:00"), BERLIN)
        assertEquals(setOf(TriggerType.TIME, TriggerType.WIFI), t.holding)
        assertEquals("Home", t.matchedSsid)
    }

    /** Property: containment agrees with the WeakenChecker coverage bitmap for random windows and minutes. */
    @Test
    fun propertyContainmentMatchesCoverage() {
        val rnd = Random(42)
        val monday = LocalDateTime.parse("2026-11-23T00:00")
        repeat(200) {
            val start = rnd.nextInt(0, 1440)
            var end = rnd.nextInt(1, 1441)
            if (end == start) end = (start + 1).coerceAtMost(1440)
            val w = dev.brainrotprevention.model.TimeWindow(0, rnd.nextInt(1, 128), start, end)
            val s = schedule { copy(timeWindows = listOf(w)) }
            val bits = WeakenChecker.coverage(s)
            repeat(50) {
                val cell = rnd.nextInt(0, WeakenChecker.CELLS)
                val local = monday.plusMinutes(cell.toLong())
                assertEquals("$w at $local", bits[cell], ScheduleEvaluator.contains(w, local))
            }
        }
    }
}
