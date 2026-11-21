package dev.brainrotprevention.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.brainrotprevention.engine.PolicyInstant
import dev.brainrotprevention.platform.log.Brp
import kotlinx.coroutines.launch

/**
 * One exact alarm for the earliest policy instant (§11.4). Alarms only wake the engine; time is evaluated
 * live, so a late alarm never causes a missed block.
 */
class AlarmScheduler internal constructor(private val app: Context, private val g: PlatformGraph) {
    private val lock = Any()
    private var scheduled: Pair<Long, String>? = null
    private var initialised = false

    fun schedule(next: PolicyInstant?, force: Boolean = false) {
        val key = next?.let { it.at to it.kind.name }
        synchronized(lock) {
            if (!force && initialised && key == scheduled) return
            initialised = true
            scheduled = key
        }
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(next?.kind?.name)
        if (next == null) {
            am.cancel(pi)
            Brp.event("alarm", "kind" to null, "next" to null)
            return
        }
        val at = g.clock.toSystemMillis(next.at)
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        Brp.event("alarm", "kind" to next.kind.name, "next" to next.at, "scheduleId" to next.scheduleId)
    }

    /** Forget what was scheduled, so the next [schedule] call re-arms and logs (boot, reset). */
    fun invalidate() = synchronized(lock) { initialised = false }

    private fun pendingIntent(kind: String?): PendingIntent {
        val i = Intent(app, AlarmReceiver::class.java).setAction(ACTION).putExtra("kind", kind)
        return PendingIntent.getBroadcast(app, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val ACTION = "dev.brainrotprevention.platform.ALARM"
    }
}

/** Exact-alarm wakeups (not exported). */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = context.platform
        val pending = goAsync()
        g.scope.launch {
            try {
                g.alarms.invalidate()
                g.engineHost.onAlarm("alarm:${intent.getStringExtra("kind")}")
            } finally {
                pending.finish()
            }
        }
    }
}
