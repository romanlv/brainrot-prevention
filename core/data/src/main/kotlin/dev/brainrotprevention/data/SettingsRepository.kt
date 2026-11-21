package dev.brainrotprevention.data

import dev.brainrotprevention.data.db.ExcludedBrowserEntity
import dev.brainrotprevention.engine.BackupValidator
import dev.brainrotprevention.engine.EditGuard
import dev.brainrotprevention.engine.Refusal
import dev.brainrotprevention.model.BackupPrefs
import dev.brainrotprevention.model.Prefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** DataStore prefs (§4.1) and the excluded-browser list (§7.7). */
class SettingsRepository internal constructor(private val c: DataCore) {
    val prefs: Flow<Prefs> = c.prefs.prefs

    val excludedBrowsers: Flow<Set<String>> =
        c.db.excludedBrowsers().observe().map { l -> l.mapTo(LinkedHashSet()) { it.packageName } as Set<String> }.distinctUntilChanged()

    val lastPackageSnapshot: Flow<Set<String>> = c.prefs.lastPackageSnapshot

    /** Experimental "X: keep on Following" switch. Freely switchable, also during Strict Mode. */
    val xKeepFollowing: Flow<Boolean> = c.prefs.xKeepFollowing

    suspend fun setXKeepFollowing(on: Boolean) = c.prefs.setXKeepFollowing(on)

    suspend fun getPrefs(): Prefs = c.prefs.get()

    suspend fun getExcludedBrowsers(): Set<String> = c.db.excludedBrowsers().getAll().mapTo(LinkedHashSet()) { it.packageName }

    /**
     * Settings screen save. Changing the day start writes `pendingDayStartHour` (applied by the engine at the
     * next daily reset, §5.4); pass the wanted hour in [new].dayStartHour. Ranges as in the backup rules.
     * Locked fields: dayStartHour while Strict Mode is ACTIVE with LOCK_EDITING.
     */
    suspend fun savePrefs(new: Prefs): WriteResult<Unit> = c.write { g ->
        val old = g.snapshot.prefs
        val wanted = new.dayStartHour
        val effective = old.pendingDayStartHour ?: old.dayStartHour
        val n = when {
            wanted == effective -> new.copy(dayStartHour = old.dayStartHour, pendingDayStartHour = old.pendingDayStartHour)
            wanted == old.dayStartHour -> new.copy(pendingDayStartHour = null)
            else -> new.copy(dayStartHour = old.dayStartHour, pendingDayStartHour = wanted)
        }
        BackupValidator.validatePrefs(
            BackupPrefs(wanted, n.limitWarningMin, n.notifyBeforeStartMin, n.notifyBeforePauseEndMin, n.hidePauseOption),
        )?.let { return@write WriteResult.Invalid(it) }
        WriteResult.locked(EditGuard.checkPrefs(g.strict, g.display, old, n))?.let { return@write it }
        c.prefs.write(n.copy(onboardingDone = old.onboardingDone))
        WriteResult.ok()
    }

    /** Engine event `DayStartHourApplied(hour)`: `dayStartHour = hour`, pending cleared. Not guarded. */
    suspend fun applyDayStartHour(hour: Int) = c.plain {
        c.prefs.write(c.prefs.get().copy(dayStartHour = hour, pendingDayStartHour = null))
    }

    suspend fun setOnboardingDone(done: Boolean) = c.plain { c.prefs.write(c.prefs.get().copy(onboardingDone = done)) }

    suspend fun setLastPackageSnapshot(pkgs: Set<String>) = c.prefs.setLastPackageSnapshot(pkgs)

    /** Settings list edit; adding is a weakening (EXCLUDED_BROWSER_ADDED while ACTIVE + LOCK_EDITING). */
    suspend fun setExcludedBrowsers(new: Set<String>): WriteResult<Unit> = c.write { g ->
        WriteResult.locked(EditGuard.checkExcludedBrowsers(g.strict, g.display, g.snapshot.excludedBrowsers, new))
            ?.let { return@write it }
        c.tx { writeExcluded(new) }
        WriteResult.ok()
    }

    /** Block screen "Not a browser? Allow" (§7.7): refused whenever Strict Mode is ON. */
    suspend fun excludeFromBlockScreen(pkg: String): WriteResult<Unit> = c.write { g ->
        if (!EditGuard.canExcludeBrowserFromBlockScreen(g.strict)) return@write WriteResult.Locked(Refusal.EXCLUDED_BROWSER_ADDED)
        c.db.excludedBrowsers().insert(listOf(ExcludedBrowserEntity(pkg)))
        WriteResult.ok()
    }

    internal suspend fun writeExcluded(new: Set<String>) {
        val d = c.db.excludedBrowsers()
        val old = d.getAll().map { it.packageName }.toSet()
        (old - new).takeIf { it.isNotEmpty() }?.let { d.delete(it.toList()) }
        d.insert((new - old).map { ExcludedBrowserEntity(it) })
    }
}
