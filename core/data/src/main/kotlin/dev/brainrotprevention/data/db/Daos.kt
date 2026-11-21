package dev.brainrotprevention.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import dev.brainrotprevention.model.LocationState
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Transaction
    @Query("SELECT * FROM schedule ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<ScheduleWithChildren>>

    @Transaction
    @Query("SELECT * FROM schedule WHERE id = :id")
    fun observe(id: Long): Flow<ScheduleWithChildren?>

    @Transaction
    @Query("SELECT * FROM schedule ORDER BY sortOrder, id")
    suspend fun getAll(): List<ScheduleWithChildren>

    @Transaction
    @Query("SELECT * FROM schedule WHERE id = :id")
    suspend fun get(id: Long): ScheduleWithChildren?

    @Insert
    suspend fun insert(s: ScheduleEntity): Long

    /** Never REPLACE a schedule: that would delete and cascade its child rows (PL-M2). */
    @Update
    suspend fun update(s: ScheduleEntity)

    @Query("DELETE FROM schedule WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM schedule")
    suspend fun deleteAll()

    @Query("UPDATE schedule SET pausedUntil = :until, updatedAt = :now WHERE id = :id")
    suspend fun setPausedUntil(id: Long, until: Long?, now: Long)

    @Query("UPDATE schedule SET manualUntil = :until, updatedAt = :now WHERE id = :id")
    suspend fun setManualUntil(id: Long, until: Long?, now: Long)

    @Query("UPDATE schedule SET enabled = :enabled, pausedUntil = NULL, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)

    @Query("UPDATE schedule SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE schedule SET pausedUntil = NULL WHERE id IN (:ids) AND pausedUntil IS NOT NULL AND pausedUntil <= :now")
    suspend fun clearExpiredPauses(ids: List<Long>, now: Long)

    @Query("UPDATE schedule SET manualUntil = NULL WHERE id IN (:ids) AND manualUntil IS NOT NULL AND manualUntil <= :now")
    suspend fun clearExpiredManual(ids: List<Long>, now: Long)

    @Query("UPDATE location_condition SET lastState = :state WHERE id = :rowId")
    suspend fun setLocationState(rowId: Long, state: LocationState)

    // ---- child rows: upserted in place by the repository (§4 conventions) ----
    @Insert suspend fun insertWindows(rows: List<TimeWindowEntity>)

    @Update suspend fun updateWindows(rows: List<TimeWindowEntity>)

    @Query("DELETE FROM time_window WHERE id IN (:ids)")
    suspend fun deleteWindows(ids: List<Long>)

    @Insert suspend fun insertLimits(rows: List<UsageLimitEntity>)

    @Update suspend fun updateLimits(rows: List<UsageLimitEntity>)

    @Query("DELETE FROM usage_limit WHERE id IN (:ids)")
    suspend fun deleteLimits(ids: List<Long>)

    @Insert suspend fun insertWifi(rows: List<WifiConditionEntity>)

    @Update suspend fun updateWifi(rows: List<WifiConditionEntity>)

    @Query("DELETE FROM wifi_condition WHERE id IN (:ids)")
    suspend fun deleteWifi(ids: List<Long>)

    @Insert suspend fun insertLocations(rows: List<LocationConditionEntity>)

    @Update suspend fun updateLocations(rows: List<LocationConditionEntity>)

    @Query("DELETE FROM location_condition WHERE id IN (:ids)")
    suspend fun deleteLocations(ids: List<Long>)

    @Insert suspend fun insertApps(rows: List<ListAppEntity>)

    @Update suspend fun updateApps(rows: List<ListAppEntity>)

    @Query("DELETE FROM list_app WHERE id IN (:ids)")
    suspend fun deleteApps(ids: List<Long>)

    @Insert suspend fun insertWebRules(rows: List<WebRuleEntity>)

    @Update suspend fun updateWebRules(rows: List<WebRuleEntity>)

    @Query("DELETE FROM web_rule WHERE id IN (:ids)")
    suspend fun deleteWebRules(ids: List<Long>)
}

@Dao
interface QuickBlockDao {
    @Query("SELECT * FROM quick_block WHERE id = 1")
    fun observeRow(): Flow<QuickBlockEntity?>

    @Query("SELECT * FROM quick_block WHERE id = 1")
    suspend fun getRow(): QuickBlockEntity?

    @Upsert suspend fun upsertRow(row: QuickBlockEntity)

    @Query("SELECT * FROM quick_block_app ORDER BY packageName")
    fun observeApps(): Flow<List<QuickBlockAppEntity>>

    @Query("SELECT * FROM quick_block_app ORDER BY packageName")
    suspend fun getApps(): List<QuickBlockAppEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertApps(rows: List<QuickBlockAppEntity>)

    @Query("DELETE FROM quick_block_app WHERE packageName IN (:pkgs)")
    suspend fun deleteApps(pkgs: List<String>)

    @Query("SELECT * FROM quick_block_web ORDER BY kind, value")
    fun observeWeb(): Flow<List<QuickBlockWebEntity>>

    @Query("SELECT * FROM quick_block_web ORDER BY kind, value")
    suspend fun getWeb(): List<QuickBlockWebEntity>

    /** Keyed by (kind, value): replacing only updates anywhereInUrl. No child rows hang off it. */
    @Upsert suspend fun upsertWeb(rows: List<QuickBlockWebEntity>)

    @Delete suspend fun deleteWeb(rows: List<QuickBlockWebEntity>)
}

@Dao
interface StrictDao {
    @Query("SELECT * FROM strict_mode WHERE id = 1")
    fun observe(): Flow<StrictModeEntity?>

    @Query("SELECT * FROM strict_mode WHERE id = 1")
    suspend fun get(): StrictModeEntity?

    @Upsert suspend fun upsert(row: StrictModeEntity)

    @Query("SELECT * FROM emergency_unblock ORDER BY at, id")
    fun observeEmergencyLog(): Flow<List<EmergencyUnblockEntity>>

    @Query("SELECT * FROM emergency_unblock ORDER BY at, id")
    suspend fun getEmergencyLog(): List<EmergencyUnblockEntity>

    @Insert suspend fun insertEmergency(row: EmergencyUnblockEntity): Long

    @Query("DELETE FROM emergency_unblock")
    suspend fun clearEmergencyLog()

    @Insert suspend fun insertEmergencyAll(rows: List<EmergencyUnblockEntity>)
}

@Dao
interface ExcludedBrowserDao {
    @Query("SELECT * FROM excluded_browser ORDER BY packageName")
    fun observe(): Flow<List<ExcludedBrowserEntity>>

    @Query("SELECT * FROM excluded_browser ORDER BY packageName")
    suspend fun getAll(): List<ExcludedBrowserEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(rows: List<ExcludedBrowserEntity>)

    @Query("DELETE FROM excluded_browser WHERE packageName IN (:pkgs)")
    suspend fun delete(pkgs: List<String>)

    @Query("DELETE FROM excluded_browser")
    suspend fun deleteAll()
}

@Dao
interface UsageCounterDao {
    @Query("SELECT * FROM usage_counter")
    fun observeAll(): Flow<List<UsageCounterEntity>>

    @Query("SELECT * FROM usage_counter")
    suspend fun getAll(): List<UsageCounterEntity>

    @Query("SELECT id FROM usage_limit WHERE id IN (:ids)")
    suspend fun existingLimitIds(ids: List<Long>): List<Long>

    @Upsert suspend fun upsert(rows: List<UsageCounterEntity>)

    @Query("DELETE FROM usage_counter")
    suspend fun deleteAll()
}

@Dao
interface PausePresetDao {
    @Query("SELECT * FROM pause_preset ORDER BY lastUsedAt DESC, id DESC")
    fun observe(): Flow<List<PausePresetEntity>>

    @Query("SELECT * FROM pause_preset ORDER BY lastUsedAt DESC, id DESC")
    suspend fun getAll(): List<PausePresetEntity>

    @Query("SELECT * FROM pause_preset WHERE durationMin = :durationMin")
    suspend fun byDuration(durationMin: Int): PausePresetEntity?

    @Insert suspend fun insert(row: PausePresetEntity): Long

    @Update suspend fun update(row: PausePresetEntity)

    @Query("DELETE FROM pause_preset WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Dao
interface NotificationCountDao {
    @Query("SELECT * FROM notification_count WHERE day = :day ORDER BY count DESC, packageName")
    fun observeDay(day: String): Flow<List<NotificationCountEntity>>

    @Query("SELECT * FROM notification_count WHERE packageName = :pkg AND day = :day")
    suspend fun get(pkg: String, day: String): NotificationCountEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(row: NotificationCountEntity): Long

    @Query("UPDATE notification_count SET count = count + 1 WHERE packageName = :pkg AND day = :day")
    suspend fun bump(pkg: String, day: String)

    @Transaction
    suspend fun increment(pkg: String, day: String) {
        if (insertIgnore(NotificationCountEntity(pkg, day, 1)) == -1L) bump(pkg, day)
    }

    @Query("DELETE FROM notification_count WHERE day < :day")
    suspend fun deleteBefore(day: String)
}
