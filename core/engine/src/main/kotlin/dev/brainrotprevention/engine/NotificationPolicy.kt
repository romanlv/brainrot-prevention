package dev.brainrotprevention.engine

import dev.brainrotprevention.model.Mode
import java.util.Locale

/** Facts about one posted notification (§10.4). */
data class NotificationFacts(
    val packageName: String,
    val isOwn: Boolean = false,
    val ongoing: Boolean = false,
    val noClear: Boolean = false,
    val foregroundService: Boolean = false,
    /** `Notification.category`, e.g. "call", "alarm", "reminder". */
    val category: String? = null,
    val hasFullScreenIntent: Boolean = false,
)

sealed interface NotificationAction {
    data object Leave : NotificationAction

    /** `snoozeNotification(key, durationMs)`, then count it in notification_count. */
    data class Snooze(val durationMs: Long) : NotificationAction
}

/** §10.4 notification hiding. */
object NotificationPolicy {
    const val DEFAULT_SNOOZE_CHUNK_MS = 15 * Periods.MINUTE
    private const val MIN_SNOOZE_MS = 1_000L
    private val PROTECTED_CATEGORIES = setOf("call", "alarm", "reminder")

    /**
     * Until of the notification block on [pkg], or null when its notifications must not be hidden.
     * Only schedules (or Quick Block) with `blockNotifications`; a schedule with limits only once a
     * budget is exhausted (LAUNCHES: used ≥ allowance).
     */
    fun blockFor(prepared: PreparedSnapshot, statuses: Map<Long, ScheduleStatus>, pkg: String): Until? {
        if (pkg in prepared.exempt || pkg in prepared.essentials) return null
        val untils = ArrayList<Until>()
        for (s in prepared.effective) {
            if (!s.blockNotifications) continue
            val st = statuses[s.id] ?: continue
            if (!st.blockingOrLaunchesExhausted) continue
            val matches = when (s.mode) {
                Mode.BLOCKLIST -> pkg in s.apps
                Mode.ALLOWLIST -> pkg !in s.apps
            }
            if (matches) untils += if (st.blocking) st.blockUntil ?: Until.Ongoing else st.gateUntil
        }
        return if (untils.isEmpty()) null else Until.combine(untils)
    }

    /** Ids of the schedules (and Quick Block) hiding notifications right now; empty = nothing to sweep. */
    fun hidingIds(prepared: PreparedSnapshot, statuses: Map<Long, ScheduleStatus>): Set<Long> =
        prepared.effective.filter { s -> s.blockNotifications && statuses[s.id]?.blockingOrLaunchesExhausted == true }
            .mapTo(LinkedHashSet()) { it.id }

    /**
     * Decision for one notification. [block] from [blockFor]; [toSystemMillis] is `PolicyClock.toSystemMillis`;
     * [systemNow] is `SystemTime.currentTimeMillis()`.
     */
    fun decide(
        facts: NotificationFacts,
        block: Until?,
        systemNow: Long,
        toSystemMillis: (Long) -> Long,
        snoozeChunkMs: Long = DEFAULT_SNOOZE_CHUNK_MS,
    ): NotificationAction {
        if (block == null || neverTouched(facts)) return NotificationAction.Leave
        val duration = if (block is Until.At) {
            minOf(toSystemMillis(block.instant) - systemNow, snoozeChunkMs)
        } else {
            snoozeChunkMs
        }
        if (duration <= 0) return NotificationAction.Leave
        return NotificationAction.Snooze(duration.coerceAtLeast(MIN_SNOOZE_MS))
    }

    fun neverTouched(f: NotificationFacts): Boolean =
        f.isOwn ||
            f.ongoing ||
            f.noClear ||
            f.foregroundService ||
            f.hasFullScreenIntent ||
            f.category?.lowercase(Locale.ROOT) in PROTECTED_CATEGORIES
}
