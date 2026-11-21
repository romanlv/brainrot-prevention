package dev.brainrotprevention.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Schema v2 (SPEC §4). Schemas are exported to `core/data/schemas/`; every change ships a migration test.
 * v2: `usage_counter.period`, `usage_counter.holdUntil`, `strict_mode.bootCount` (nullable, auto-migrated).
 */
@Database(
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
    entities = [
        ScheduleEntity::class,
        TimeWindowEntity::class,
        UsageLimitEntity::class,
        UsageCounterEntity::class,
        WifiConditionEntity::class,
        LocationConditionEntity::class,
        ListAppEntity::class,
        WebRuleEntity::class,
        QuickBlockEntity::class,
        QuickBlockAppEntity::class,
        QuickBlockWebEntity::class,
        PausePresetEntity::class,
        StrictModeEntity::class,
        EmergencyUnblockEntity::class,
        ExcludedBrowserEntity::class,
        NotificationCountEntity::class,
    ],
)
abstract class BrpDatabase : RoomDatabase() {
    abstract fun schedules(): ScheduleDao

    abstract fun quickBlock(): QuickBlockDao

    abstract fun strict(): StrictDao

    abstract fun excludedBrowsers(): ExcludedBrowserDao

    abstract fun usageCounters(): UsageCounterDao

    abstract fun pausePresets(): PausePresetDao

    abstract fun notificationCounts(): NotificationCountDao

    companion object {
        const val NAME = "brp.db"

        fun open(context: Context, name: String = NAME): BrpDatabase =
            Room.databaseBuilder(context.applicationContext, BrpDatabase::class.java, name).build()

        fun inMemory(context: Context): BrpDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, BrpDatabase::class.java).build()
    }
}
