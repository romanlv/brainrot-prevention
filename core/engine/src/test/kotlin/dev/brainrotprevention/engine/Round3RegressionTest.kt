package dev.brainrotprevention.engine

import dev.brainrotprevention.model.Combine
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.StrictState
import dev.brainrotprevention.model.Weekdays
import java.util.BitSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for review round 3 (diff `20fef41..d5d3923`). */
class Round3RegressionTest {
    private val facts =
        StrictFacts(accessibilityConnected = true, usageAccessGranted = true, inAccessibilityShortcut = false, adminActive = true)
    private val now = at("2026-11-23T10:00")
    private val morning = schedule(1, T) { copy(timeWindows = listOf(win(Weekdays.ALL, "00:00", "12:00"))) }
    private val afternoon = schedule(2, T) { copy(timeWindows = listOf(win(Weekdays.ALL, "12:00", "24:00"))) }
    private val schedulesLock = StrictActivation(StrictMethod.SCHEDULES, StrictOptions.LOCK_EDITING, followAllSchedules = true)
    private val onRow = StrictModeRow(
        state = StrictState.ON,
        method = StrictMethod.SCHEDULES,
        options = StrictOptions.LOCK_EDITING,
        followAllSchedules = true,
    )
    private val refused = EditCheck.Refused(Refusal.UNBOUNDED_UNDER_STRICT)

    private fun activation(vararg s: Schedule, qb: QuickBlock = QuickBlock(), req: StrictActivation = schedulesLock) =
        StrictPolicy.checkActivation(req, facts, s.toList(), StrictModeRow(), qb)

    @Test
    fun freeGapNeedsTenMinutesAndWrapsTheWeek() {
        assertEquals(10 * MIN, StrictPolicy.MIN_FREE_GAP_MS)
        val full = BitSet(WeakenChecker.CELLS).apply { set(0, WeakenChecker.CELLS) }
        assertFalse(StrictPolicy.hasFreeGap(full))
        assertTrue(StrictPolicy.hasFreeGap(BitSet(WeakenChecker.CELLS)))
        // A 9-minute hole is too short; a 10-minute one is enough.
        assertFalse(StrictPolicy.hasFreeGap((full.clone() as BitSet).apply { clear(5000, 5009) }))
        assertTrue(StrictPolicy.hasFreeGap((full.clone() as BitSet).apply { clear(5000, 5010) }))
        // 5 free minutes at the end of Sunday + 5 at the start of Monday join up.
        fun wrap(head: Int) = (full.clone() as BitSet).apply {
            clear(0, head)
            clear(WeakenChecker.CELLS - 5, WeakenChecker.CELLS)
        }
        assertTrue(StrictPolicy.hasFreeGap(wrap(5)))
        assertFalse(StrictPolicy.hasFreeGap(wrap(4)))
    }

    @Test
    fun lockCoverageCountsOnlyUnconditionalBlocking() {
        assertEquals(12 * 60 * 7, StrictPolicy.lockCoverage(morning).cardinality())
        // A budget ends at the reset; Wi-Fi / location only: the user can leave; ALL with Wi-Fi: same; off: nothing.
        assertTrue(StrictPolicy.lockCoverage(morning.copy(limits = listOf(timeLimit(30)))).isEmpty)
        assertTrue(StrictPolicy.lockCoverage(schedule(3, T) { copy(wifi = wifi("Home")) }).isEmpty)
        assertTrue(StrictPolicy.lockCoverage(morning.copy(combine = Combine.ALL, wifi = wifi("Home"))).isEmpty)
        assertTrue(StrictPolicy.lockCoverage(morning.copy(enabled = false)).isEmpty)
        // ANY with Wi-Fi: the windows still count.
        assertEquals(12 * 60 * 7, StrictPolicy.lockCoverage(morning.copy(wifi = wifi("Home"))).cardinality())
        // A pause ends by itself, so it doesn't free the time.
        assertEquals(12 * 60 * 7, StrictPolicy.lockCoverage(morning.copy(pausedUntil = now + 60 * MIN)).cardinality())
        // Until stopped / always on / Quick Block without a timer: the whole week.
        assertEquals(WeakenChecker.CELLS, StrictPolicy.lockCoverage(morning.copy(manualUntil = Schedule.UNTIL_STOPPED)).cardinality())
        assertEquals(WeakenChecker.CELLS, StrictPolicy.lockCoverage(schedule(3, T)).cardinality())
        assertEquals(WeakenChecker.CELLS, StrictPolicy.lockCoverage(QuickBlock(active = true, apps = listOf(T))).cardinality())
        assertTrue(StrictPolicy.lockCoverage(QuickBlock(active = true, until = now + MIN, apps = listOf(T))).isEmpty)
    }

