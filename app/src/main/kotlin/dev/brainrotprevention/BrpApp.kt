package dev.brainrotprevention

import android.app.Application
import android.content.Context
import dev.brainrotprevention.data.DataGraph
import dev.brainrotprevention.model.OffsetPolicyClock
import dev.brainrotprevention.model.PolicyClock
import dev.brainrotprevention.model.SystemPolicyClock
import dev.brainrotprevention.model.SystemTime
import dev.brainrotprevention.platform.PlatformGraph
import dev.brainrotprevention.platform.PlatformHost
import dev.brainrotprevention.platform.debug.DebugClockStore
import dev.brainrotprevention.platform.log.Brp
import dev.brainrotprevention.platform.time.SystemTimeAndroid

/**
 * Creates the process-wide [DataGraph] (one per process: DataStore allows one instance per file) and the
 * [PlatformGraph]. Debug and qa builds use the offset policy clock of the §14.3 clock commands.
 */
class BrpApp : Application(), PlatformHost {
    lateinit var graph: DataGraph
        private set

    override lateinit var platform: PlatformGraph
        private set

    /** The debug clock (debug/qa only), with its persistence. */
    var debugClock: OffsetPolicyClock? = null
        private set
    var debugClockStore: DebugClockStore? = null
        private set

    override fun onCreate() {
        super.onCreate()
        dev.brainrotprevention.ui.UiGuard.install(this)
        val debug = BuildConfig.BUILD_TYPE != "release"
        val clock: PolicyClock = if (debug) {
            val store = DebugClockStore(this)
            debugClockStore = store
            store.create(AndroidSystemTime).also { debugClock = it }
        } else {
            SystemPolicyClock(AndroidSystemTime)
        }
        graph = DataGraph.create(this, clock)
        Brp.policyNow = { graph.clock.now() }
        platform = PlatformGraph.create(this, graph, clock, debug)
    }

    private object AndroidSystemTime : SystemTime {
        override fun currentTimeMillis() = SystemTimeAndroid.currentTimeMillis()

        override fun elapsedRealtime() = SystemTimeAndroid.elapsedRealtime()

        override fun uptimeMillis() = SystemTimeAndroid.uptimeMillis()
    }
}

/** The process-wide data graph. */
val Context.dataGraph: DataGraph get() = (applicationContext as BrpApp).graph
