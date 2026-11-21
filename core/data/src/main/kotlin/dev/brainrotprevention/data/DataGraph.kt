package dev.brainrotprevention.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import dev.brainrotprevention.data.db.BrpDatabase
import dev.brainrotprevention.model.PolicyClock
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * Manual DI for the data layer. Create exactly one per process ([create], from `Application.onCreate`):
 * DataStore allows one instance per file. Tests use [inMemory].
 */
class DataGraph private constructor(
    val db: BrpDatabase,
    dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
    filesDir: File,
    val clock: PolicyClock,
) {
    val prefsStore = PrefsStore(dataStore)
    val snapshots = SnapshotStore(db, prefsStore, File(filesDir, SnapshotStore.FILE))
    val strictGate = StrictGate(db, clock)
    private val core = DataCore(db, prefsStore, snapshots, strictGate, clock)

    val schedules = ScheduleRepository(core)
    val quickBlock = QuickBlockRepository(core)
    val strict = StrictRepository(core)
    val settings = SettingsRepository(core)
    val counters = UsageCounterStore(db)
    val pausePresets = PausePresetRepository(db)
    val notificationCounts = NotificationCountRepository(db)
    val backup = BackupRepository(core, schedules, quickBlock, settings)

    /**
     * Debug `reset`: wipes every table (strict row → INACTIVE, emergency log, counters, …) and every pref,
     * then rebuilds the snapshot (and its cache) from the empty state. Platform facts are kept.
     */
    suspend fun wipe() {
        core.plain {
            withContext(Dispatchers.IO) { db.clearAllTables() }
            prefsStore.clear()
        }
    }

    fun close() {
        db.close()
        scope.cancel()
    }

    companion object {
        const val PREFS_FILE = "datastore/brp.preferences_pb"

        fun create(context: Context, clock: PolicyClock): DataGraph {
            val app = context.applicationContext
            return build(BrpDatabase.open(app), File(app.filesDir, PREFS_FILE), app.filesDir, clock)
        }

        /** In-memory Room; prefs and the snapshot cache live under [dir]. */
        fun inMemory(context: Context, clock: PolicyClock, dir: File): DataGraph =
            build(BrpDatabase.inMemory(context), File(dir, PREFS_FILE), dir, clock)

        private fun build(db: BrpDatabase, prefsFile: File, filesDir: File, clock: PolicyClock): DataGraph {
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { prefsFile })
            return DataGraph(db, store, scope, filesDir, clock)
        }
    }
}
