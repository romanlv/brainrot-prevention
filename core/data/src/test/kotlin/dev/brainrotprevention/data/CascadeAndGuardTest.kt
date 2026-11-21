package dev.brainrotprevention.data

import dev.brainrotprevention.engine.Refusal
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.UsageCounter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CascadeAndGuardTest : GraphTest() {
    private suspend fun count(table: String): Int =
        withContext(Dispatchers.IO) {
            g.db.query("SELECT COUNT(*) FROM $table", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
        }

    @Test
    fun deletingAScheduleCascadesToEveryChildAndCounter() = runBlocking {
        val keep = g.schedules.save(schedule("Keep")).ok()
        val id = g.schedules.save(schedule().copy(wifi = listOf(dev.brainrotprevention.model.WifiCondition(ssid = "x")))).ok()
        val limitId = g.schedules.get(id)!!.limits.single().id
        g.counters.save(listOf(UsageCounter(limitId, T0, 1)))
        g.schedules.delete(id).ok()
        assertEquals(listOf(keep), g.schedules.getAll().map { it.id })
        for (t in listOf("time_window", "usage_limit", "list_app", "web_rule")) assertEquals(t, 1, count(t))
        assertEquals(0, count("wifi_condition"))
        assertEquals(0, count("usage_counter"))
    }

    @Test
    fun weakeningEditsAreRefusedWhileStrictIsActive() = runBlocking {
        val id = g.schedules.save(schedule(apps = listOf("com.a.x", "com.a.y"))).ok()
        g.quickBlock.save(QuickBlock(apps = listOf("com.q.a"))).ok()
        g.quickBlock.start(until = null).ok()
        g.strict.writePolicyResult(strictOn())
        val s = g.schedules.get(id)!!

        assertEquals(WriteResult.Locked(Refusal.DELETED), g.schedules.delete(id))
        assertEquals(WriteResult.Locked(Refusal.PAUSED), g.schedules.pause(id, T0 + 900_000))
        assertEquals(WriteResult.Locked(Refusal.DISABLED), g.schedules.setEnabled(id, false))
        assertEquals(WriteResult.Locked(Refusal.BLOCKLIST_ITEM_REMOVED), g.schedules.save(s.copy(apps = listOf("com.a.x"))))
        assertEquals(WriteResult.Locked(Refusal.QUICK_BLOCK_STOPPED), g.quickBlock.stop())
        assertEquals(WriteResult.Locked(Refusal.EXCLUDED_BROWSER_ADDED), g.settings.excludeFromBlockScreen("com.b"))
        assertEquals(WriteResult.Locked(Refusal.EXCLUDED_BROWSER_ADDED), g.settings.setExcludedBrowsers(setOf("com.b")))
        val p = g.settings.getPrefs()
        assertEquals(WriteResult.Locked(Refusal.SETTING_LOCKED), g.settings.savePrefs(p.copy(dayStartHour = 3)))
        assertEquals(WriteResult.Locked(Refusal.STRICT_CHANGED), g.strict.edit(g.strict.get().copy(options = 0)))
        // Nothing was written.
        assertEquals(s, g.schedules.get(id))
        assertTrue(g.quickBlock.get().active)

        // Strengthening is still allowed.
        g.schedules.save(s.copy(apps = s.apps + "com.a.z")).ok()
        g.settings.savePrefs(p.copy(limitWarningMin = 9)).ok()
        g.strict.edit(g.strict.get().let { it.copy(endsAt = it.endsAt!! + 60_000, endsAtElapsed = it.endsAtElapsed!! + 60_000) }).ok()
    }

    @Test
    fun withoutLockEditingOnlyBlockScreenExclusionAndImportAreRefused() = runBlocking {
        val id = g.schedules.save(schedule()).ok()
        g.strict.writePolicyResult(strictOn(options = StrictOptions.BLOCK_SETTINGS))
        g.schedules.pause(id, T0 + 900_000).ok()
        assertEquals(WriteResult.Locked(Refusal.EXCLUDED_BROWSER_ADDED), g.settings.excludeFromBlockScreen("com.b"))
        assertEquals(WriteResult.Locked(Refusal.IMPORT_LOCKED), g.backup.import(g.backup.exportText()))
    }

    @Test
    fun cleanupClearsOnlyExpiredValues() = runBlocking {
        val id = g.schedules.save(schedule()).ok()
        g.schedules.pause(id, T0 + 1000).ok()
        g.schedules.startNow(id, T0 + 5000).ok()
        clock.wall = T0 + 2000
        g.schedules.applyCleanup(dev.brainrotprevention.engine.Cleanup(listOf(id), listOf(id), false, null), clock.wall)
        val s = g.schedules.get(id)!!
        assertEquals(null, s.pausedUntil)
        assertEquals(T0 + 5000, s.manualUntil)
        g.quickBlock.save(QuickBlock(apps = listOf("com.q.a"))).ok()
        g.quickBlock.start(T0 + 3000).ok()
        g.quickBlock.markExpired() // now = T0 + 2000: not expired yet
        assertEquals(true, g.snapshots.current().quickBlock.active)
        clock.wall = T0 + 3000
        g.quickBlock.markExpired()
        assertEquals(false, g.snapshots.current().quickBlock.active)
    }

    /** Review round 1, #12: the engine saw the old Quick Block expire, the user restarted it, then cleanup ran. */
    @Test
    fun markExpiredDoesNotStopARestartedQuickBlock() = runBlocking {
        g.quickBlock.save(QuickBlock(apps = listOf("com.q.a"))).ok()
        g.quickBlock.start(T0 + 1000).ok()
        clock.wall = T0 + 2000 // the engine computes quickBlockExpired here …
        g.quickBlock.start(null).ok() // … the user restarts it (no timer) …
        g.quickBlock.markExpired() // … and the cleanup lands.
        assertEquals(true, g.quickBlock.get().active)
        g.quickBlock.start(T0 + 60_000).ok()
        g.quickBlock.markExpired()
        assertEquals(true, g.quickBlock.get().active)
    }

    /** Review round 1, #12: save() refuses an active Quick Block without items (§5.5), like start(). */
    @Test
    fun activeEmptyQuickBlockIsRefusedBySave() = runBlocking {
        val r = g.quickBlock.save(QuickBlock(active = true))
        assertTrue(r.describe(), r is WriteResult.Invalid)
        assertEquals(false, g.quickBlock.get().active)
        g.quickBlock.save(QuickBlock(active = false)).ok()
    }

    /** Review round 1, #10: extending a timer that already ended is a NotFound result, not an exception. */
    @Test
    fun extendTimerAfterTheTimerEndedIsARefusal() = runBlocking {
        val r = g.strict.extendTimer(60_000)
        assertTrue(r.describe(), r is WriteResult.NotFound)
        g.strict.writePolicyResult(strictOn())
        val before = g.strict.get()
        g.strict.extendTimer(60_000).ok()
        assertEquals(before.endsAt!! + 60_000, g.strict.get().endsAt)
        assertEquals(before.endsAtElapsed!! + 60_000, g.strict.get().endsAtElapsed)
        g.strict.writePolicyResult(dev.brainrotprevention.engine.StrictPolicy.deactivated(g.strict.get()))
        assertTrue(g.strict.extendTimer(60_000) is WriteResult.NotFound)
    }

    /** Review round 1, #1: in STANDBY, excluded browsers and dayStartHour are locked too. */
    @Test
    fun standbyLocksExcludedBrowsersAndDayStart() = runBlocking {
        // A followed schedule that is not armed now (its window is in the past) → STANDBY.
        val id = g.schedules.save(
            schedule().copy(
                timeWindows = listOf(
                    dev.brainrotprevention.model.TimeWindow(days = dev.brainrotprevention.model.Weekdays.ALL, startMin = 0, endMin = 60)
                ),
                limits = emptyList(),
                blockUnsupportedBrowsers = true,
            )
        ).ok()
        g.strict.writePolicyResult(
            dev.brainrotprevention.model.StrictModeRow(
                state = dev.brainrotprevention.model.StrictState.ON,
                options = StrictOptions.LOCK_EDITING,
                method = dev.brainrotprevention.model.StrictMethod.SCHEDULES,
                followAllSchedules = true,
            )
        )
        assertEquals(dev.brainrotprevention.model.StrictDisplayState.STANDBY, g.strictGate.display(g.snapshots.current()))
        assertEquals(WriteResult.Locked(Refusal.EXCLUDED_BROWSER_ADDED), g.settings.setExcludedBrowsers(setOf("org.mozilla.focus")))
        assertEquals(WriteResult.Locked(Refusal.SETTING_LOCKED), g.settings.savePrefs(g.settings.getPrefs().copy(dayStartHour = 12)))
        g.settings.savePrefs(g.settings.getPrefs().copy(limitWarningMin = 9)).ok()
        assertTrue(g.schedules.get(id) != null)
    }
}
