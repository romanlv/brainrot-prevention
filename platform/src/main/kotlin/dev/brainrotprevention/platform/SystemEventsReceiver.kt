package dev.brainrotprevention.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.brainrotprevention.platform.log.Brp
import kotlinx.coroutines.launch

/**
 * Manifest receiver for exempt broadcasts only (§11.1): boot, own update, clock and zone changes, package fully
 * removed, exact-alarm permission changes. Reschedules, re-registers geofences, recomputes package facts.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = context.platform
        val action = intent.action ?: return
        val pending = goAsync()
        g.scope.launch {
            try {
                when (action) {
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        Brp.event("boot", "action" to action)
                        g.alarms.invalidate()
                        g.engineHost.onBoot()
                        g.packages.refresh("boot")
                        g.sensors.geofences.registerAll()
                        g.health.refresh()
                    }
                    Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                        g.alarms.invalidate()
                        g.engineHost.onTimeChanged(action)
                    }
                    Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                        g.packages.refresh("package-removed")
                        g.engineHost.requestEvaluate("package-removed")
                    }
                    else -> {
                        g.alarms.invalidate()
                        g.engineHost.requestEvaluate(action)
                    }
                }
            } catch (t: Throwable) {
                Brp.event("error", "where" to "system-events", "action" to action, "msg" to t.toString())
            } finally {
                pending.finish()
            }
        }
    }
}