    /** Major 1, scenario A: two bounded schedules cover the week between them. */
    @Test
    fun activationIsRefusedWhenFollowedSchedulesCoverTheWeekTogether() {
        assertNull(activation(morning))
        assertNull(activation(afternoon))
        assertEquals(ActivationRefusal.UNBOUNDED_FOLLOWED, activation(morning, afternoon))
        // Overnight window plus a daytime one.
        val night = schedule(3, T) { copy(timeWindows = listOf(win(Weekdays.ALL, "18:00", "08:00"))) }
        val day = schedule(4, T) { copy(timeWindows = listOf(win(Weekdays.ALL, "08:00", "18:00"))) }
        assertEquals(ActivationRefusal.UNBOUNDED_FOLLOWED, activation(night, day))
        // Leaving 10 minutes once a week is enough.
        val sunGap = schedule(5, T) {
            copy(timeWindows = listOf(win(Weekdays.ALL and Weekdays.SUN.inv(), "12:00", "24:00"), win(Weekdays.SUN, "12:00", "23:50")))
        }
        assertNull(activation(morning, sunGap))
        // A budget or a Wi-Fi-only schedule doesn't close the gap.
        assertNull(activation(morning, afternoon.copy(limits = listOf(timeLimit(30)))))
        assertNull(activation(morning, afternoon.copy(combine = Combine.ALL, wifi = wifi("Home"))))
        // Quick Block without a timer still counts on its own, a timed one doesn't.
        assertEquals(ActivationRefusal.UNBOUNDED_FOLLOWED, activation(morning, qb = QuickBlock(active = true, apps = listOf(T))))
        assertNull(activation(morning, qb = QuickBlock(active = true, until = now + MIN, apps = listOf(T))))
        // Only followed schedules count.
        val some = schedulesLock.copy(followAllSchedules = false)
        assertNull(activation(morning.copy(strictFollowed = true), afternoon, req = some))
        assertEquals(
            ActivationRefusal.UNBOUNDED_FOLLOWED,
            activation(morning.copy(strictFollowed = true), afternoon.copy(strictFollowed = true), req = some),
        )
        // Without LOCK_EDITING there's no lock-in.
        assertNull(activation(morning, afternoon, req = schedulesLock.copy(options = 0)))
    }

    /** Major 1, scenario B: gaps shorter than the 5-minute turn-off wait plus margin. */
    @Test
    fun activationIsRefusedWhenGapsAreTooShort() {
        val almost = schedule(1, T) { copy(timeWindows = listOf(win(Weekdays.ALL, "00:00", "23:58"))) }
        assertTrue(StrictPolicy.isUnbounded(almost))
        assertEquals(ActivationRefusal.UNBOUNDED_FOLLOWED, activation(almost))
        val b = schedule(2, T) { copy(timeWindows = listOf(win(Weekdays.ALL, "12:05", "24:00"))) }
        assertEquals(ActivationRefusal.UNBOUNDED_FOLLOWED, activation(morning, b))
        val c = schedule(2, T) { copy(timeWindows = listOf(win(Weekdays.ALL, "12:10", "24:00"))) }
        assertNull(activation(morning, c))
        assertFalse(StrictPolicy.isUnbounded(schedule(1, T) { copy(timeWindows = listOf(win(Weekdays.ALL, "00:00", "23:50"))) }))
    }

