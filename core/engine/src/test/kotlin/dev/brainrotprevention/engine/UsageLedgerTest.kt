package dev.brainrotprevention.engine

import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.UsageCounter
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLedgerTest {
    private val tl = timeLimit(5)
    private val ll = launchLimit(3)

    /** Small driver: wall and elapsed move together unless jumped. */
    private class Driver(start: String, val limits: List<dev.brainrotprevention.model.UsageLimit>) {
        var now = at(start)
        var elapsed = 1_000_000L
        var state = LedgerState()
        var events = listOf<LedgerEvent>()
        var saved = listOf<UsageCounter>()
        var armed = true
        var interactive = true
        var dayStartHour = 0
        var pending: Int? = null
        var warnMin = 1
        var full = false

        fun tick(keys: Set<String>, launch: Set<String> = keys): Driver {
            val out = UsageLedger.step(
                state,
                LedgerInput(
                    now, BERLIN, elapsed, interactive,
                    listOf(
                        LedgerSchedule(1, armed, limits, keys, launch)
                    ),
                    dayStartHour, pending, warnMin, full
                ),
            )
            state = out.state
            events = out.events
            saved = out.countersToSave
            return this
        }

        fun advance(ms: Long): Driver {
            now += ms
            elapsed += ms
            return this
        }

        fun jump(ms: Long): Driver {
            now += ms
            return this
        }

        fun counter(id: Long) = state.counters.getValue(id)
    }

    @Test
    fun timeCountsClampedAtMostOncePerTick() {
        val d = Driver("2026-11-23T10:00", listOf(tl))
        d.tick(setOf(T))
        d.advance(1_000).tick(setOf(T, "com.other")) // two matches, still once per tick
        assertEquals(1_000, d.counter(100).used)
        d.advance(60_000).tick(setOf(T)) // release clamp 15 s
        assertEquals(16_000, d.counter(100).used)
        d.full = true
        d.advance(60_000).tick(setOf(T)) // debug: counts in full
        assertEquals(76_000, d.counter(100).used)
        d.advance(1_000).tick(emptySet()) // nothing in front
        assertEquals(76_000, d.counter(100).used)
        d.armed = false
        d.advance(1_000).tick(setOf(T))
        assertEquals(76_000, d.counter(100).used)
    }

    @Test
    fun noCountingAcrossScreenOff() {
        val d = Driver("2026-11-23T10:00", listOf(tl))
        d.tick(setOf(T)).advance(1_000).tick(setOf(T))
        d.interactive = false
        d.advance(600_000).tick(setOf(T))
        d.interactive = true
        d.advance(10_000).tick(setOf(T))
        assertEquals(1_000, d.counter(100).used)
        d.advance(1_000).tick(setOf(T))
        assertEquals(2_000, d.counter(100).used)
    }

    @Test
    fun periodResetForwardAndBackwardClock() {
        data class C(val period: Period, val stored: String, val now: String, val expectUsed: Long, val expectStart: String)
        val cases = listOf(
            C(Period.DAILY, "2026-11-23T00:00", "2026-11-23T23:59", 500, "2026-11-23T00:00"),
            C(Period.DAILY, "2026-11-23T00:00", "2026-11-24T00:01", 0, "2026-11-24T00:00"),
            C(Period.DAILY, "2026-11-24T00:00", "2026-11-23T12:00", 500, "2026-11-23T00:00"), // clock went back: keep used
            C(Period.HOURLY, "2026-11-23T10:00", "2026-11-23T10:59", 500, "2026-11-23T10:00"),
            C(Period.HOURLY, "2026-11-23T10:00", "2026-11-23T11:00", 0, "2026-11-23T11:00"),
            C(Period.HOURLY, "2026-11-23T11:00", "2026-11-23T10:30", 500, "2026-11-23T10:00"),
        )
        for (c in cases) {
            val limit = timeLimit(5, period = c.period)
            val stored = UsageCounter(100, at(c.stored), 500, null, at(c.stored))
            val r = UsageLedger.read(stored, limit, at(c.now), BERLIN, 0)
            assertEquals(c.toString(), c.expectUsed, r.used)
            assertEquals(c.toString(), at(c.expectStart), r.periodStart)
            if (c.expectUsed == 0L) assertNull(r.warnedPeriodStart) else assertEquals(r.periodStart, r.warnedPeriodStart)
        }
    }

    @Test
    fun dayStartHourAndPending() {
        // dayStartHour 4: at 03:00 the period is yesterday's 04:00.
        assertEquals(at("2026-11-22T04:00"), Periods.dailyStart(at("2026-11-23T03:00"), BERLIN, 4))
        assertEquals(at("2026-11-23T04:00"), Periods.dailyEnd(at("2026-11-23T03:00"), BERLIN, 4))
        assertEquals(at("2026-11-23T04:00") - 1, Periods.endOfToday(at("2026-11-23T03:00"), BERLIN, 4))
        val d = Driver("2026-11-23T22:00", listOf(tl))
        d.pending = 4
        d.tick(setOf(T))
        assertFalse(d.events.any { it is LedgerEvent.DayStartHourApplied }) // first read creates the counter
        d.advance(1_000).tick(setOf(T))
        assertFalse(d.events.any { it is LedgerEvent.DayStartHourApplied })
        d.advance(2 * Periods.HOUR).tick(setOf(T)) // crosses midnight: DAILY reset with the old hour
        assertTrue(d.events.contains(LedgerEvent.DayStartHourApplied(4)))
        assertTrue(d.events.any { it is LedgerEvent.PeriodReset })
        // Without DAILY limits the pending hour applies at once.
        val h = Driver("2026-11-23T22:00", listOf(timeLimit(5, period = Period.HOURLY)))
        h.pending = 6
        h.tick(setOf(T))
        assertTrue(h.events.contains(LedgerEvent.DayStartHourApplied(6)))
    }

    @Test
    fun sharedBudgetAcrossApps() {
        val d = Driver("2026-11-23T10:00", listOf(tl))
        d.full = true
        d.tick(setOf("a.one"))
        d.advance(2 * MIN).tick(setOf("a.one"))
        d.advance(1).tick(setOf("b.two"))
        d.advance(3 * MIN).tick(setOf("b.two"))
        assertTrue(UsageLedger.exhausted(tl, d.counter(100)))
    }

    @Test
    fun launchGateThreeAllowedFourthBlocked() {
        val d = Driver("2026-11-23T10:00", listOf(ll))
        repeat(3) { i ->
            d.tick(setOf(T))
            assertTrue(d.events.any { it is LedgerEvent.LaunchCounted && it.used == (i + 1).toLong() })
            // Running session: stays in front 5 s, never gated.
            repeat(5) { d.advance(1_000).tick(setOf(T)) }
            assertFalse(d.state.gated(1, T))
            d.advance(1_000).tick(emptySet())
            d.advance(31_000)
        }
        d.tick(setOf(T))
        assertTrue(d.events.any { it is LedgerEvent.LaunchBlocked })
        assertTrue(d.state.gated(1, T))
        assertEquals(3, d.counter(200).used) // not incremented
        d.advance(1_000).tick(setOf(T))
        assertTrue(d.state.gated(1, T)) // stays gated while in front
        d.advance(1_000).tick(emptySet())
        assertFalse(d.state.gated(1, T))
    }

    @Test
    fun antiBounceAndScreenOff() {
        val d = Driver("2026-11-23T10:00", listOf(ll))
        d.tick(setOf(T)).advance(1_000).tick(emptySet())
        d.advance(10_000).tick(setOf(T)) // S6: relaunch within 30 s is not a launch
        assertEquals(1, d.counter(200).used)
        d.advance(60_000).tick(setOf(T)) // still in front
        // Screen off then on within 30 s with the same item: not a launch.
        d.interactive = false
        d.advance(5_000).tick(setOf(T))
        d.interactive = true
        d.advance(5_000).tick(setOf(T))
        assertEquals(1, d.counter(200).used)
        // Screen off for > 30 s: a launch.
        d.advance(1_000).tick(setOf(T))
        d.interactive = false
        d.advance(1_000).tick(emptySet())
        d.interactive = true
        d.advance(40_000).tick(setOf(T))
        assertEquals(2, d.counter(200).used)
        // Secondary (floating) members never count.
        d.advance(1_000).tick(emptySet())
        d.advance(40_000).tick(setOf("float.app"), launch = emptySet())
        assertEquals(2, d.counter(200).used)
        // Not armed: tracked but not counted; arming while in front is not a new launch.
        d.armed = false
        d.advance(1_000).tick(emptySet())
        d.advance(40_000).tick(setOf(T))
        d.armed = true
        d.advance(1_000).tick(setOf(T))
        assertEquals(2, d.counter(200).used)
    }

    @Test
    fun limitWarningOnce() {
        val d = Driver("2026-11-23T10:00", listOf(tl))
        d.full = true
        d.tick(setOf(T))
        repeat(3) { d.advance(MIN).tick(setOf(T)) }
        assertFalse(d.events.any { it is LedgerEvent.LimitWarning })
        d.advance(MIN).tick(setOf(T))
        val w = d.events.filterIsInstance<LedgerEvent.LimitWarning>().single()
        assertEquals(MIN, w.timeLeftMs)
        assertEquals(d.counter(100).periodStart, d.counter(100).warnedPeriodStart)
        d.advance(30_000).tick(setOf(T))
        assertFalse(d.events.any { it is LedgerEvent.LimitWarning })
    }

    @Test
    fun saveCadence() {
        val d = Driver("2026-11-23T10:00", listOf(tl))
        d.tick(setOf(T))
        assertTrue(d.saved.isNotEmpty()) // first save
        d.advance(1_000).tick(setOf(T))
        assertTrue(d.saved.isEmpty())
        repeat(9) { d.advance(1_000).tick(setOf(T)) }
        assertTrue(d.saved.isNotEmpty()) // ≥ 10 s
        d.advance(1_000).tick(setOf("other.app")) // change of target
        assertTrue(d.saved.isNotEmpty())
    }

    /** Property: within one period, used never decreases and never exceeds the sum of clamped deltas. */
    @Test
    fun propertyMonotoneAndBounded() {
        val rnd = Random(7)
        repeat(20) {
            val d = Driver("2026-11-23T10:00", listOf(timeLimit(600)))
            var bound = 0L
            var last = 0L
            d.tick(setOf(T))
            repeat(200) {
                val step = rnd.nextLong(0, 40_000)
                d.advance(step)
                val keys = if (rnd.nextBoolean()) setOf(T) else emptySet()
                d.tick(keys)
                if (keys.isNotEmpty()) bound += minOf(step, UsageLedger.MAX_TICK_DELTA_MS)
                val used = d.counter(100).used
                assertTrue(used >= last)
                assertTrue(used <= bound)
                last = used
            }
        }
    }
}
