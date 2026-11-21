package dev.brainrotprevention.data

import android.database.sqlite.SQLiteConstraintException
import dev.brainrotprevention.data.db.ListAppEntity
import dev.brainrotprevention.data.db.UsageCounterEntity
import dev.brainrotprevention.data.db.UsageLimitEntity
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.LocationCondition
import dev.brainrotprevention.model.LocationState
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.UsageCounter
import dev.brainrotprevention.model.WifiCondition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DaoTest : GraphTest() {
    @Test
    fun scheduleRoundTripsWithAllChildren() = runBlocking {
        val s = schedule().copy(
            wifi = listOf(WifiCondition(ssid = "Office")),
            locations = listOf(LocationCondition(label = "Home", lat = 52.5, lon = 13.4, radiusM = 200)),
        )
        val id = g.schedules.save(s).ok()
        val back = g.schedules.get(id)!!
        assertEquals("Work", back.name)
        assertEquals(T0, back.createdAt)
        assertEquals(listOf("com.example.a"), back.apps)
        assertEquals(listOf("Office"), back.wifi.map { it.ssid })
        assertEquals(LocationState.UNKNOWN, back.locations.single().lastState)
        assertEquals(s.timeWindows.map { it.copy(id = 0) }, back.timeWindows.map { it.copy(id = 0) })
        assertEquals(1, g.schedules.schedules.first().size)
    }

    @Test
    fun usageLimitIsUniquePerScheduleAndKind() = runBlocking {
        val id = g.schedules.save(schedule()).ok()
        try {
            g.db.schedules().insertLimits(listOf(UsageLimitEntity(0, id, LimitKind.TIME, Period.HOURLY, 60_000)))
            fail("duplicate (scheduleId, kind) accepted")
        } catch (_: SQLiteConstraintException) {
        }
        try {
            g.db.schedules().insertApps(listOf(ListAppEntity(0, id, "com.example.a")))
            fail("duplicate (scheduleId, packageName) accepted")
        } catch (_: SQLiteConstraintException) {
        }
    }

    @Test
    fun counterNeedsAnExistingLimit() = runBlocking {
        try {
            g.db.usageCounters().upsert(listOf(UsageCounterEntity(999, T0, 1, null, null)))
            fail("counter without limit accepted")
        } catch (_: SQLiteConstraintException) {
        }
        // The store drops rows of deleted limits instead of failing.
        val id = g.schedules.save(schedule()).ok()
        val limitId = g.schedules.get(id)!!.limits.single().id
        g.counters.save(listOf(UsageCounter(limitId, T0, 5), UsageCounter(999, T0, 1)))
        assertEquals(mapOf(limitId to UsageCounter(limitId, T0, 5)), g.counters.loadAll())
        assertEquals(5L, g.counters.counters.first()[limitId]?.used)
    }

    @Test
    fun notificationCountsIncrementPerPackageAndDay() = runBlocking {
        repeat(3) { g.notificationCounts.increment("com.chat", "2026-11-23") }
        g.notificationCounts.increment("com.chat", "2026-11-22")
        g.notificationCounts.increment("com.mail", "2026-11-23")
        assertEquals(3, g.notificationCounts.get("com.chat", "2026-11-23"))
        assertEquals(mapOf("com.chat" to 3, "com.mail" to 1), g.notificationCounts.countsFor("2026-11-23").first())
        g.notificationCounts.pruneBefore("2026-11-23")
        assertEquals(0, g.notificationCounts.get("com.chat", "2026-11-22"))
    }

    @Test
    fun pausePresetsKeepTheLastThreeDistinctDurations() = runBlocking {
        g.pausePresets.recordUse(20, 1)
        g.pausePresets.recordUse(45, 2)
        g.pausePresets.recordUse(90, 3)
        g.pausePresets.recordUse(20, 4)
        g.pausePresets.recordUse(120, 5)
        assertEquals(listOf(120, 20, 90), g.pausePresets.getAll().map { it.durationMin })
        assertEquals(listOf(120, 20, 90), g.pausePresets.presets.first().map { it.durationMin })
    }

    @Test
    fun strictRowAndEmergencyLog() = runBlocking {
        assertFalse(g.strict.get().isOn)
        g.strict.writePolicyResult(strictOn())
        assertTrue(g.strict.strict.first().isOn)
        g.strict.writeEmergency(
            dev.brainrotprevention.engine.StrictPolicy.deactivated(g.strict.get()),
            dev.brainrotprevention.model.EmergencyUnblock(at = T0)
        )
        assertFalse(g.strict.get().isOn)
        assertEquals(listOf(T0), g.strict.getEmergencyLog().map { it.at })
        assertTrue(g.snapshots.current().emergencyLog.isNotEmpty())
    }

    @Test
    fun excludedBrowsersAndPrefs() = runBlocking {
        g.settings.setExcludedBrowsers(setOf("com.b1", "com.b2")).ok()
        g.settings.setExcludedBrowsers(setOf("com.b2", "com.b3")).ok()
        assertEquals(setOf("com.b2", "com.b3"), g.settings.excludedBrowsers.first())
        g.settings.excludeFromBlockScreen("com.b4").ok()
        assertEquals(setOf("com.b2", "com.b3", "com.b4"), g.snapshots.current().excludedBrowsers)

        val p = g.settings.getPrefs()
        assertEquals(0, p.dayStartHour)
        g.settings.savePrefs(p.copy(dayStartHour = 4, limitWarningMin = 10)).ok()
        g.settings.getPrefs().let {
            assertEquals(0, it.dayStartHour)
            assertEquals(4, it.pendingDayStartHour)
            assertEquals(10, it.limitWarningMin)
        }
        g.settings.applyDayStartHour(4)
        g.settings.getPrefs().let {
            assertEquals(4, it.dayStartHour)
            assertNull(it.pendingDayStartHour)
        }
        assertTrue(g.settings.savePrefs(p.copy(limitWarningMin = 500)) is WriteResult.Invalid)
        g.settings.setOnboardingDone(true)
        assertTrue(g.settings.prefs.first().onboardingDone)
        g.settings.setLastPackageSnapshot(setOf("a.b"))
        assertEquals(setOf("a.b"), g.settings.lastPackageSnapshot.first())
    }

    @Test
    fun invalidScheduleIsRejected() = runBlocking {
        val r = g.schedules.save(schedule().copy(name = ""))
        assertTrue(r is WriteResult.Invalid)
        assertEquals("schedule.name", (r as WriteResult.Invalid).error.path)
        assertTrue(g.schedules.save(schedule().copy(id = 42)) is WriteResult.NotFound)
        assertTrue(g.schedules.getAll().isEmpty())
    }
}
