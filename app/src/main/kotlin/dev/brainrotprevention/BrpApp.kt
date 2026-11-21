package dev.brainrotprevention

import android.app.Application
import android.content.Context
import dev.brainrotprevention.data.DataGraph
import dev.brainrotprevention.model.PolicyClock
import dev.brainrotprevention.model.SystemPolicyClock
import dev.brainrotprevention.model.SystemTime
import dev.brainrotprevention.platform.log.Brp
import dev.brainrotprevention.platform.time.SystemTimeAndroid

/** Creates the process-wide [DataGraph] (one per process: DataStore allows one instance per file). */
class BrpApp : Application() {
    lateinit var graph: DataGraph
        private set

    override fun onCreate() {
        super.onCreate()
        val clock: PolicyClock = SystemPolicyClock(AndroidSystemTime)
        graph = DataGraph.create(this, clock)
        Brp.policyNow = { graph.clock.now() }
    }

    private object AndroidSystemTime : SystemTime {
        override fun currentTimeMillis() = SystemTimeAndroid.currentTimeMillis()

        override fun elapsedRealtime() = SystemTimeAndroid.elapsedRealtime()

        override fun uptimeMillis() = SystemTimeAndroid.uptimeMillis()
    }
}

/** The process-wide data graph. */
val Context.dataGraph: DataGraph get() = (applicationContext as BrpApp).graph
