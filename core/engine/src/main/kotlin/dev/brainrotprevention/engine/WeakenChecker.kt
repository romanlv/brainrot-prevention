package dev.brainrotprevention.engine

import dev.brainrotprevention.model.AllowlistWeb
import dev.brainrotprevention.model.Combine
import dev.brainrotprevention.model.LocationCondition
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.Prefs
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.TriggerType
import dev.brainrotprevention.model.WebRule
import java.util.BitSet

/** Why a change was refused under LOCK_EDITING (§9.4). */
enum class Refusal {
    TIME_COVERAGE_REDUCED,
    COMBINE_TIGHTENED,
    TRIGGER_ADDED,
    TRIGGER_REMOVED_OR_EDITED,
    LIMIT_ADDED,
    LIMIT_REMOVED,
    LIMIT_RAISED,
    LIMIT_PERIOD_SHORTENED,
    MODE_CHANGED,
    ALLOWLIST_WEB_OPENED,
    BLOCKLIST_ITEM_REMOVED,
    ALLOWLIST_ITEM_ADDED,
    EXCLUDED_BROWSER_ADDED,
    FLAG_TURNED_OFF,
    DISABLED,
    PAUSED,
    DELETED,
    MANUAL_SHORTENED,
    UNFOLLOWED,
    QUICK_BLOCK_STOPPED,
    QUICK_BLOCK_SHORTENED,
    STRICT_CHANGED,
    SETTING_LOCKED,
    IMPORT_LOCKED,
}

sealed interface EditCheck {
    data object Allowed : EditCheck

    data class Refused(val reason: Refusal) : EditCheck

    val allowed: Boolean get() = this is Allowed
}

/** The §9.4 weaken table as pure comparisons of old vs new. Does not decide whether the lock applies (see [EditGuard]). */
object WeakenChecker {
    const val CELLS = 7 * 1440

    /** 7×1440 coverage bitmap, Mon=day 0 (§9.4). Overnight windows spill into the next day; no-window rule [V2-N7]. */
    fun coverage(s: Schedule): BitSet {
        val bits = BitSet(CELLS)
        if (s.timeWindows.isEmpty()) {
            val hasOther = s.wifi.isNotEmpty() || s.locations.isNotEmpty()
            if (!hasOther || s.combine == Combine.ALL) bits.set(0, CELLS)
            return bits
        }
        for (w in s.timeWindows) {
            for (day in 0 until 7) {
                if (w.days and (1 shl day) == 0) continue
                val base = day * 1440
                if (w.startMin < w.endMin) {
                    bits.set(base + w.startMin, base + w.endMin.coerceAtMost(1440))
                } else {
                    bits.set(base + w.startMin, base + 1440)
                    val next = ((day + 1) % 7) * 1440
                    bits.set(next, next + w.endMin)
                }
            }
        }
        return bits
    }

    fun covers(newer: BitSet, older: BitSet): Boolean {
        val missing = older.clone() as BitSet
        missing.andNot(newer)
        return missing.isEmpty
    }

    private fun refuse(r: Refusal) = EditCheck.Refused(r)

    /** Compares one schedule edit; [old] null = create, [new] null = delete. */
    fun checkSchedule(old: Schedule?, new: Schedule?, now: Long): EditCheck {
        if (old == null) return EditCheck.Allowed
        if (new == null) return refuse(Refusal.DELETED)
        // Activation
        if (old.enabled && !new.enabled) return refuse(Refusal.DISABLED)
        val oldPause = old.pausedUntil?.takeIf { it > now }
        val newPause = new.pausedUntil?.takeIf { it > now }
        if (newPause != null && (oldPause == null || newPause > oldPause)) return refuse(Refusal.PAUSED)
        val oldManual = old.manualUntil?.takeIf { it > now }
        val newManual = new.manualUntil?.takeIf { it > now }
        if (oldManual != null && (newManual == null || newManual < oldManual)) return refuse(Refusal.MANUAL_SHORTENED)
        if (old.strictFollowed && !new.strictFollowed) return refuse(Refusal.UNFOLLOWED)
        // Mode
        if (old.mode != new.mode) return refuse(Refusal.MODE_CHANGED)
        if (old.allowlistWeb == AllowlistWeb.ONLY_LISTED &&
            new.allowlistWeb == AllowlistWeb.OPEN
        ) {
            return refuse(Refusal.ALLOWLIST_WEB_OPENED)
        }
        // Trigger structure
        if (old.combine == Combine.ANY && new.combine == Combine.ALL) return refuse(Refusal.COMBINE_TIGHTENED)
        triggerRows(old, new)?.let { return refuse(it) }
        if (!covers(coverage(new), coverage(old))) return refuse(Refusal.TIME_COVERAGE_REDUCED)
        // Limits
        limits(old, new)?.let { return refuse(it) }
        // Lists
        lists(old, new)?.let { return refuse(it) }
        // Flags
        if ((old.blockNotifications && !new.blockNotifications) || (old.blockUnsupportedBrowsers && !new.blockUnsupportedBrowsers)) {
            return refuse(Refusal.FLAG_TURNED_OFF)
        }
        return EditCheck.Allowed
    }

