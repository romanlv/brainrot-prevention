package dev.brainrotprevention.data

import dev.brainrotprevention.engine.BackupValidation
import dev.brainrotprevention.engine.BackupValidator
import dev.brainrotprevention.engine.EditGuard
import dev.brainrotprevention.model.Backup
import dev.brainrotprevention.model.BackupCodec

data class ImportSummary(val schedules: Int, val quickBlockApps: Int, val quickBlockWebRules: Int)

/**
 * Backup export/import (§4.3) through `BackupCodec` / `BackupValidator`. Export leaves out counters,
 * pause/Start now, Quick Block active/until, strict row, emergency log, presets, notification counts and
 * onboarding state (the codec only carries the exported fields).
 */
class BackupRepository internal constructor(
    private val c: DataCore,
    private val schedules: ScheduleRepository,
    private val quickBlock: QuickBlockRepository,
    private val settings: SettingsRepository,
) {
    suspend fun export(): Backup {
        val s = c.snapshots.load()
        return BackupCodec.fromConfig(s.schedules, s.quickBlock, s.excludedBrowsers, s.prefs, c.clock.now())
    }

    suspend fun exportText(): String = BackupCodec.encode(export())

    /**
     * Refused while Strict Mode is ON (Locked IMPORT_LOCKED). The whole file is validated first; the first
     * offending row is returned as Invalid and nothing is written. Then, in one Room transaction, every
     * schedule (and with it every usage counter) and all Quick Block items are replaced, as are the excluded
     * browsers; Quick Block active/until is kept. Prefs (DataStore) are written right after the commit,
     * with `dayStartHour` applied directly (no counters survive an import) and onboarding state kept.
     */
    suspend fun import(text: String): WriteResult<ImportSummary> = c.write { g ->
        WriteResult.locked(EditGuard.checkImport(g.strict))?.let { return@write it }
        val backup = when (val v = BackupValidator.validateText(text)) {
            is BackupValidation.Invalid -> return@write WriteResult.Invalid(v.error)
            is BackupValidation.Valid -> v.backup
        }
        val plan = BackupCodec.toImportPlan(backup, g.now)
        c.tx {
            c.db.schedules().deleteAll()
            plan.schedules.forEach { schedules.writeSchedule(it, null, g.now) }
            val qb = g.snapshot.quickBlock
            quickBlock.write(
                qb.copy(apps = plan.quickBlockApps, webRules = plan.quickBlockWebRules, blockNotifications = plan.quickBlockNotifications),
            )
            settings.writeExcluded(plan.excludedBrowsers)
        }
        val p = plan.prefs
        c.prefs.write(
            g.snapshot.prefs.copy(
                dayStartHour = p.dayStartHour,
                pendingDayStartHour = null,
                limitWarningMin = p.limitWarningMin,
                notifyBeforeStartMin = p.notifyBeforeStartMin,
                notifyBeforePauseEndMin = p.notifyBeforePauseEndMin,
                hidePauseOption = p.hidePauseOption,
            ),
        )
        WriteResult.Ok(ImportSummary(plan.schedules.size, plan.quickBlockApps.size, plan.quickBlockWebRules.size))
    }
}
