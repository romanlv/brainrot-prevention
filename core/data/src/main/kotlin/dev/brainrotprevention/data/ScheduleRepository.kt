package dev.brainrotprevention.data

import dev.brainrotprevention.data.db.ListAppEntity
import dev.brainrotprevention.data.db.LocationConditionEntity
import dev.brainrotprevention.data.db.ScheduleDao
import dev.brainrotprevention.data.db.TimeWindowEntity
import dev.brainrotprevention.data.db.UsageLimitEntity
import dev.brainrotprevention.data.db.WebRuleEntity
import dev.brainrotprevention.data.db.WifiConditionEntity
import dev.brainrotprevention.data.db.syncChildren
import dev.brainrotprevention.data.db.toEntity
import dev.brainrotprevention.data.db.toModel
import dev.brainrotprevention.engine.Cleanup
import dev.brainrotprevention.engine.EditGuard
import dev.brainrotprevention.engine.ScheduleValidator
import dev.brainrotprevention.engine.ValidationError
import dev.brainrotprevention.model.LocationState
import dev.brainrotprevention.model.Schedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Schedules with their child rows (§4.1). Every weakening mutation is checked by EditGuard first. */
class ScheduleRepository internal constructor(private val c: DataCore) {
    private val dao: ScheduleDao get() = c.db.schedules()

    /** All schedules in Home order (sortOrder, id), children included. */
    val schedules: Flow<List<Schedule>> = c.db.schedules().observeAll().map { l -> l.map { it.toModel() } }.distinctUntilChanged()

    fun schedule(id: Long): Flow<Schedule?> = dao.observe(id).map { it?.toModel() }.distinctUntilChanged()

    suspend fun getAll(): List<Schedule> = dao.getAll().map { it.toModel() }

    suspend fun get(id: Long): Schedule? = dao.get(id)?.toModel()

    /**
     * Creates (`id == 0`) or edits a schedule. Web rules are normalised, then the editor rules run, then
     * EditGuard. Child rows are upserted in place: limits by kind, Wi-Fi by SSID, apps by package, web rules
     * by (kind, value), windows and locations by id then value — so usage counters survive an edit (PL-M2).
     * `pausedUntil`/`manualUntil` are taken from [s] too; use [pause]/[startNow] for those.
     * Returns the schedule id.
     */
    suspend fun save(s: Schedule): WriteResult<Long> = c.write { g ->
        val rules = s.webRules.mapIndexed { i, r ->
            ScheduleValidator.normalizeRule(r)
                ?: return@write WriteResult.Invalid(
                    ValidationError("schedule.webRules[$i]", "invalid ${r.kind.name.lowercase()} \"${r.value}\"")
                )
        }
        val n = s.copy(name = s.name.trim(), webRules = rules)
        ScheduleValidator.validate(n)?.let { return@write WriteResult.Invalid(it) }
        val others = g.schedules.filter { it.id != n.id }.sumOf { it.locations.size }
        if (others + n.locations.size > ScheduleValidator.MAX_GEOFENCES) {
            return@write WriteResult.Invalid(
                ValidationError("schedule.locations", "at most ${ScheduleValidator.MAX_GEOFENCES} locations in total")
            )
        }
        val old = if (n.id ==
            0L
        ) {
            null
        } else {
            (g.schedules.firstOrNull { it.id == n.id } ?: return@write WriteResult.NotFound("schedule ${n.id}"))
        }
        WriteResult.locked(EditGuard.checkSchedule(g.strict, g.display, g.schedules, old, n, g.now))?.let { return@write it }
        WriteResult.Ok(c.tx { writeSchedule(n, old, g.now) })
    }

