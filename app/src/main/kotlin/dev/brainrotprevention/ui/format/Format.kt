package dev.brainrotprevention.ui.format

import dev.brainrotprevention.engine.ActivationRefusal
import dev.brainrotprevention.engine.Refusal
import dev.brainrotprevention.engine.ScheduleEvaluator
import dev.brainrotprevention.engine.ScheduleStatus
import dev.brainrotprevention.engine.StrictPolicy
import dev.brainrotprevention.engine.UnlockRefusal
import dev.brainrotprevention.engine.Until
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.TimeWindow
import dev.brainrotprevention.model.UsageLimit
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind
import dev.brainrotprevention.model.Weekdays
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pure text formatting for the UI (no Android, no clock reads: callers pass `now`). */
object Format {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR
    private val dayShort = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val dayHm = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
    private val dateHm = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)
    private val date = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)

    /** Minutes from midnight as `HH:mm`; 1440 is "24:00". */
    fun minuteOfDay(m: Int): String = "%02d:%02d".format(Locale.ROOT, m / 60, m % 60)

    fun dayShort(index: Int): String = dayShort[index]

    /** "Every day", "Mon–Fri", "Weekends", "Mon–Wed", "Mon, Wed, Fri", "Sun". */
    fun days(mask: Int): String {
        if (mask == Weekdays.ALL) return "Every day"
        if (mask == Weekdays.WEEKDAYS) return "Mon–Fri"
        if (mask == Weekdays.WEEKEND) return "Weekends"
        val idx = (0..6).filter { mask and (1 shl it) != 0 }
        if (idx.isEmpty()) return "No days"
        if (idx.size >= 3 && idx.last() - idx.first() == idx.size - 1) return "${dayShort[idx.first()]}–${dayShort[idx.last()]}"
        return idx.joinToString(", ") { dayShort[it] }
    }

    fun windowTimes(w: TimeWindow): String =
        "${minuteOfDay(w.startMin)}–${minuteOfDay(w.endMin)}" + if (w.overnight) " (ends next day)" else ""

    fun window(w: TimeWindow): String = "${days(w.days)} · ${windowTimes(w)}"

    /** "45 s", "12 min", "2 h 10 m", "3 d 4 h". */
    fun duration(ms: Long): String {
        val v = ms.coerceAtLeast(0)
        return when {
            v < MIN -> "${(v + 999) / 1000} s"
            v < HOUR -> "${(v + MIN - 1) / MIN} min"
            v < DAY -> {
                val totalMin = (v + MIN - 1) / MIN
                val h = totalMin / 60
                val m = totalMin % 60
                if (m == 0L) "$h h" else "$h h $m m"
            }
            else -> {
                val totalH = (v + HOUR - 1) / HOUR
                val d = totalH / 24
                val h = totalH % 24
                if (h == 0L) "$d d" else "$d d $h h"
            }
        }
    }

    /** "1:05" / "12:00:30" countdown text. */
    fun countdown(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, sec) else "%d:%02d".format(Locale.ROOT, m, sec)
    }

    fun periodWord(p: Period): String = if (p == Period.DAILY) "day" else "hour"

    fun limit(l: UsageLimit): String = when (l.kind) {
        LimitKind.TIME -> "${duration(l.allowance)}/${periodWord(l.period)}"
        LimitKind.LAUNCHES -> "${l.allowance} ${if (l.allowance == 1L) "open" else "opens"}/${periodWord(l.period)}"
    }

    /** "Mon–Fri · 08:00–17:40 · 30 min/day", or "Always on" (§12). */
    fun summary(s: Schedule): String {
        val parts = mutableListOf<String>()
        s.timeWindows.firstOrNull()?.let { parts += window(it) }
        if (s.timeWindows.size > 1) parts += "+${s.timeWindows.size - 1} more"
        if (s.wifi.isNotEmpty()) parts += "on Wi-Fi " + s.wifi.joinToString(", ") { it.ssid }
        s.locations.forEach { parts += (if (it.inverted) "away from " else "at ") + it.label }
        val triggers = parts.size
        s.limits.sortedBy { it.kind }.forEach { parts += limit(it) }
        if (triggers == 0 && s.limits.isEmpty()) return "Always on"
        if (triggers == 0) parts.add(0, "All day")
        if (triggers >= 2 && s.triggerTypes.size >= 2 && s.combine == dev.brainrotprevention.model.Combine.ALL) {
            parts.add(triggers, "all together")
        }
        return parts.joinToString(" · ")
    }

    fun listCounts(apps: Int, sites: Int): String {
        val a = if (apps == 1) "1 app" else "$apps apps"
        val w = if (sites == 1) "1 site" else "$sites sites"
        return when {
            apps == 0 && sites == 0 -> "Nothing selected"
            sites == 0 -> a
            apps == 0 -> w
            else -> "$a · $w"
        }
    }

    /** A clock time relative to [now]: "17:40", "Tue 17:40", "24 Nov 17:40". */
    fun at(instant: Long, now: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(instant).atZone(zone)
        val n = Instant.ofEpochMilli(now).atZone(zone)
        return when {
            t.toLocalDate() == n.toLocalDate() -> hm.format(t)
            instant > now && instant - now < 6 * DAY -> dayHm.format(t)
            else -> dateHm.format(t)
        }
    }

    fun dateLong(instant: Long, zone: ZoneId): String = date.format(Instant.ofEpochMilli(instant).atZone(zone))

    /** The block-screen style "until" line (§10.3). */
    fun until(u: Until?, now: Long, zone: ZoneId): String = when (u) {
        null -> ""
        is Until.At -> if (u.instant == Schedule.UNTIL_STOPPED) "until you stop it" else "until ${at(u.instant, now, zone)}"
        is Until.WhileConditions -> "while ${u.label}"
        Until.UntilStopped -> "until you stop it"
        Until.Ongoing -> "all week"
        is Until.UnlockCondition -> unlockShort(u.method, u.endsAt, now, zone)
    }

    private fun unlockShort(m: StrictMethod, endsAt: Long?, now: Long, zone: ZoneId) = when (m) {
        StrictMethod.TIMER -> if (endsAt != null) "until ${at(endsAt, now, zone)}" else "until the timer ends"
        StrictMethod.SCHEDULES -> "while followed schedules block"
        StrictMethod.CHARGER -> "until unlocked with the charger"
        StrictMethod.PIN -> "until unlocked with the PIN"
        StrictMethod.COOLDOWN -> "until unlocked after a cooldown"
    }

    /** One-line web-rule description; also the live preview (§12 "Blocks facebook.com and every subdomain"). */
    fun rule(r: WebRule, verb: String = "Blocks"): String = when (r.kind) {
        WebRuleKind.DOMAIN -> "$verb ${r.value} and every subdomain"
        WebRuleKind.KEYWORD ->
            if (r.anywhereInUrl) "$verb any page whose address contains “${r.value}”" else "$verb sites whose name contains “${r.value}”"
    }

    /** What the user must do to end Strict Mode (shown with "Locked by Strict Mode"). */
    fun unlockCondition(
        row: StrictModeRow,
        display: StrictDisplayState,
        now: Long,
        elapsed: Long,
        zone: ZoneId
    ): String = when (row.method) {
        StrictMethod.TIMER -> {
            val left = StrictPolicy.timerRemaining(row, now, elapsed)
            val end = row.endsAt?.let { " (${at(it, now, zone)})" } ?: ""
            "Strict Mode ends in ${duration(left)}$end."
        }
        StrictMethod.SCHEDULES ->
            if (display == StrictDisplayState.STANDBY) {
                "Strict Mode is on standby: you can turn it off from the Strict tab after a 5-minute wait."
            } else {
                "Strict Mode stays active while a followed schedule is blocking."
            }
        StrictMethod.CHARGER -> "Plug in the charger and tap Unlock on the Strict tab."
        StrictMethod.PIN -> "Enter the PIN on the Strict tab."
        StrictMethod.COOLDOWN -> "Wait ${row.cooldownMin} minutes on the Strict tab to unlock."
    }

    fun refusal(r: Refusal): String = when (r) {
        Refusal.TIME_COVERAGE_REDUCED -> "This would block for less time."
        Refusal.COMBINE_TIGHTENED -> "Requiring all conditions together would block less often."
        Refusal.TRIGGER_ADDED -> "A new kind of condition would block less often."
        Refusal.TRIGGER_REMOVED_OR_EDITED -> "Wi-Fi and location conditions can't be removed or changed."
        Refusal.LIMIT_ADDED -> "Adding a budget would allow use that is blocked now."
        Refusal.LIMIT_REMOVED -> "This budget can't be removed."
        Refusal.LIMIT_RAISED -> "Budgets can only be lowered."
        Refusal.LIMIT_PERIOD_SHORTENED -> "A daily budget can't become hourly."
        Refusal.MODE_CHANGED -> "The mode can't be changed."
        Refusal.ALLOWLIST_WEB_OPENED -> "Web browsing can't be opened up."
        Refusal.BLOCKLIST_ITEM_REMOVED -> "Blocked apps and sites can't be removed."
        Refusal.ALLOWLIST_ITEM_ADDED -> "Allowed apps and sites can't be added."
        Refusal.EXCLUDED_BROWSER_ADDED -> "Browsers can't be excluded."
        Refusal.FLAG_TURNED_OFF -> "These options can only be turned on."
        Refusal.DISABLED -> "Schedules can't be turned off."
        Refusal.PAUSED -> "Schedules can't be paused."
        Refusal.DELETED -> "Schedules can't be deleted."
        Refusal.MANUAL_SHORTENED -> "A started schedule can't be stopped early."
        Refusal.UNFOLLOWED -> "Followed schedules can't be unfollowed."
        Refusal.QUICK_BLOCK_STOPPED -> "Quick Block can't be stopped."
        Refusal.QUICK_BLOCK_SHORTENED -> "Quick Block can't be shortened or have items removed."
        Refusal.STRICT_CHANGED -> "Strict Mode settings can't be changed while it is on."
        Refusal.SETTING_LOCKED -> "This setting can't be changed."
        Refusal.IMPORT_LOCKED -> "Backups can't be imported."
    }

    fun activationRefusal(r: ActivationRefusal): String = when (r) {
        ActivationRefusal.ALREADY_ON -> "Strict Mode is already on."
        ActivationRefusal.ACCESSIBILITY_NOT_CONNECTED -> "Turn on the Brainrot Prevention accessibility service first."
        ActivationRefusal.NO_USAGE_ACCESS -> "Grant usage access first."
        ActivationRefusal.IN_ACCESSIBILITY_SHORTCUT -> "Remove Brainrot Prevention from the accessibility shortcut first."
        ActivationRefusal.INVALID_OPTIONS -> "Blocking uninstall also needs blocking Settings."
        ActivationRefusal.ADMIN_NOT_ACTIVE -> "Uninstall protection needs device admin. Activation was cancelled."
        ActivationRefusal.TIMER_OUT_OF_RANGE -> "Pick a duration between 15 minutes and 30 days."
        ActivationRefusal.INVALID_PIN -> "The PIN must be 4 to 8 digits."
        ActivationRefusal.INVALID_COOLDOWN -> "Pick a cooldown of 5, 10, 15 or 30 minutes."
        ActivationRefusal.FOLLOWED_SET_EMPTY -> "Choose at least one blocklist schedule to follow."
    }

    fun unlockRefusal(r: UnlockRefusal?, remainingMs: Long?): String = when (r) {
        UnlockRefusal.NOT_PLUGGED -> "The charger isn't connected. Plug it in and try again."
        UnlockRefusal.PIN_LOCKED_OUT -> "Too many wrong PINs. Try again in ${duration(remainingMs ?: 0)}."
        UnlockRefusal.COOLDOWN_RUNNING -> "Keep waiting: ${countdown(remainingMs ?: 0)} left."
        UnlockRefusal.NOT_IN_STANDBY -> "A followed schedule is blocking right now."
        UnlockRefusal.NOT_ON -> "Strict Mode is already off."
        UnlockRefusal.WRONG_METHOD, null -> "That unlock method isn't available."
    }
}

