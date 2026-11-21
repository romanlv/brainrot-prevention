package dev.brainrotprevention.engine

import dev.brainrotprevention.model.Combine
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.EmergencyUnblock
import dev.brainrotprevention.model.ForegroundApp
import dev.brainrotprevention.model.LocationCondition
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictState
import dev.brainrotprevention.model.UsageCounter
import dev.brainrotprevention.model.WebRuleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the round-1 engine review (one test per finding). */
class ReviewRegressionTest {
    private val day = 24 * 60 * MIN

    // --- 2. Clock back then forward must not refill a budget [PL-M3] ---
    @Test
    fun clockBackThenForwardKeepsAnExhaustedBudget() {
        val limit = timeLimit(60)
        val now = at("2026-11-23T15:00")
        val exhausted = UsageCounter(100, at("2026-11-23T00:00"), 60 * MIN, null, null, Period.DAILY)
        val back = UsageLedger.read(exhausted, limit, now - day, BERLIN, 0)
        assertEquals(60 * MIN, back.used)
        val forward = UsageLedger.read(back, limit, now, BERLIN, 0)
        assertEquals(60 * MIN, forward.used)
        assertTrue(UsageLedger.exhausted(limit, forward))
        assertEquals(at("2026-11-23T00:00"), forward.periodStart)
        // The real next period still resets.
        val tomorrow = UsageLedger.read(forward, limit, at("2026-11-24T00:01"), BERLIN, 0)
        assertEquals(0, tomorrow.used)
        assertNull(tomorrow.holdUntil)
        // Through the ledger: no PeriodReset event for the forward jump.
        val sched = LedgerSchedule(1, true, listOf(limit), emptySet())
        val s1 = UsageLedger.step(
            LedgerState(counters = mapOf(100L to exhausted)),
            LedgerInput(now - day, BERLIN, 1_000, true, listOf(sched))
        )
        val s2 = UsageLedger.step(s1.state, LedgerInput(now, BERLIN, 2_000, true, listOf(sched)))
        assertFalse(s2.events.any { it is LedgerEvent.PeriodReset })
        assertEquals(60 * MIN, s2.state.counters.getValue(100).used)
    }

    // --- 3. Widening a host keyword to anywhere-in-URL never unblocks ---
    @Test
    fun anywhereKeywordIsASupersetOfHostKeyword() {
        val idn = UrlNormalizer.normalizeDetected("xn--bcher-kva.de/x")
        assertTrue(UrlMatcher.matches(keyword("bücher"), idn))
        assertTrue(UrlMatcher.matches(keyword("bücher", anywhere = true), idn))
        val urls = listOf("xn--bcher-kva.de/x", "ｆａｃｅｂｏｏｋ.com/a", "https://user@game.example.org:8080/p", "https://x.com/%41")
        for (u in urls) {
            val n = UrlNormalizer.normalizeDetected(u)
            for (k in listOf("bücher", "facebook", "game", "example")) {
                if (UrlMatcher.matches(keyword(k), n)) assertTrue("$k on $u", UrlMatcher.matches(keyword(k, anywhere = true), n))
            }
        }
    }

    // --- 4. Reboot after a short previous uptime is detected through BOOT_COUNT ---
    @Test
    fun rebootWithShortPreviousUptimeIsDetected() {
        val wall0 = at("2026-11-23T10:00")
        val row = StrictModeRow(
            state = StrictState.ON,
            method = StrictMethod.TIMER,
            endsAt = wall0 + 60 * MIN,
            endsAtElapsed = 60_000 + 60 * MIN,
            lastCheckWall = wall0,
            lastCheckElapsed = 60_000,
            bootCount = 7,
        )
        // Off for 2 h; first check after boot at elapsed 90 s.
        val wallNow = wall0 + 2 * 60 * MIN + 30_000
        val t = StrictPolicy.check(row, emptyList(), wallNow, 90_000, bootCount = 8)
        assertTrue(t.bootDetected)
        assertEquals(0, t.skewApplied)
        assertEquals(row.endsAt, t.row.endsAt) // the off time is served, not added
        assertEquals(8, t.row.bootCount)
        assertTrue(StrictPolicy.timerExpired(t.row, wallNow, 90_000))
        // Same boot: no detection.
        val again = StrictPolicy.check(t.row, emptyList(), wallNow + MIN, 90_000 + MIN, bootCount = 8)
        assertFalse(again.bootDetected)
    }

