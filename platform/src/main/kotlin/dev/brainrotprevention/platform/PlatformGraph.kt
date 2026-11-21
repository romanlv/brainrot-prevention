package dev.brainrotprevention.platform

import android.app.Application
import android.content.Context
import dev.brainrotprevention.data.DataGraph
import dev.brainrotprevention.model.PolicyClock
import dev.brainrotprevention.platform.sensors.Sensors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Implemented by the Application; gives every component the process-wide [PlatformGraph]. */
interface PlatformHost {
    val platform: PlatformGraph
}

/** The process-wide platform graph (see `platform/API.md`). */
val Context.platform: PlatformGraph get() = (applicationContext as PlatformHost).platform

/**
 * Manual DI for the platform layer. One per process, created by the Application after the [DataGraph].
 * [debug] is true for the debug and qa builds (test hooks, offset clock, `browsers.debug.json`).
 */
class PlatformGraph private constructor(
    val app: Application,
    val data: DataGraph,
    val clock: PolicyClock,
    val debug: Boolean,
) {
    /** Long-lived background work (never the main thread). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val knobs = DebugKnobs()
    val packages = PackageFacts(app, this)
    val notifier = Notifier(app, this)
    val sensors = Sensors(app, this)
    val alarms = AlarmScheduler(app, this)
    val engineHost = EngineHost(app, this)
    val health = PermissionHealth(app, this)
    val apps = InstalledApps(app, this)
    val strict = StrictActivator(app, this)

    /** The connected accessibility service, or null. */
    @Volatile var service: BlockerAccessibilityService? = null
        internal set

    /** The connected notification listener, or null. */
    @Volatile var listener: NotificationBlockerService? = null
        internal set

    private fun start() {
        data.strictGate.sensors = { sensors.state() }
        engineHost.start()
    }

    /** Debug `reset` (after the data wipe): hide the overlay, forget engine memory, re-arm alarms, evaluate. */
    suspend fun resetForDebug() {
        service?.hideOverlay("reset")
        engineHost.resetMemory()
        alarms.invalidate()
        engineHost.evaluateNow("reset")
    }

    companion object {
        fun create(app: Application, data: DataGraph, clock: PolicyClock, debug: Boolean): PlatformGraph =
            PlatformGraph(app, data, clock, debug).also { it.start() }
    }
}

/** Debug/test overrides (set only through the debug-control commands). */
class DebugKnobs {
    /** `snooze-chunk --el ms N` (§10.4); null = the release default. */
    @Volatile var snoozeChunkMs: Long? = null

    /** `clock-advance` deltas count in full (debug builds, §5.4). */
    @Volatile var countFullDelta: Boolean = false
}
