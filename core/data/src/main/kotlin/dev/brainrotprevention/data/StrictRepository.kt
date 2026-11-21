package dev.brainrotprevention.data

import dev.brainrotprevention.data.db.EmergencyUnblockEntity
import dev.brainrotprevention.data.db.toEntity
import dev.brainrotprevention.data.db.toModel
import dev.brainrotprevention.engine.EditGuard
import dev.brainrotprevention.engine.StrictPolicy
import dev.brainrotprevention.engine.TamperCheck
import dev.brainrotprevention.engine.ValidationError
import dev.brainrotprevention.model.EmergencyUnblock
import dev.brainrotprevention.model.StrictModeRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The strict_mode row and the emergency log (§9). State transitions are computed by `StrictPolicy`
 * (activate, unlock, tamper check, emergency); this repository persists their results.
 */
class StrictRepository internal constructor(private val c: DataCore) {
    private val dao get() = c.db.strict()

    val strict: Flow<StrictModeRow> = c.db.strict().observe().map { it.toModel() }.distinctUntilChanged()

    val emergencyLog: Flow<List<EmergencyUnblock>> =
        c.db.strict().observeEmergencyLog().map { l -> l.map { it.toModel() } }.distinctUntilChanged()

    suspend fun get(): StrictModeRow = dao.get().toModel()

    suspend fun getEmergencyLog(): List<EmergencyUnblock> = dao.getEmergencyLog().map { it.toModel() }

    /** A user edit of the row (extend TIMER, follow all). While ON, EditGuard.checkStrict restricts it. */
    suspend fun edit(new: StrictModeRow): WriteResult<Unit> = c.write { g ->
        WriteResult.locked(EditGuard.checkStrict(g.strict, new))?.let { return@write it }
        dao.upsert(new.toEntity())
        WriteResult.ok()
    }

    /**
     * Extends the running TIMER by [extraMs], reading the row inside the write lock. `NotFound` when no TIMER
     * is running any more (for example it expired between the tap and the write), instead of throwing.
     */
    suspend fun extendTimer(extraMs: Long): WriteResult<Unit> = c.write { g ->
        val row = g.strict
        if (!StrictPolicy.canExtendTimer(row, extraMs)) return@write WriteResult.NotFound("running Strict Mode timer")
        if (!StrictPolicy.extensionWithinMax(row, extraMs, g.now)) {
            return@write WriteResult.Invalid(
                ValidationError("strict.endsAt", "Strict Mode can't be set to end more than 30 days from now.")
            )
        }
        val new = StrictPolicy.extendTimer(row, extraMs)
        WriteResult.locked(EditGuard.checkStrict(row, new))?.let { return@write it }
        dao.upsert(new.toEntity())
        WriteResult.ok()
    }

    /**
     * Persists a row returned by a `StrictPolicy` transition (activate, unlock*, deactivated, afterBoot,
     * wrong-PIN bookkeeping). Not guarded: StrictPolicy already enforced the rules.
     */
    suspend fun writePolicyResult(row: StrictModeRow) = c.plain { dao.upsert(row.toEntity()) }

    /** Persists `StrictPolicy.check` (row + skew-adjusted log) in one transaction; no-op if unchanged. */
    suspend fun writeTamperCheck(t: TamperCheck) {
        if (!t.changed) return
        c.plain {
            c.tx {
                dao.upsert(t.row.toEntity())
                dao.clearEmergencyLog()
                dao.insertEmergencyAll(t.emergencyLog.map { EmergencyUnblockEntity(it.id, it.at) })
            }
        }
    }

    /** Persists `EmergencyResult.Done(row, entry)`: the INACTIVE row plus the new log entry. */
    suspend fun writeEmergency(row: StrictModeRow, entry: EmergencyUnblock): Long = c.plain {
        c.tx {
            dao.upsert(row.toEntity())
            dao.insertEmergency(EmergencyUnblockEntity(0, entry.at))
        }
    }
}
