package dev.brainrotprevention.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.brainrotprevention.model.Prefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore preferences of SPEC §4.1. Raw access; guarded writes go through [SettingsRepository]. */
class PrefsStore(private val store: DataStore<Preferences>) {
    private object K {
        val dayStartHour = intPreferencesKey("dayStartHour")
        val pendingDayStartHour = intPreferencesKey("pendingDayStartHour")
        val limitWarningMin = intPreferencesKey("limitWarningMin")
        val notifyBeforeStartMin = intPreferencesKey("notifyBeforeStartMin")
        val notifyBeforePauseEndMin = intPreferencesKey("notifyBeforePauseEndMin")
        val hidePauseOption = booleanPreferencesKey("hidePauseOption")
        val onboardingDone = booleanPreferencesKey("onboardingDone")
        val lastPackageSnapshot = stringSetPreferencesKey("lastPackageSnapshot")
        val xKeepFollowing = booleanPreferencesKey("xKeepFollowing")
    }

    val prefs: Flow<Prefs> = store.data.map { it.toPrefs() }.distinctUntilChanged()

    val lastPackageSnapshot: Flow<Set<String>> = store.data.map { it[K.lastPackageSnapshot] ?: emptySet() }.distinctUntilChanged()

    /** Experimental: keep X's home on "Following" (not part of [Prefs] or backups). */
    val xKeepFollowing: Flow<Boolean> = store.data.map { it[K.xKeepFollowing] ?: false }.distinctUntilChanged()

    suspend fun setXKeepFollowing(on: Boolean) {
        store.edit { it[K.xKeepFollowing] = on }
    }

    suspend fun get(): Prefs = prefs.first()

    suspend fun write(p: Prefs) {
        store.edit { it.put(p) }
    }

    suspend fun setLastPackageSnapshot(pkgs: Set<String>) {
        store.edit { it[K.lastPackageSnapshot] = pkgs }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }

    private fun Preferences.toPrefs(): Prefs {
        val d = Prefs()
        return Prefs(
            dayStartHour = this[K.dayStartHour] ?: d.dayStartHour,
            pendingDayStartHour = this[K.pendingDayStartHour],
            limitWarningMin = this[K.limitWarningMin] ?: d.limitWarningMin,
            notifyBeforeStartMin = this[K.notifyBeforeStartMin] ?: d.notifyBeforeStartMin,
            notifyBeforePauseEndMin = this[K.notifyBeforePauseEndMin] ?: d.notifyBeforePauseEndMin,
            hidePauseOption = this[K.hidePauseOption] ?: d.hidePauseOption,
            onboardingDone = this[K.onboardingDone] ?: d.onboardingDone,
        )
    }

    private fun MutablePreferences.put(p: Prefs) {
        this[K.dayStartHour] = p.dayStartHour
        val pending = p.pendingDayStartHour
        if (pending == null) remove(K.pendingDayStartHour) else this[K.pendingDayStartHour] = pending
        this[K.limitWarningMin] = p.limitWarningMin
        this[K.notifyBeforeStartMin] = p.notifyBeforeStartMin
        this[K.notifyBeforePauseEndMin] = p.notifyBeforePauseEndMin
        this[K.hidePauseOption] = p.hidePauseOption
        this[K.onboardingDone] = p.onboardingDone
    }
}
