package dev.brainrotprevention.data

import dev.brainrotprevention.engine.Refusal
import dev.brainrotprevention.engine.ScheduleValidator
import dev.brainrotprevention.model.LocationCondition
import dev.brainrotprevention.model.LocationState
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.StrictState
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Regression tests for the round-2 reviews (data layer). */
@RunWith(RobolectricTestRunner::class)
class Round2DataTest : GraphTest() {
    private fun rules(n: Int) = (1..n).map { WebRule(kind = WebRuleKind.DOMAIN, value = "site$it.example") }

    /** Diff minor 3: an editor save (stale restored draft) keeps the runtime fields of the current row. */
    @Test
    fun editorSaveKeepsRuntimeFieldsOfTheCurrentRow() = runBlocking {
        val loc = LocationCondition(label = "Home", lat = 52.5, lon = 13.4, radiusM = 200)
        val id = g.schedules.save(schedule().copy(locations = listOf(loc))).ok()
        val stale = g.schedules.get(id)!!
        // Meanwhile: paused, started, and the geofence reported INSIDE.
        g.schedules.pause(id, T0 + 3_600_000).ok()
        g.schedules.startNow(id, Schedule.UNTIL_STOPPED).ok()
        g.schedules.setLocationState(stale.locations.single().id, LocationState.INSIDE)
        g.schedules.save(stale.copy(name = "Renamed"), keepRuntime = true).ok()
        val now = g.schedules.get(id)!!
        assertEquals("Renamed", now.name)
        assertEquals(T0 + 3_600_000, now.pausedUntil)
        assertEquals(Schedule.UNTIL_STOPPED, now.manualUntil)
        assertEquals(LocationState.INSIDE, now.locations.single().lastState)
        // A plain save still writes what it's given (debug/import paths).
        g.schedules.save(now.copy(pausedUntil = null)).ok()
        assertEquals(null, g.schedules.get(id)!!.pausedUntil)
    }

    /** Diff minor 3, scenario B: under Strict, the stale draft is not refused for MANUAL_SHORTENED. */
    @Test
    fun staleDraftUnderStrictIsNotRefusedForRuntimeFields() = runBlocking {
        val id = g.schedules.save(schedule()).ok()
        val stale = g.schedules.get(id)!!
        g.schedules.startNow(id, T0 + 3_600_000).ok()
        g.strict.writePolicyResult(strictOn())
        g.schedules.save(stale.copy(apps = stale.apps + "com.example.more"), keepRuntime = true).ok()
        assertEquals(T0 + 3_600_000, g.schedules.get(id)!!.manualUntil)
    }

    /** Adversarial 1: at most 2,000 web rules per schedule, in Quick Block and in an import. */
    @Test
    fun webRulesAreCappedPerSchedule() = runBlocking {
        val max = ScheduleValidator.MAX_WEB_RULES
        g.schedules.save(schedule().copy(webRules = rules(max))).ok()
        val r = g.schedules.save(schedule("Big").copy(webRules = rules(max + 1)))
        assertTrue(r.describe(), r is WriteResult.Invalid && r.error.message.contains("2,000"))
        val q = g.quickBlock.save(QuickBlock(webRules = rules(max + 1)))
        assertTrue(q.describe(), q is WriteResult.Invalid)
        val big = (1..max + 1).joinToString(",") { """{"kind":"DOMAIN","value":"s$it.example"}""" }
        val text = """{"format":1,"schedules":[{"name":"X","apps":["com.a.b"],"webRules":[$big]}]}"""
        val imp = g.backup.import(text)
        assertTrue(imp.describe(), imp is WriteResult.Invalid && imp.error.message.contains("2,000"))
    }

    /** Adversarial 7: Strict SCHEDULES + LOCK_EDITING refuses starting unbounded followed items. */
    @Test
    fun schedulesStrictRefusesUnboundedStarts() = runBlocking {
        val id = g.schedules.save(schedule().copy(limits = emptyList())).ok()
        g.quickBlock.save(QuickBlock(apps = listOf("com.q.a"))).ok()
        g.strict.writePolicyResult(
            StrictModeRow(
                state = StrictState.ON,
                options = StrictOptions.LOCK_EDITING,
                method = StrictMethod.SCHEDULES,
                followAllSchedules = true
            ),
        )
        val locked = WriteResult.Locked(Refusal.UNBOUNDED_UNDER_STRICT)
        assertEquals(locked, g.quickBlock.start(until = null))
        assertEquals(locked, g.schedules.startNow(id, Schedule.UNTIL_STOPPED))
        assertEquals(locked, g.schedules.save(schedule("Always").copy(timeWindows = emptyList(), limits = emptyList())))
        // Bounded starts are fine.
        g.quickBlock.start(until = T0 + 60_000).ok()
        g.schedules.startNow(id, T0 + 60_000).ok()
    }

    /** Adversarial minor 10: the timer can't be extended past 30 days from now. */
    @Test
    fun timerExtensionIsCapped() = runBlocking {
        g.strict.writePolicyResult(strictOn())
        val day = 24 * 3_600_000L
        val r = g.strict.extendTimer(30 * day)
        assertTrue(r.describe(), r is WriteResult.Invalid)
        g.strict.extendTimer(29 * day).ok()
    }
}
