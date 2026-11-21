package dev.brainrotprevention.engine

import dev.brainrotprevention.model.ClockReading
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.TimeWindow
import dev.brainrotprevention.model.UsageLimit
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind
import dev.brainrotprevention.model.WifiCondition
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

/** Local Berlin time `yyyy-MM-ddTHH:mm[:ss]` → epoch ms (earlier offset in overlaps). */
fun at(local: String): Long = ZonedDateTime.ofLocal(LocalDateTime.parse(local), BERLIN, null).toInstant().toEpochMilli()

fun clock(local: String, elapsed: Long = 0): ClockReading = ClockReading(at(local), BERLIN, elapsed)

fun hm(s: String): Int = if (s == "24:00") 1440 else s.substring(0, 2).toInt() * 60 + s.substring(3, 5).toInt()

fun win(days: Int, start: String, end: String, id: Long = 0) = TimeWindow(id, days, hm(start), hm(end))

fun timeLimit(minutes: Long, id: Long = 100, period: Period = Period.DAILY) = UsageLimit(id, LimitKind.TIME, period, minutes * 60_000)

fun launchLimit(n: Long, id: Long = 200, period: Period = Period.DAILY) = UsageLimit(id, LimitKind.LAUNCHES, period, n)

fun domain(v: String) = WebRule(kind = WebRuleKind.DOMAIN, value = v)

fun keyword(v: String, anywhere: Boolean = false) = WebRule(kind = WebRuleKind.KEYWORD, value = v, anywhereInUrl = anywhere)

fun wifi(vararg ssids: String) = ssids.map { WifiCondition(ssid = it) }

fun schedule(id: Long = 1, vararg apps: String, block: Schedule.() -> Schedule = { this }): Schedule =
    Schedule(id = id, name = "S$id", apps = apps.toList()).block()

const val T = "dev.brainrotprevention.fixture.target"
const val CHROME = "com.android.chrome"
const val MIN = 60_000L
const val SEC = 1_000L
