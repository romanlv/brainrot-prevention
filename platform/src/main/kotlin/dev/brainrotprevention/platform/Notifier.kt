package dev.brainrotprevention.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.brainrotprevention.engine.EvaluationResult
import dev.brainrotprevention.engine.QUICK_BLOCK_ID
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.platform.log.Brp
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Our notifications: the ongoing status line (low importance) and alerts (§3 rule 2, §5.4, §8, §11.6). */
class Notifier internal constructor(private val app: Context, private val g: PlatformGraph) {
    private val nm = app.getSystemService(NotificationManager::class.java)
    private var lastStatusText: String? = null

    init {
        nm?.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Blocking status", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "The ongoing \"schedules blocking\" line."
            },
        )
        nm?.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Reminders and warnings", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Limit warnings, pause ending, schedule starting, permission problems."
            },
        )
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(): PendingIntent? {
        val i = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return null
        return PendingIntent.getActivity(app, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @SuppressLint("MissingPermission")
    private fun post(id: Int, n: Notification) {
        if (!canPost()) return
        try {
            nm?.notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    private fun alert(title: String, text: String): Notification = Notification.Builder(app, CH_ALERTS)
        .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .setCategory(Notification.CATEGORY_REMINDER)
        .setContentIntent(openApp())
        .build()

    fun postLimitWarning(s: Schedule?, timeLeftMs: Long) {
        val min = (timeLeftMs + 59_999) / 60_000
        post(ID_LIMIT, alert("${s?.name ?: "Budget"}: $min min left", "Your time budget is almost used up."))
        Brp.event("notif_posted", "kind" to "LIMIT_WARNING", "scheduleId" to s?.id, "leftMs" to timeLeftMs)
    }

    fun postPauseEnd(s: Schedule, until: Long) {
        post(ID_PAUSE + s.id.toInt(), alert("${s.name} resumes soon", "The pause ends at ${hhmm(until)}."))
        Brp.event("notif_posted", "kind" to "PAUSE_END", "scheduleId" to s.id)
    }

    fun postBeforeStart(s: Schedule) {
        post(ID_START + s.id.toInt(), alert("${s.name} starts soon", "Blocking starts in a few minutes."))
        Brp.event("notif_posted", "kind" to "BEFORE_START", "scheduleId" to s.id)
    }

    fun postHealth(title: String, text: String) {
        post(ID_HEALTH, alert(title, text))
        Brp.event("notif_posted", "kind" to "HEALTH", "title" to title)
    }

    fun postError(ids: List<Long>) {
        post(ID_ERROR, alert("Blocking error, tap to fix", "A schedule could not be evaluated; it blocks its listed items."))
        Brp.event("error", "where" to "schedule", "ids" to ids)
    }

    /** The ongoing status line, posted only while the accessibility service runs. */
    fun updateStatus(r: EvaluationResult, snapshot: ConfigSnapshot, serviceConnected: Boolean) {
        if (!serviceConnected) {
            if (lastStatusText != null) {
                nm?.cancel(ID_STATUS)
                lastStatusText = null
            }
            return
        }
        val blocking = r.summary.blocking
        val names = blocking.map { id ->
            if (id == QUICK_BLOCK_ID) "Quick Block" else snapshot.schedules.firstOrNull { it.id == id }?.name ?: "#$id"
        }
        val title = when (blocking.size) {
            0 -> "Nothing blocking"
            1 -> "1 schedule blocking"
            else -> "${blocking.size} schedules blocking"
        }
        val budget = r.summary.budgets.firstOrNull()?.let { b ->
            when {
                b.timeLeftMs != null -> "${(b.timeLeftMs!! + 59_999) / 60_000} min left"
                b.launchesLeft != null -> "${b.launchesLeft} launches left"
                else -> null
            }
        }
        val text = listOfNotNull(names.joinToString(", ").ifEmpty { null }, budget).joinToString(" · ")
        val key = "$title|$text"
        if (key == lastStatusText) return
        lastStatusText = key
        val n = Notification.Builder(app, CH_STATUS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(title)
            .setContentText(text.ifEmpty { "Brainrot Prevention is running" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp())
            .build()
        post(ID_STATUS, n)
    }

    fun cancelStatus() {
        nm?.cancel(ID_STATUS)
        lastStatusText = null
    }

    private fun hhmm(t: Long): String = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(t).atZone(g.clock.zone()))

    companion object {
        const val CH_STATUS = "status"
        const val CH_ALERTS = "alerts"
        const val ID_STATUS = 1
        const val ID_LIMIT = 2
        const val ID_HEALTH = 3
        const val ID_ERROR = 4
        const val ID_PAUSE = 1000
        const val ID_START = 100000
    }
}