    // --- 5. Small skews add up ---
    @Test
    fun smallSkewsAccumulate() {
        var wall = at("2026-11-23T10:00")
        var el = 1_000_000L
        val log = listOf(EmergencyUnblock(1, wall - day))
        var row = StrictModeRow(lastCheckWall = wall, lastCheckElapsed = el)
        var curLog = log
        var total = 0L
        repeat(100) {
            wall += 60_000 + 59_000 // a minute passes plus a 59 s forward jump
            el += 60_000
            val t = StrictPolicy.check(row, curLog, wall, el)
            row = t.row
            curLog = t.emergencyLog
            total += t.skewApplied
        }
        assertTrue("applied $total", total >= 100 * 59_000L - 60_000L)
        assertEquals(log.single().at + total, curLog.single().at)
    }

    // --- 6. Percent-decoded text is lower-cased and NFC-normalised ---
    @Test
    fun decodedUrlIsLowerCasedAndNfc() {
        val k = keyword("über", anywhere = true)
        assertTrue(UrlMatcher.matches(k, UrlNormalizer.normalizeDetected("example.com/%C3%9Cber")))
        assertTrue(UrlMatcher.matches(k, UrlNormalizer.normalizeDetected("example.com/u%CC%88ber")))
        val samsung = UrlNormalizer.normalizeDetected(
            "https://r.internet.apps.samsung.com/refer?url=https%3A%2F%2FFaceBook.com%2F%C3%9Cber"
        )
        assertEquals("facebook.com", samsung.hostAscii)
        assertTrue(UrlMatcher.matches(k, samsung))
    }

    // --- 7. Trailing non-ASCII full stop ---
    @Test
    fun trailingIdeographicFullStopIsStripped() {
        val n = UrlNormalizer.normalizeDetected("facebook.com。")
        assertEquals("facebook.com", n.hostAscii)
        assertTrue(UrlMatcher.matches(domain("facebook.com"), n))
        val r = UrlNormalizer.normalizeRuleInput(WebRuleKind.DOMAIN, "facebook.com。")
        assertEquals(RuleInput.Ok(domain("facebook.com")), r)
    }

    // --- 8. A restart does not turn a running session into a new launch ---
    @Test
    fun restartKeepsRunningSessionOutOfTheLaunchGate() {
        val ll = launchLimit(3)
        val now = at("2026-11-23T10:00")
        val sched = LedgerSchedule(1, true, listOf(ll), setOf(T))
        val used = UsageCounter(200, at("2026-11-23T00:00"), 3, now - 10 * MIN, null, Period.DAILY)
        val out = UsageLedger.step(
            LedgerState(counters = mapOf(200L to used), previousKeysKnown = false),
            LedgerInput(now, BERLIN, 1_000, true, listOf(sched)),
        )
        assertFalse(out.events.any { it is LedgerEvent.LaunchBlocked })
        assertFalse(out.state.gated(1, T))
        // Below the allowance the restart is not counted either.
        val below = UsageLedger.step(
            LedgerState(counters = mapOf(200L to used.copy(used = 1)), previousKeysKnown = false),
            LedgerInput(now, BERLIN, 1_000, true, listOf(sched)),
        )
        assertEquals(1, below.state.counters.getValue(200).used)
        // After the seeding tick, leaving and coming back is a launch again.
        val gone = UsageLedger.step(
            out.state,
            LedgerInput(now + SEC, BERLIN, 2_000, true, listOf(sched.copy(matchedKeys = emptySet(), launchKeys = emptySet())))
        )
        val back = UsageLedger.step(gone.state, LedgerInput(now + 40 * SEC, BERLIN, 41_000, true, listOf(sched)))
        assertTrue(back.events.any { it is LedgerEvent.LaunchBlocked })
    }

    // --- 9. Changing the period resets the counter (§5.4) ---
    @Test
    fun periodChangeResetsTheCounter() {
        val hourlyCounter = UsageCounter(100, at("2026-11-23T10:00"), 500, null, null, Period.HOURLY)
        val toDaily = UsageLedger.read(hourlyCounter, timeLimit(5, period = Period.DAILY), at("2026-11-23T10:30"), BERLIN, 0)
        assertEquals(0, toDaily.used)
        assertEquals(Period.DAILY, toDaily.period)
        val dailyCounter = UsageCounter(100, at("2026-11-23T00:00"), 500, null, null, Period.DAILY)
        val toHourly = UsageLedger.read(dailyCounter, timeLimit(5, period = Period.HOURLY), at("2026-11-23T00:30"), BERLIN, 0)
        assertEquals(0, toHourly.used)
        assertEquals(at("2026-11-23T00:00"), toHourly.periodStart)
    }

    // --- 10. Extending a timer that just ended is a refusal, not a crash ---
    @Test
    fun extendTimerHasACheckablePrecondition() {
        val on = StrictModeRow(state = StrictState.ON, method = StrictMethod.TIMER, endsAt = 1000, endsAtElapsed = 500)
        assertTrue(StrictPolicy.canExtendTimer(on, MIN))
        assertFalse(StrictPolicy.canExtendTimer(StrictPolicy.deactivated(on), MIN))
        assertFalse(StrictPolicy.canExtendTimer(on, 0))
    }

