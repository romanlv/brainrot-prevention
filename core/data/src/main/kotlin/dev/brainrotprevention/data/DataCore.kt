package dev.brainrotprevention.data

import androidx.room.withTransaction
import dev.brainrotprevention.data.db.BrpDatabase
import dev.brainrotprevention.data.db.toModel
import dev.brainrotprevention.engine.Engine
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.PolicyClock
import dev.brainrotprevention.model.SensorState
import dev.brainrotprevention.model.StrictDisplayState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Computes the displayed Strict state that EditGuard needs (ACTIVE locks everything, STANDBY the followed
 * schedules). The engine host may install [sensors] (Wi-Fi/location affect whether followed schedules block)
 * or a [displayOverride] with the state of its last evaluation.
 */
class StrictGate internal constructor(private val db: BrpDatabase, private val clock: PolicyClock) {
    private val engine = Engine()

    @Volatile var sensors: () -> SensorState = { SensorState() }

    /** Returns null to fall back to computing the state from the snapshot. */
    @Volatile var displayOverride: () -> StrictDisplayState? = { null }

    suspend fun display(snapshot: ConfigSnapshot): StrictDisplayState {
        if (!snapshot.strict.isOn) return StrictDisplayState.INACTIVE
        displayOverride()?.let { return it }
        val counters = db.usageCounters().getAll().associate { it.limitId to it.toModel() }
        return engine.status(snapshot, clock.read(), counters, sensors()).strict
    }
}

/** Shared plumbing of the repositories: one write lock so guard checks and writes are atomic. */
internal class DataCore(
    val db: BrpDatabase,
    val prefs: PrefsStore,
    val snapshots: SnapshotStore,
    val gate: StrictGate,
    val clock: PolicyClock,
) {
    private val writeLock = Mutex()

    /**
     * Runs a guarded mutation: [block] gets the current snapshot (DB state) and its display state, returns a
     * result; on Ok the snapshot is rebuilt before the lock is released.
     */
    suspend fun <T> write(block: suspend (GuardContext) -> WriteResult<T>): WriteResult<T> = writeLock.withLock {
        val snap = snapshots.load()
        val ctx = GuardContext(snap, gate.display(snap), clock.now())
        val r = block(ctx)
        if (r is WriteResult.Ok) snapshots.load()
        r
    }

    /** An unguarded platform/engine write (counters excluded): still serialised, snapshot rebuilt after. */
    suspend fun <T> plain(block: suspend () -> T): T = writeLock.withLock {
        val r = block()
        snapshots.load()
        r
    }

    suspend fun <T> tx(block: suspend () -> T): T = db.withTransaction { block() }
}

internal data class GuardContext(val snapshot: ConfigSnapshot, val display: StrictDisplayState, val now: Long) {
    val strict get() = snapshot.strict
    val schedules get() = snapshot.schedules
}