/** The state pill on a schedule card (§12). */
sealed interface StatePill {
    val label: String

    data class Blocking(val untilText: String) : StatePill {
        override val label = "Blocking"
    }

    data class Counting(val left: String) : StatePill {
        override val label = "Counting · $left left"
    }

    data class Paused(val untilText: String) : StatePill {
        override val label = "Paused"
    }

    data object Off : StatePill {
        override val label = "Off"
    }

    data class Scheduled(val next: String?) : StatePill {
        override val label = "Scheduled"
    }

    companion object {
        fun of(s: Schedule, st: ScheduleStatus?, now: Long, zone: ZoneId): StatePill {
            if (!s.enabled) return Off
            if (ScheduleEvaluator.isPaused(s, now)) return Paused(Format.until(Until.At(s.pausedUntil!!), now, zone))
            if (st == null) return Scheduled(null)
            if (st.blocking) return Blocking(Format.until(st.blockUntil, now, zone))
            if (st.armed && st.launchesExhausted) return Blocking("no opens left")
            if (st.armed) {
                val left = listOfNotNull(st.time?.let { Format.duration(it.left) }, st.launches?.let { "${it.left} opens" })
                return Counting(left.joinToString(", ").ifEmpty { "—" })
            }
            val next = ScheduleEvaluator.nextWindowStart(s, now, zone)?.let { Format.at(it, now, zone) }
            return Scheduled(next)
        }
    }
}
