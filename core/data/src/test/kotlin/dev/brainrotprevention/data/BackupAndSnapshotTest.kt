package dev.brainrotprevention.data

import androidx.test.core.app.ApplicationProvider
import dev.brainrotprevention.engine.Refusal
import dev.brainrotprevention.model.BackupCodec
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.UsageCounter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupAndSnapshotTest : GraphTest() {
    private fun seeds() = seedsDir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.also { assertTrue(it.size >= 16) }

    @Test
    fun everySeedRoundTripsExportImportExport() = runBlocking {
        for (seed in seeds()) {
            g.wipe()
            g.backup.import(seed.readText()).ok()
            val a = g.backup.export()
            val seedBackup = BackupCodec.decode(seed.readText())
            assertEquals(seed.name, seedBackup.schedules.map { it.name }.sorted(), a.schedules.map { it.name }.sorted())
            g.wipe()
            assertTrue(g.schedules.getAll().isEmpty())
            clock.wall += 1000
            g.backup.import(BackupCodec.encode(a)).ok()
            val b = g.backup.export()
            assertEquals(seed.name, a.copy(exportedAt = 0), b.copy(exportedAt = 0))
            assertEquals(seed.name, BackupCodec.encode(a.copy(exportedAt = 0)), BackupCodec.encode(b.copy(exportedAt = 0)))
        }
    }

    @Test
    fun exportLeavesOutRuntimeStateAndImportResetsIt() = runBlocking {
        val id = g.schedules.save(schedule()).ok()
        g.schedules.pause(id, T0 + 60_000).ok()
        g.counters.save(listOf(UsageCounter(g.schedules.get(id)!!.limit(LimitKind.TIME)!!.id, T0, 99)))
        g.pausePresets.recordUse(20, T0)
        g.quickBlock.save(dev.brainrotprevention.model.QuickBlock(apps = listOf("com.q.a"))).ok()
        g.quickBlock.start(T0 + 60_000).ok()
        val text = g.backup.exportText()
        val runtimeKeys = listOf(
            "pausedUntil", "manualUntil", "\"active\"", "\"until\"", "\"id\"", "createdAt", "updatedAt", "used", "pinHash", "emergency",
        )
        for (k in runtimeKeys) {
            assertFalse("$k exported", text.contains(k))
        }
        g.backup.import(text).ok()
        val s = g.schedules.getAll().single()
        assertEquals(null, s.pausedUntil)
        assertEquals(null, s.manualUntil)
        assertTrue("counters belong to the replaced schedules", g.counters.loadAll().isEmpty())
        assertEquals(1, g.pausePresets.getAll().size)
        assertTrue("Quick Block active/until is kept", g.quickBlock.get().active)
    }

    @Test
    fun importIsRefusedWhileStrictModeIsOn() = runBlocking {
        g.schedules.save(schedule("Mine")).ok()
        g.strict.writePolicyResult(strictOn())
        val r = g.backup.import(seeds().first().readText())
        assertEquals(WriteResult.Locked(Refusal.IMPORT_LOCKED), r)
        assertEquals(listOf("Mine"), g.schedules.getAll().map { it.name })
    }

    @Test
    fun invalidImportIsRejectedAsAWholeWithTheFirstOffendingRow() = runBlocking {
        g.schedules.save(schedule("Mine")).ok()
        g.settings.savePrefs(g.settings.getPrefs().copy(limitWarningMin = 7)).ok()
        val good = BackupCodec.decode(seedsDir.resolve("S11.json").readText())
        val bad = good.copy(
            schedules = good.schedules + good.schedules[0].copy(name = "Bad", apps = listOf("not a package")) +
                good.schedules[0].copy(name = ""),
            prefs = good.prefs.copy(limitWarningMin = 1),
        )
        val r = g.backup.import(BackupCodec.encode(bad))
        assertTrue(r.describe(), r is WriteResult.Invalid)
        assertEquals("schedules[1].apps[0]", (r as WriteResult.Invalid).error.path)
        assertEquals(listOf("Mine"), g.schedules.getAll().map { it.name })
        assertEquals(7, g.settings.getPrefs().limitWarningMin)
        assertTrue(g.backup.import("{\"format\":1,\"bogus\":true}") is WriteResult.Invalid)
        assertTrue(g.backup.import("not json") is WriteResult.Invalid)
    }

    @Test
    fun snapshotIsCachedAndUsedWhenTheDatabaseFails() = runBlocking {
        val facts = PlatformFacts(exemptPackages = setOf("com.launcher"), ownPackage = "dev.brainrotprevention")
        g.snapshots.setPlatformFacts(facts)
        g.schedules.save(schedule("Cached")).ok()
        val live = g.snapshots.current()
        assertTrue(g.snapshots.cacheFile.isFile)
        assertEquals(live, g.snapshots.readCache())

        // Unchanged content keeps the same instance (engine per-instance cache).
        assertSame(live, g.snapshots.rebuild())

        // A fresh store (new process) whose DB can't be read falls back to the cache, facts included.
        val other = DataGraph.inMemory(ApplicationProvider.getApplicationContext(), clock, dir.resolve("other"))
        dir.resolve("snapshot.json").copyTo(other.snapshots.cacheFile.also { it.parentFile!!.mkdirs() })
        other.db.close()
        val fallback = other.snapshots.current()
        assertEquals(listOf("Cached"), fallback.schedules.map(Schedule::name))
        assertEquals(setOf("com.launcher"), fallback.exemptPackages)
        other.close()
    }

    @Test
    fun corruptCacheIsIgnoredAndFactsComeFromTheCacheOnColdStart() = runBlocking {
        g.snapshots.cacheFile.writeText("{ not json")
        assertEquals(null, g.snapshots.readCache())
        assertEquals(ConfigSnapshot(), g.snapshots.current())

        val other = DataGraph.inMemory(ApplicationProvider.getApplicationContext(), clock, dir.resolve("cold"))
        other.snapshots.cacheFile.parentFile!!.mkdirs()
        other.snapshots.cacheFile.writeText(
            SnapshotStore.json.encodeToString(ConfigSnapshot.serializer(), ConfigSnapshot(ownPackage = "x.y"))
        )
        assertEquals("x.y", other.snapshots.current().ownPackage)
        assertNotNull(other.snapshots.snapshot.value)
        other.close()
    }

    @Test
    fun wipeEndsStrictModeAndClearsEverything() = runBlocking {
        g.schedules.save(schedule()).ok()
        g.strict.writePolicyResult(strictOn())
        g.strict.writeEmergency(g.strict.get(), dev.brainrotprevention.model.EmergencyUnblock(at = T0))
        g.settings.savePrefs(g.settings.getPrefs().copy(limitWarningMin = 9)).ok()
        g.wipe()
        val s = g.snapshots.current()
        assertFalse(s.strict.isOn)
        assertTrue(s.schedules.isEmpty())
        assertTrue(s.emergencyLog.isEmpty())
        assertEquals(5, s.prefs.limitWarningMin)
        assertEquals(s, g.snapshots.readCache())
    }
}