    private fun LocationCondition.key() = LocationCondition(0, label, lat, lon, radiusM, inverted)

    private fun triggerRows(old: Schedule, new: Schedule): Refusal? {
        val oldSsids = old.wifi.map { it.ssid }.toSet()
        val newSsids = new.wifi.map { it.ssid }.toSet()
        val oldLocs = old.locations.map { it.key() }.toSet()
        val newLocs = new.locations.map { it.key() }.toSet()
        if (!newSsids.containsAll(oldSsids) || !newLocs.containsAll(oldLocs)) return Refusal.TRIGGER_REMOVED_OR_EDITED
        val oldTypes = old.triggerTypes
        for (type in new.triggerTypes - oldTypes) {
            // Time windows are judged by the coverage bitmap.
            if (type == TriggerType.TIME) continue
            if (oldTypes.isEmpty() || new.combine == Combine.ALL) return Refusal.TRIGGER_ADDED
        }
        return null
    }

    private fun limits(old: Schedule, new: Schedule): Refusal? {
        if (new.limits.isEmpty()) return null
        for (n in new.limits) {
            val o = old.limit(n.kind) ?: return Refusal.LIMIT_ADDED
            if (n.allowance > o.allowance) return Refusal.LIMIT_RAISED
            if (o.period == Period.DAILY && n.period == Period.HOURLY) return Refusal.LIMIT_PERIOD_SHORTENED
        }
        if (old.limits.any { new.limit(it.kind) == null }) return Refusal.LIMIT_REMOVED
        return null
    }

    private fun lists(old: Schedule, new: Schedule): Refusal? = when (old.mode) {
        Mode.BLOCKLIST -> when {
            !new.apps.toSet().containsAll(old.apps) -> Refusal.BLOCKLIST_ITEM_REMOVED
            !rulesStillBlocked(old.webRules, new.webRules) -> Refusal.BLOCKLIST_ITEM_REMOVED
            else -> null
        }
        Mode.ALLOWLIST -> when {
            !old.apps.toSet().containsAll(new.apps) -> Refusal.ALLOWLIST_ITEM_ADDED
            old.allowlistWeb == AllowlistWeb.ONLY_LISTED && !ruleSet(old.webRules).containsAll(ruleSet(new.webRules)) ->
                Refusal.ALLOWLIST_ITEM_ADDED
            else -> null
        }
    }

    private fun ruleSet(rules: List<WebRule>) = rules.map { Triple(it.kind, it.value, it.anywhereInUrl) }.toSet()

    /** Every old blocking rule is still present (a keyword may widen to anywhere-in-URL). */
    fun rulesStillBlocked(old: List<WebRule>, new: List<WebRule>): Boolean = old.all { o ->
        new.any { n -> n.kind == o.kind && n.value == o.value && (n.anywhereInUrl || !o.anywhereInUrl) }
    }

    fun checkQuickBlock(old: QuickBlock, new: QuickBlock, now: Long): EditCheck {
        val oldUntil = old.until ?: Long.MAX_VALUE
        val newUntil = new.until ?: Long.MAX_VALUE
        val oldOn = old.active && oldUntil > now
        val newOn = new.active && newUntil > now
        if (oldOn && !newOn) return refuse(Refusal.QUICK_BLOCK_STOPPED)
        if (oldOn && newUntil < oldUntil) return refuse(Refusal.QUICK_BLOCK_SHORTENED)
        if (!new.apps.toSet().containsAll(old.apps) || !rulesStillBlocked(old.webRules, new.webRules)) {
            return refuse(Refusal.BLOCKLIST_ITEM_REMOVED)
        }
        if (old.blockNotifications && !new.blockNotifications) return refuse(Refusal.FLAG_TURNED_OFF)
        return EditCheck.Allowed
    }

    fun checkExcludedBrowsers(old: Set<String>, new: Set<String>): EditCheck =
        if (old.containsAll(new)) EditCheck.Allowed else refuse(Refusal.EXCLUDED_BROWSER_ADDED)

    /** Notification preferences are free; dayStartHour is locked. */
    fun checkPrefs(old: Prefs, new: Prefs): EditCheck =
        if (old.dayStartHour != new.dayStartHour || old.pendingDayStartHour != new.pendingDayStartHour) {
            refuse(Refusal.SETTING_LOCKED)
        } else {
            EditCheck.Allowed
        }