    /** Major 1, scenario A step 4: adding the second schedule while Strict is ON. */
    @Test
    fun whileOnEditsThatCloseTheLastGapAreRefused() {
        for (display in listOf(StrictDisplayState.STANDBY, StrictDisplayState.ACTIVE)) {
            val cur = listOf(morning)
            // Creating B.
            assertEquals(refused, EditGuard.checkSchedule(onRow, display, cur, null, afternoon.copy(id = 0), now))
            // Widening A's window over the whole day (a strengthening, so the weaken table allows it).
            val wide = morning.copy(timeWindows = listOf(win(Weekdays.ALL, "00:00", "23:55")))
            assertEquals(refused, EditGuard.checkSchedule(onRow, display, cur, morning, wide, now))
            // Turning B on, removing its budget, following it.
            val all = listOf(morning, afternoon.copy(enabled = false))
            assertEquals(refused, EditGuard.checkSchedule(onRow, display, all, all[1], afternoon, now))
            val budget = listOf(morning, afternoon.copy(limits = listOf(timeLimit(30))))
            assertEquals(refused, EditGuard.checkSchedule(onRow, display, budget, budget[1], afternoon, now))
            val some = onRow.copy(followAllSchedules = false)
            val f = listOf(morning.copy(strictFollowed = true), afternoon)
            assertEquals(refused, EditGuard.checkSchedule(some, display, f, afternoon, afternoon.copy(strictFollowed = true), now))
            // Leaving a 10-minute gap is fine.
            val ok = afternoon.copy(id = 0, timeWindows = listOf(win(Weekdays.ALL, "12:00", "23:50")))
            assertTrue(EditGuard.checkSchedule(onRow, display, cur, null, ok, now).allowed)
            // Quick Block without a timer when the schedules leave a gap is refused as before; with a timer it's fine.
            val qb = QuickBlock(apps = listOf(T))
            assertEquals(refused, EditGuard.checkQuickBlock(onRow, display, cur, qb, qb.copy(active = true), now))
            assertTrue(EditGuard.checkQuickBlock(onRow, display, cur, qb, qb.copy(active = true, until = now + MIN), now).allowed)
        }
        // An unfollowed schedule (followAll off) may close the gap: Strict doesn't wait for it.
        val some = onRow.copy(followAllSchedules = false)
        val f = listOf(morning.copy(strictFollowed = true))
        assertTrue(EditGuard.checkSchedule(some, StrictDisplayState.STANDBY, f, null, afternoon.copy(id = 0), now).allowed)
        // Without LOCK_EDITING, or with another method: allowed.
        val noLock = onRow.copy(options = 0)
        assertTrue(EditGuard.checkSchedule(noLock, StrictDisplayState.STANDBY, listOf(morning), null, afternoon.copy(id = 0), now).allowed)
        val timer = onRow.copy(method = StrictMethod.TIMER)
        assertTrue(EditGuard.checkSchedule(timer, StrictDisplayState.ACTIVE, listOf(morning), null, afternoon.copy(id = 0), now).allowed)
    }

    @Test
    fun alreadyLockedInSetsKeepTheNormalLock() {
        // Already covering the week (for example from before this check): an unrelated strengthening edit is allowed,
        // and weakening edits are still judged by the weaken table.
        val both = listOf(morning, afternoon)
        val more = afternoon.copy(apps = listOf(T, "com.example.more"))
        assertTrue(EditGuard.checkSchedule(onRow, StrictDisplayState.ACTIVE, both, afternoon, more, now).allowed)
        assertEquals(
            EditCheck.Refused(Refusal.DISABLED),
            EditGuard.checkTurnOff(onRow, StrictDisplayState.ACTIVE, both, afternoon, now),
        )
    }
}
