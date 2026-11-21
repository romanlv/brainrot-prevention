package dev.brainrotprevention.ui

import dev.brainrotprevention.engine.Until
import dev.brainrotprevention.model.Combine
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.LocationCondition
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.TimeWindow
import dev.brainrotprevention.model.UsageLimit
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind
import dev.brainrotprevention.model.Weekdays
import dev.brainrotprevention.model.WifiCondition
import dev.brainrotprevention.ui.format.Format
import dev.brainrotprevention.ui.format.StatePill
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    private val utc = ZoneId.of("UTC")

    /** Mon 2026-11-23 10:00 UTC. */
    private val t0 = 1_795_428_000_000L

    @Test fun minutes() {
        assertEquals("00:00", Format.minuteOfDay(0))
        assertEquals("17:40", Format.minuteOfDay(1060))
        assertEquals("24:00", Format.minuteOfDay(1440))
    }

    @Test fun days() {
        assertEquals("Every day", Format.days(Weekdays.ALL))
        assertEquals("Mon–Fri", Format.days(Weekdays.WEEKDAYS))
        assertEquals("Weekends", Format.days(Weekdays.WEEKEND))
        assertEquals("Mon–Wed", Format.days(Weekdays.MON or Weekdays.TUE or Weekdays.WED))
        assertEquals("Mon, Wed, Fri", Format.days(Weekdays.MON or Weekdays.WED or Weekdays.FRI))
        assertEquals("Sun", Format.days(Weekdays.SUN))
    }

    @Test fun windowsAndOvernight() {
        assertEquals("Mon–Fri · 08:00–17:40", Format.window(TimeWindow(days = Weekdays.WEEKDAYS, startMin = 480, endMin = 1060)))
        assertEquals("22:00–06:00 (ends next day)", Format.windowTimes(TimeWindow(days = 1, startMin = 1320, endMin = 360)))
    }

    @Test fun durations() {
        assertEquals("45 s", Format.duration(45_000))
        assertEquals("12 min", Format.duration(12 * 60_000L))
        assertEquals("2 h 10 m", Format.duration(130 * 60_000L))
        assertEquals("1 h", Format.duration(60 * 60_000L))
        assertEquals("3 d 4 h", Format.duration((3 * 24 + 4) * 3_600_000L))
        assertEquals("1:05", Format.countdown(65_000))
        assertEquals("1:00:00", Format.countdown(3_600_000))
    }

    @Test fun summaries() {
        val s = Schedule(
            name = "Work",
            timeWindows = listOf(TimeWindow(days = Weekdays.WEEKDAYS, startMin = 480, endMin = 1060)),
            limits = listOf(UsageLimit(kind = LimitKind.TIME, period = Period.DAILY, allowance = 30 * 60_000L)),
        )
        assertEquals("Mon–Fri · 08:00–17:40 · 30 min/day", Format.summary(s))
        assertEquals("Always on", Format.summary(Schedule(name = "x")))
        val cap = Schedule(name = "c", limits = listOf(UsageLimit(kind = LimitKind.LAUNCHES, period = Period.HOURLY, allowance = 3)))
        assertEquals("All day · 3 opens/hour", Format.summary(cap))
        val mixed = s.copy(
            limits = emptyList(),
            wifi = listOf(WifiCondition(ssid = "Office")),
            locations = listOf(LocationCondition(label = "Home", lat = 0.0, lon = 0.0, radiusM = 200, inverted = true)),
            combine = Combine.ALL,
        )
        assertEquals("Mon–Fri · 08:00–17:40 · on Wi-Fi Office · away from Home · all together", Format.summary(mixed))
    }

    @Test fun untilLines() {
        assertEquals("until 17:40", Format.until(Until.At(t0 + 460 * 60_000L), t0, utc))
        assertEquals("until Tue 09:00", Format.until(Until.At(t0 + 23 * 3_600_000L), t0, utc))
        assertEquals("until you stop it", Format.until(Until.UntilStopped, t0, utc))
        assertEquals("until you stop it", Format.until(Until.At(Long.MAX_VALUE), t0, utc))
        assertEquals("all week", Format.until(Until.Ongoing, t0, utc))
        assertEquals("while on Wi-Fi Home", Format.until(Until.WhileConditions("on Wi-Fi Home"), t0, utc))
    }

    @Test fun rulePreview() {
        assertEquals("Blocks facebook.com and every subdomain", Format.rule(WebRule(kind = WebRuleKind.DOMAIN, value = "facebook.com")))
        assertTrue(Format.rule(WebRule(kind = WebRuleKind.KEYWORD, value = "bet", anywhereInUrl = true)).contains("address contains"))
    }

    @Test fun pills() {
        val s = Schedule(name = "a", enabled = false)
        assertEquals(StatePill.Off, StatePill.of(s, null, t0, utc))
        val paused = Schedule(name = "a", pausedUntil = t0 + 15 * 60_000L)
        val p = StatePill.of(paused, null, t0, utc)
        assertTrue(p is StatePill.Paused)
        assertEquals("until 10:15", (p as StatePill.Paused).untilText)
    }
}
