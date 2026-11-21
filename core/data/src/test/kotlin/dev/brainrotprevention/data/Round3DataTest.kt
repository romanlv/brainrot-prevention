package dev.brainrotprevention.data

import dev.brainrotprevention.engine.Refusal
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.StrictState
import dev.brainrotprevention.model.TimeWindow
import dev.brainrotprevention.model.Weekdays
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Regression tests for review round 3 (data layer). */
@RunWith(RobolectricTestRunner::class)
class Round3DataTest : GraphTest() {
    private val locked = WriteResult.Locked(Refusal.UNBOUNDED_UNDER_STRICT)

    private fun timed(name: String, start: Int, end: Int, followed: Boolean = false) = schedule(name).copy(
        timeWindows = listOf(TimeWindow(days = Weekdays.ALL, startMin = start, endMin = end)),
        limits = emptyList(),
        strictFollowed = followed,
    )

    private fun schedulesStrict(followAll: Boolean) = StrictModeRow(
        state = StrictState.ON,
        options = StrictOptions.LOCK_EDITING,
        method = StrictMethod.SCHEDULES,
        followAllSchedules = followAll,
        activatedAt = T0,
    )

    /** Major 1: a second followed schedule that closes the week's last free run is refused, on create and on edit. */
    @Test
    fun followedSchedulesCantCoverTheWeekTogether() = runBlocking {
        g.schedules.save(timed("Morning", 0, 720)).ok()
        g.strict.writePolicyResult(schedulesStrict(followAll = true))
        assertEquals(locked, g.schedules.save(timed("Afternoon", 720, 1440)))
        assertEquals(locked, g.schedules.save(timed("Almost", 725, 1440)))
        val id = g.schedules.save(timed("Afternoon", 720, 1430)).ok()
        // Widening the second one so the gap is under 10 minutes.
        val cur = g.schedules.get(id)!!
        val wider = cur.copy(timeWindows = cur.timeWindows.map { it.copy(endMin = 1435) })
        assertEquals(locked, g.schedules.save(wider))
        assertEquals(1, g.schedules.getAll().count { it.name == "Afternoon" })
        assertFalse(g.schedules.getAll().any { it.name == "Almost" })
    }

    /** Minor 2: follow-all off→on is refused when the newly followed items would block with no break. */
    @Test
    fun followAllCantBeTurnedOnIntoALockIn() = runBlocking {
        g.schedules.save(timed("Work", 540, 1020, followed = true)).ok()
        g.quickBlock.save(QuickBlock(apps = listOf("com.q.a"))).ok()
        val off = schedulesStrict(followAll = false)
        g.strict.writePolicyResult(off)
        // Quick Block isn't followed without follow-all, so it may run with no timer.
        g.quickBlock.start(until = null).ok()
        assertEquals(locked, g.strict.edit(off.copy(followAllSchedules = true)))
        assertFalse(g.strict.get().followAllSchedules)
        // With a timer the follow-all edit goes through.
        g.strict.writePolicyResult(StrictModeRow())
        g.quickBlock.stop().ok()
        g.quickBlock.start(until = T0 + 60_000).ok()
        g.strict.writePolicyResult(off)
        g.strict.edit(off.copy(followAllSchedules = true)).ok()
        assertTrue(g.strict.get().followAllSchedules)
    }
}
