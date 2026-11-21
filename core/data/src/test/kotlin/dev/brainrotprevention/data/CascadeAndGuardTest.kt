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
        g.quickBlock.markExpired()
        assertEquals(false, g.snapshots.current().quickBlock.active)
    }
}
