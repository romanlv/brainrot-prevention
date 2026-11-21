package dev.brainrotprevention.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotprevention.data.db.BrpDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Schema v1 from the exported `schemas/…/1.json` (SPEC §4.2). Every schema change adds a
 * `runMigrationsAndValidate(DB, n, true, MIGRATION_x_n)` case here (or an auto-migration check).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), BrpDatabase::class.java)

    @Test
    fun version1IsCreatedFromTheExportedSchemaAndOpensWithRoom() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO schedule (name, icon, sortOrder, enabled, mode, combine, allowlistWeb, blockNotifications, " +
                    "blockUnsupportedBrowsers, pausedUntil, manualUntil, strictFollowed, createdAt, updatedAt) " +
                    "VALUES ('Old', '', 0, 1, 'BLOCKLIST', 'ANY', 'OPEN', 0, 1, NULL, NULL, 0, 1, 1)"
            )
        }
        // Validates the on-disk schema against 1.json.
        helper.runMigrationsAndValidate(DB, 1, true).close()
        // v1 → v2 (auto-migration: usage_counter.period/holdUntil, strict_mode.bootCount), validated against 2.json.
        helper.runMigrationsAndValidate(DB, 2, true).close()

        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val room = Room.databaseBuilder(ctx, BrpDatabase::class.java, DB).build()
        try {
            assertEquals(listOf("Old"), runBlocking { room.schedules().getAll().map { it.schedule.name } })
        } finally {
            room.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