    /** Inserts or updates [s] with its children, in the caller's transaction. Returns the id. */
    internal suspend fun writeSchedule(s: Schedule, old: Schedule?, now: Long): Long {
        val id = if (old == null) {
            dao.insert(s.copy(id = 0, createdAt = now, updatedAt = now).toEntity())
        } else {
            dao.update(s.copy(createdAt = old.createdAt, updatedAt = now).toEntity())
            s.id
        }
        val ex = if (old == null) null else dao.get(id)
        syncChildren(
            ex?.timeWindows.orEmpty(),
            s.timeWindows.map { TimeWindowEntity(it.id, id, it.days, it.startMin, it.endMin) },
            { it.id },
            { Triple(it.days, it.startMin, it.endMin) },
            { e, i -> e.copy(id = i) },
            dao::deleteWindows,
            dao::updateWindows,
            dao::insertWindows,
        )
        syncChildren(
            ex?.limits.orEmpty(), s.limits.map { UsageLimitEntity(it.id, id, it.kind, it.period, it.allowance) },
            { it.id }, { it.kind }, { e, i -> e.copy(id = i) },
            dao::deleteLimits, dao::updateLimits, dao::insertLimits, matchById = false,
        )
        syncChildren(
            ex?.wifi.orEmpty(), s.wifi.map { WifiConditionEntity(it.id, id, it.ssid) },
            { it.id }, { it.ssid }, { e, i -> e.copy(id = i) },
            dao::deleteWifi, dao::updateWifi, dao::insertWifi, matchById = false,
        )
        syncChildren(
            ex?.locations.orEmpty(),
            s.locations.map { LocationConditionEntity(it.id, id, it.label, it.lat, it.lon, it.radiusM, it.inverted, it.lastState) },
            { it.id },
            { listOf(it.label, it.lat, it.lon, it.radiusM, it.inverted) },
            { e, i -> e.copy(id = i) },
            dao::deleteLocations,
            dao::updateLocations,
            dao::insertLocations,
        )
        syncChildren(
            ex?.apps.orEmpty(), s.apps.map { ListAppEntity(0, id, it) },
            { it.id }, { it.packageName }, { e, i -> e.copy(id = i) },
            dao::deleteApps, dao::updateApps, dao::insertApps, matchById = false,
        )
        syncChildren(
            ex?.webRules.orEmpty(), s.webRules.map { WebRuleEntity(it.id, id, it.kind, it.value, it.anywhereInUrl) },
            { it.id }, { it.kind to it.value }, { e, i -> e.copy(id = i) },
            dao::deleteWebRules, dao::updateWebRules, dao::insertWebRules, matchById = false,
        )
        return id
    }

    suspend fun delete(id: Long): WriteResult<Unit> = c.write { g ->
        val s = g.schedules.firstOrNull { it.id == id } ?: return@write WriteResult.NotFound("schedule $id")
        WriteResult.locked(EditGuard.checkDelete(g.strict, g.display, g.schedules, s, g.now))?.let { return@write it }
        dao.delete(id)
        WriteResult.ok()
    }

    /** Pause until [until] (§8); refused while LOCK_EDITING applies. */
    suspend fun pause(id: Long, until: Long): WriteResult<Unit> = c.write { g ->
        val s = g.schedules.firstOrNull { it.id == id } ?: return@write WriteResult.NotFound("schedule $id")
        WriteResult.locked(EditGuard.checkPause(g.strict, g.display, g.schedules, s, until, g.now))?.let { return@write it }
        dao.setPausedUntil(id, until, g.now)
        WriteResult.ok()
    }

    /** Resume: clears `pausedUntil` (a strengthening, never locked). */
    suspend fun resume(
        id: Long
    ): WriteResult<Unit> = setField(id, { it.copy(pausedUntil = null) }) { g -> dao.setPausedUntil(id, null, g.now) }

    /** Turn on/off. Off also clears the pause (§8). */
    suspend fun setEnabled(id: Long, enabled: Boolean): WriteResult<Unit> =
        setField(id, { it.copy(enabled = enabled, pausedUntil = null) }) { g -> dao.setEnabled(id, enabled, g.now) }

    /** Start now until [until] (`Schedule.UNTIL_STOPPED` = until stopped) (§8). */
    suspend fun startNow(id: Long, until: Long): WriteResult<Unit> =
        setField(id, { it.copy(manualUntil = until) }) { g -> dao.setManualUntil(id, until, g.now) }

    /** Stop a Start now; refused (MANUAL_SHORTENED) while locked. */
    suspend fun stopNow(
        id: Long
    ): WriteResult<Unit> = setField(id, { it.copy(manualUntil = null) }) { g -> dao.setManualUntil(id, null, g.now) }

    private suspend fun setField(
        id: Long,
        change: (Schedule) -> Schedule,
        write: suspend (GuardContext) -> Unit,
    ): WriteResult<Unit> = c.write { g ->
        val s = g.schedules.firstOrNull { it.id == id } ?: return@write WriteResult.NotFound("schedule $id")
        WriteResult.locked(EditGuard.checkSchedule(g.strict, g.display, g.schedules, s, change(s), g.now))?.let { return@write it }
        write(g)
        WriteResult.ok()
    }

    /** Home drag-reorder: sortOrder = index in [ids]. Not a policy change. */
    suspend fun reorder(ids: List<Long>) = c.plain { c.tx { ids.forEachIndexed { i, id -> dao.setSortOrder(id, i) } } }

    /** Applies the engine's `EvaluationResult.cleanup` for schedules (expired pause / Start now → null). */
    suspend fun applyCleanup(cleanup: Cleanup, now: Long) {
        if (cleanup.expiredPauses.isEmpty() && cleanup.expiredManual.isEmpty()) return
        c.plain {
            c.tx {
                if (cleanup.expiredPauses.isNotEmpty()) dao.clearExpiredPauses(cleanup.expiredPauses, now)
                if (cleanup.expiredManual.isNotEmpty()) dao.clearExpiredManual(cleanup.expiredManual, now)
            }
        }
    }

    /** Sensor bookkeeping: the last known geofence state of a location row (§11.5). */
    suspend fun setLocationState(rowId: Long, state: LocationState) = c.plain { dao.setLocationState(rowId, state) }
}
