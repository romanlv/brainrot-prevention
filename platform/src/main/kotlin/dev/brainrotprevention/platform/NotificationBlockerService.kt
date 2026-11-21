package dev.brainrotprevention.platform

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.brainrotprevention.engine.EvaluationResult
import dev.brainrotprevention.engine.NotificationAction
import dev.brainrotprevention.engine.NotificationFacts
import dev.brainrotprevention.engine.NotificationPolicy
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.platform.log.Brp
import dev.brainrotprevention.platform.time.SystemTimeAndroid
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * Notification hiding (§10.4): snoozes notifications of blocked packages in bounded chunks, re-snoozes them when
 * they come back while still blocked, counts them, and rebinds when disconnected. Also gives the enforcer access
 * to media sessions (§10.2).
 */
class NotificationBlockerService : NotificationListenerService() {
    private val g: PlatformGraph get() = platform

    /** Schedules hiding notifications at the last sweep; the active list is only fetched when this changes. */
    @Volatile private var lastHiding: Set<Long>? = null

    /** "N held today" counts each notification key once per day (updates of one notification are re-snoozed). */
    private val counted = java.util.concurrent.ConcurrentHashMap<String, String>() // key → day

    override fun onListenerConnected() {
        super.onListenerConnected()
        g.listener = this
        Brp.event("service", "name" to "listener", "state" to "connected")
        lastHiding = null
        g.scope.launch { g.engineHost.evaluateNow("listener-connected").let { sweep(null) } }
    }

    override fun onListenerDisconnected() {
        if (g.listener === this) g.listener = null
        Brp.event("service", "name" to "listener", "state" to "disconnected")
        runCatching { requestRebind(ComponentName(this, NotificationBlockerService::class.java)) }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        handle(n)
    }

    private fun facts(sbn: StatusBarNotification): NotificationFacts {
        val n = sbn.notification
        val flags = n.flags
        return NotificationFacts(
            packageName = sbn.packageName,
            isOwn = sbn.packageName == packageName,
            ongoing = flags and Notification.FLAG_ONGOING_EVENT != 0,
            noClear = flags and Notification.FLAG_NO_CLEAR != 0,
            foregroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            category = n.category,
            hasFullScreenIntent = n.fullScreenIntent != null,
        )
    }

    /** Returns true when the notification was snoozed. */
    private fun handle(sbn: StatusBarNotification): Boolean {
        val snapshot = g.data.snapshots.snapshot.value ?: return false
        val result = g.engineHost.lastResult ?: return false
        val block = g.engineHost.notificationBlock(snapshot, result, sbn.packageName)
        val action = NotificationPolicy.decide(
            facts(sbn),
            block,
            SystemTimeAndroid.currentTimeMillis(),
            g.clock::toSystemMillis,
            g.knobs.snoozeChunkMs ?: NotificationPolicy.DEFAULT_SNOOZE_CHUNK_MS,
        )
        if (action !is NotificationAction.Snooze) return false
        try {
            snoozeNotification(sbn.key, action.durationMs)
        } catch (e: RuntimeException) {
            Brp.event("error", "where" to "snooze", "msg" to e.toString())
            return false
        }
        Brp.event("notif_hidden", "pkg" to sbn.packageName, "ms" to action.durationMs)
        val day = Instant.ofEpochMilli(g.clock.now()).atZone(g.clock.zone()).toLocalDate().toString()
        if (counted.put(sbn.key, day) != day) {
            if (counted.size > MAX_COUNTED_KEYS) counted.entries.removeIf { it.value != day }
            g.scope.launch { g.data.notificationCounts.increment(sbn.packageName, day) }
        }
        return true
    }

    /**
     * After every evaluation: when the set of schedules hiding notifications changes, apply the rules to the
     * active notifications. Nothing is fetched over binder while that set stays the same (new posts go through
     * [onNotificationPosted]).
     */
    internal fun onEvaluated(r: EvaluationResult, snapshot: ConfigSnapshot) {
        val hiding = g.engineHost.notificationHidingIds(snapshot, r)
        if (hiding == lastHiding) return
        lastHiding = hiding
        if (hiding.isNotEmpty()) sweep(null)
    }

    private companion object {
        const val MAX_COUNTED_KEYS = 2_000
    }

    private fun sweep(only: Set<String>?) {
        val active = try {
            activeNotifications
        } catch (_: RuntimeException) {
            null
        } ?: return
        for (sbn in active) if (only == null || sbn.packageName in only) handle(sbn)
    }
}
