package dev.brainrotprevention.data

import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.TimeWindow
import dev.brainrotprevention.model.UsageCounter
import dev.brainrotprevention.model.UsageLimit
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpsertInPlaceTest : GraphTest() {
    @Test
    fun usageCounterSurvivesAScheduleSave() = runBlocking {
        val id = g.schedules.save(schedule()).ok()
        val before = g.schedules.get(id)!!
        val limit = before.limit(LimitKind.TIME)!!
        val counter = UsageCounter(limit.id, T0, 12 * 60_000L, T0 - 1000, null)
        g.counters.save(listOf(counter))

        // Typical editor save: rename, raise the allowance, add a window, an app and a rule (fresh rows have id 0).
        clock.wall += 60_000
        val edited = before.copy(
            name = "Work2",
            limits = listOf(
                limit.copy(allowance = 45 * 60_000L),
                UsageLimit(kind = LimitKind.LAUNCHES, period = Period.HOURLY, allowance = 3)
            ),
            timeWindows = before.timeWindows + TimeWindow(days = 1, startMin = 1320, endMin = 60),
            apps = before.apps + "com.example.b",
            webRules = before.webRules.map { it.copy(anywhereInUrl = false) } + WebRule(kind = WebRuleKind.KEYWORD, value = "Shorts"),
        )
        g.schedules.save(edited).ok()

        val after = g.schedules.get(id)!!
        assertEquals("Work2", after.name)
        assertEquals(T0, after.createdAt)
        assertEquals(T0 + 60_000, after.updatedAt)
        assertEquals(limit.id, after.limit(LimitKind.TIME)!!.id)
        assertEquals(45 * 60_000L, after.limit(LimitKind.TIME)!!.allowance)
        assertEquals(before.timeWindows.single().id, after.timeWindows.first().id)
        assertEquals(before.webRules.single().id, after.webRules.first { it.kind == WebRuleKind.DOMAIN }.id)
        assertEquals("shorts", after.webRules.first { it.kind == WebRuleKind.KEYWORD }.value) // normalised on save
        assertEquals(counter, g.counters.loadAll()[limit.id])
    }

    @Test
    fun limitMatchedByKindEvenIfTheEditorSendsANewRow() = runBlocking {
        val id = g.schedules.save(schedule()).ok()
        val s = g.schedules.get(id)!!
        val limitId = s.limits.single().id
        g.counters.save(listOf(UsageCounter(limitId, T0, 1000)))
        g.schedules.save(s.copy(limits = listOf(UsageLimit(kind = LimitKind.TIME, period = Period.DAILY, allowance = 60_000)))).ok()
        assertEquals(limitId, g.schedules.get(id)!!.limits.single().id)
        assertEquals(1000L, g.counters.loadAll()[limitId]?.used)
    }

    @Test
    fun removingALimitDropsItsCounter() = runBlocking {
        val id = g.schedules.save(schedule()).ok()
        val s = g.schedules.get(id)!!
        g.counters.save(listOf(UsageCounter(s.limits.single().id, T0, 1000)))
        g.schedules.save(s.copy(limits = emptyList())).ok()
        assertTrue(g.counters.loadAll().isEmpty())
    }

    @Test
    fun removedChildRowsAreDeletedKeptOnesKeepTheirIds() = runBlocking {
        val id = g.schedules.save(schedule(apps = listOf("com.a.x", "com.a.y", "com.a.z"))).ok()
        val s = g.schedules.get(id)!!
        val window = s.timeWindows.single()
        g.schedules.save(s.copy(apps = listOf("com.a.z", "com.a.x"), timeWindows = listOf(window.copy(endMin = 1080)))).ok()
        val after = g.schedules.get(id)!!
        assertEquals(setOf("com.a.x", "com.a.z"), after.apps.toSet())
        assertEquals(listOf(window.copy(endMin = 1080)), after.timeWindows)
    }
}
