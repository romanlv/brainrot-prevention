package dev.brainrotprevention.data

import androidx.room.withTransaction
import dev.brainrotprevention.data.db.BrpDatabase
import dev.brainrotprevention.data.db.PausePresetEntity
import dev.brainrotprevention.data.db.toEntity
import dev.brainrotprevention.data.db.toModel
import dev.brainrotprevention.model.UsageCounter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * usage_counter persistence for the engine ledger (§5.4). Seed `LedgerState(counters = loadAll())` on the
 * first evaluation; upsert `EvaluationResult.countersToSave` with [save]. Counters are not part of the
 * snapshot, so saving doesn't rebuild it.
 */
class UsageCounterStore internal constructor(private val db: BrpDatabase) {
    val counters: Flow<Map<Long, UsageCounter>> =
        db.usageCounters().observeAll().map { l -> l.associate { it.limitId to it.toModel() } }.distinctUntilChanged()

    suspend fun loadAll(): Map<Long, UsageCounter> = db.usageCounters().getAll().associate { it.limitId to it.toModel() }

    /** Upserts [changed]; rows whose limit was deleted meanwhile are dropped (no FK failure). */
    suspend fun save(changed: Collection<UsageCounter>) {
        if (changed.isEmpty()) return
        db.withTransaction {
            val live = db.usageCounters().existingLimitIds(changed.map { it.limitId }).toSet()
            db.usageCounters().upsert(changed.filter { it.limitId in live }.map { it.toEntity() })
        }
    }
}

data class PausePreset(val id: Long, val durationMin: Int, val lastUsedAt: Long)

/** The last 3 custom pause durations (§8, pause_preset). */
class PausePresetRepository internal constructor(private val db: BrpDatabase) {
    val presets: Flow<List<PausePreset>> =
        db.pausePresets().observe().map { l -> l.map { PausePreset(it.id, it.durationMin, it.lastUsedAt) } }.distinctUntilChanged()

    suspend fun getAll(): List<PausePreset> = db.pausePresets().getAll().map { PausePreset(it.id, it.durationMin, it.lastUsedAt) }

    /** Records a custom duration use and keeps only the newest [KEEP]. */
    suspend fun recordUse(durationMin: Int, at: Long) {
        require(durationMin > 0) { "durationMin must be > 0" }
        val d = db.pausePresets()
        db.withTransaction {
            val ex = d.byDuration(durationMin)
            if (ex == null) d.insert(PausePresetEntity(0, durationMin, at)) else d.update(ex.copy(lastUsedAt = at))
            val extra = d.getAll().drop(KEEP).map { it.id }
            if (extra.isNotEmpty()) d.delete(extra)
        }
    }

    companion object {
        const val KEEP = 3
    }
}

/** Per-package hidden-notification counts per day (`yyyy-MM-dd`, §10.4). Counts only, no content. */
class NotificationCountRepository internal constructor(private val db: BrpDatabase) {
    /** package → count for [day], highest first. */
    fun countsFor(day: String): Flow<Map<String, Int>> =
        db.notificationCounts().observeDay(day).map { l -> l.associate { it.packageName to it.count } }.distinctUntilChanged()

    suspend fun get(pkg: String, day: String): Int = db.notificationCounts().get(pkg, day)?.count ?: 0

    suspend fun increment(pkg: String, day: String) = db.notificationCounts().increment(pkg, day)

    /** Drops days before [day]. */
    suspend fun pruneBefore(day: String) = db.notificationCounts().deleteBefore(day)
}