    // --- 11. checkStrict compares the whole row ---
    @Test
    fun strictRowEditsCannotTouchBookkeeping() {
        val pin = StrictModeRow(
            state = StrictState.ON, method = StrictMethod.PIN, pinHash = "h", pinSalt = "s", pinFailures = 6,
            pinLockedUntilWall = 5_000, pinLockedUntilElapsed = 4_000, lastCheckWall = 100, lastCheckElapsed = 50, activatedAt = 10,
        )
        val edits = listOf(
            pin.copy(pinFailures = 0),
            pin.copy(pinLockedUntilWall = null, pinLockedUntilElapsed = null),
            pin.copy(lastCheckWall = 100 + day),
            pin.copy(lastCheckElapsed = 0),
            pin.copy(activatedAt = 0),
            pin.copy(endsAtElapsed = 9),
            pin.copy(bootCount = 3),
        )
        for (e in edits) assertEquals(e.toString(), EditCheck.Refused(Refusal.STRICT_CHANGED), WeakenChecker.checkStrict(pin, e))
        assertTrue(WeakenChecker.checkStrict(pin, pin).allowed)
    }

    // --- 13. Counters of limits not processed in a tick survive ---
    @Test
    fun countersSurviveATickWithoutTheirSchedule() {
        val tl = timeLimit(30)
        val now = at("2026-11-23T10:00")
        val c = UsageCounter(100, at("2026-11-23T00:00"), 20 * MIN, null, null, Period.DAILY)
        val sched = LedgerSchedule(1, true, listOf(tl), emptySet())
        val a = UsageLedger.step(LedgerState(counters = mapOf(100L to c)), LedgerInput(now, BERLIN, 1_000, true, listOf(sched)))
        val gap = UsageLedger.step(a.state, LedgerInput(now + SEC, BERLIN, 2_000, true, emptyList())) // e.g. fallback snapshot
        val b = UsageLedger.step(gap.state, LedgerInput(now + 2 * SEC, BERLIN, 3_000, true, listOf(sched)))
        assertEquals(20 * MIN, b.state.counters.getValue(100).used)
        // Through the engine: an empty fallback snapshot in between.
        val engine = Engine()
        val snap = ConfigSnapshot(schedules = listOf(schedule(1, T) { copy(limits = listOf(tl)) }))
        fun eval(s: ConfigSnapshot, st: EngineState, el: Long) = engine.evaluate(
            EvaluationInput(s, clock("2026-11-23T10:00", el), st, foreground = listOf(ForegroundApp(T))),
        )
        val r1 = eval(snap, EngineState(LedgerState(counters = mapOf(100L to c))), 1_000)
        val r2 = eval(ConfigSnapshot(), r1.state, 2_000)
        val r3 = eval(snap, r2.state, 3_000)
        assertTrue(r3.state.ledger.counters.getValue(100).used >= 20 * MIN)
    }

    // --- 14. ALL→ANY with a single non-time trigger type is allowed ---
    @Test
    fun allToAnyWithoutWindowsIsAllowed() {
        val wifiAll = Schedule(id = 1, name = "W", combine = Combine.ALL, wifi = wifi("Office"), apps = listOf("a.one"))
        assertEquals(EditCheck.Allowed, WeakenChecker.checkSchedule(wifiAll, wifiAll.copy(combine = Combine.ANY), at("2026-11-23T10:00")))
        val loc = LocationCondition(id = 3, label = "Home", lat = 1.0, lon = 2.0, radiusM = 200)
        val locAll = wifiAll.copy(wifi = emptyList(), locations = listOf(loc))
        assertEquals(EditCheck.Allowed, WeakenChecker.checkSchedule(locAll, locAll.copy(combine = Combine.ANY), at("2026-11-23T10:00")))
        // Still refused: the other direction, and removing every window of a timed schedule.
        assertEquals(
            EditCheck.Refused(Refusal.COMBINE_TIGHTENED),
            WeakenChecker.checkSchedule(wifiAll.copy(combine = Combine.ANY), wifiAll, at("2026-11-23T10:00"))
        )
        val timed = wifiAll.copy(timeWindows = listOf(win(127, "09:00", "17:00")))
        assertEquals(
            EditCheck.Refused(Refusal.TIME_COVERAGE_REDUCED),
            WeakenChecker.checkSchedule(timed, timed.copy(timeWindows = emptyList(), combine = Combine.ANY), at("2026-11-23T10:00"))
        )
    }
}