    /** While ON: only extending TIMER and following more (followAllSchedules false→true) (§9.4 "Strict"). */
    fun checkStrict(old: StrictModeRow, new: StrictModeRow): EditCheck {
        val same = old.state == new.state &&
            old.options == new.options &&
            old.method == new.method &&
            old.cooldownMin == new.cooldownMin &&
            old.pinHash == new.pinHash &&
            old.pinSalt == new.pinSalt
        if (!same) return refuse(Refusal.STRICT_CHANGED)
        if (old.followAllSchedules && !new.followAllSchedules) return refuse(Refusal.UNFOLLOWED)
        if (old.method == StrictMethod.TIMER) {
            val wallOk = (new.endsAt ?: Long.MIN_VALUE) >= (old.endsAt ?: Long.MIN_VALUE)
            val elOk = (new.endsAtElapsed ?: Long.MIN_VALUE) >= (old.endsAtElapsed ?: Long.MIN_VALUE)
            if (!wallOk || !elOk) return refuse(Refusal.STRICT_CHANGED)
        } else if (old.endsAt != new.endsAt) {
            return refuse(Refusal.STRICT_CHANGED)
        }
        return EditCheck.Allowed
    }
}

/** Which edits the lock covers right now (§9.4, §9.5). */
enum class LockScope { NONE, FOLLOWED, ALL }

/**
 * Decides whether LOCK_EDITING applies and delegates to [WeakenChecker]. Call before every save.
 * `display` is the current displayed strict state (from the last evaluation).
 */
object EditGuard {
    fun scope(row: StrictModeRow, display: StrictDisplayState): LockScope = when {
        !row.isOn || !row.has(StrictOptions.LOCK_EDITING) -> LockScope.NONE
        display == StrictDisplayState.ACTIVE -> LockScope.ALL
        display == StrictDisplayState.STANDBY -> LockScope.FOLLOWED
        else -> LockScope.NONE
    }

    /** Whether edits to [scheduleId] (or [QUICK_BLOCK_ID]) are locked. */
    fun isLocked(row: StrictModeRow, display: StrictDisplayState, schedules: List<Schedule>, scheduleId: Long): Boolean =
        when (scope(row, display)) {
            LockScope.NONE -> false
            LockScope.ALL -> true
            LockScope.FOLLOWED -> scheduleId in StrictPolicy.followed(row, schedules)
        }

    fun checkSchedule(
        row: StrictModeRow,
        display: StrictDisplayState,
        schedules: List<Schedule>,
        old: Schedule?,
        new: Schedule?,
        now: Long,
    ): EditCheck {
        val id = old?.id ?: return EditCheck.Allowed
        if (!isLocked(row, display, schedules, id)) return EditCheck.Allowed
        return WeakenChecker.checkSchedule(old, new, now)
    }

    /** Pause (§8): refused whenever LOCK_EDITING applies to the schedule. */
    fun checkPause(
        row: StrictModeRow,
        display: StrictDisplayState,
        schedules: List<Schedule>,
        s: Schedule,
        until: Long,
        now: Long
    ): EditCheck =
        checkSchedule(row, display, schedules, s, s.copy(pausedUntil = until), now)

    /** Turn off (§8): `enabled = false`. */
    fun checkTurnOff(row: StrictModeRow, display: StrictDisplayState, schedules: List<Schedule>, s: Schedule, now: Long): EditCheck =
        checkSchedule(row, display, schedules, s, s.copy(enabled = false, pausedUntil = null), now)

    fun checkDelete(row: StrictModeRow, display: StrictDisplayState, schedules: List<Schedule>, s: Schedule, now: Long): EditCheck =
        checkSchedule(row, display, schedules, s, null, now)

    fun checkQuickBlock(
        row: StrictModeRow,
        display: StrictDisplayState,
        schedules: List<Schedule>,
        old: QuickBlock,
        new: QuickBlock,
        now: Long,
    ): EditCheck =
        if (isLocked(row, display, schedules, QUICK_BLOCK_ID)) WeakenChecker.checkQuickBlock(old, new, now) else EditCheck.Allowed

    fun checkExcludedBrowsers(row: StrictModeRow, display: StrictDisplayState, old: Set<String>, new: Set<String>): EditCheck =
        if (scope(row, display) == LockScope.ALL) WeakenChecker.checkExcludedBrowsers(old, new) else EditCheck.Allowed

    fun checkPrefs(row: StrictModeRow, display: StrictDisplayState, old: Prefs, new: Prefs): EditCheck =
        if (scope(row, display) == LockScope.ALL) WeakenChecker.checkPrefs(old, new) else EditCheck.Allowed

    /** Strict row edits are restricted whenever Strict Mode is ON (any option set). */
    fun checkStrict(old: StrictModeRow, new: StrictModeRow): EditCheck =
        if (old.isOn) WeakenChecker.checkStrict(old, new) else EditCheck.Allowed

    /** Import is refused while Strict Mode is ON, ACTIVE or STANDBY (§4.3). */
    fun checkImport(row: StrictModeRow): EditCheck = if (row.isOn) EditCheck.Refused(Refusal.IMPORT_LOCKED) else EditCheck.Allowed

    /** "Not a browser? Allow" is hidden while Strict Mode is ON (§7.7). */
    fun canExcludeBrowserFromBlockScreen(row: StrictModeRow): Boolean = !row.isOn
}
