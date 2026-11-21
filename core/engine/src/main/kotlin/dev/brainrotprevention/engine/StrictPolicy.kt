package dev.brainrotprevention.engine

import dev.brainrotprevention.model.EmergencyUnblock
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.StrictState
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Schedules followed by Strict Mode SCHEDULES (§9.5). */
data class FollowedSet(val scheduleIds: Set<Long>, val quickBlock: Boolean) {
    val isEmpty: Boolean get() = scheduleIds.isEmpty() && !quickBlock

    operator fun contains(scheduleId: Long): Boolean =
        if (scheduleId == QUICK_BLOCK_ID) quickBlock else scheduleId in scheduleIds
}

/** Platform facts for the activation preconditions (§9.2). */
data class StrictFacts(
    val accessibilityConnected: Boolean,
    val usageAccessGranted: Boolean,
    /** Our service is in ACCESSIBILITY_SHORTCUT_TARGET_SERVICE or accessibility_button_targets. */
    val inAccessibilityShortcut: Boolean,
    /** `isAdminActive` (required before saving ON with BLOCK_UNINSTALL). */
    val adminActive: Boolean,
)

/** A Strict Mode activation request (§9.2, §9.5). */
data class StrictActivation(
    val method: StrictMethod,
    val options: Int,
    /** TIMER duration, 15 min – 30 days. */
    val durationMs: Long? = null,
    /** PIN, 4–8 digits. */
    val pin: String? = null,
    /** COOLDOWN minutes: 5, 10, 15 or 30. */
    val cooldownMin: Int? = null,
    val followAllSchedules: Boolean = false,
)

enum class ActivationRefusal {
    ALREADY_ON,
    ACCESSIBILITY_NOT_CONNECTED,
    NO_USAGE_ACCESS,
    IN_ACCESSIBILITY_SHORTCUT,
    INVALID_OPTIONS,
    ADMIN_NOT_ACTIVE,
    TIMER_OUT_OF_RANGE,
    INVALID_PIN,
    INVALID_COOLDOWN,
    FOLLOWED_SET_EMPTY,
}

sealed interface ActivationResult {
    data class Activated(val row: StrictModeRow) : ActivationResult

    data class Refused(val reason: ActivationRefusal) : ActivationResult
}

enum class UnlockRefusal { NOT_ON, WRONG_METHOD, NOT_PLUGGED, PIN_LOCKED_OUT, COOLDOWN_RUNNING, NOT_IN_STANDBY }

sealed interface UnlockResult {
    /** Persist [row] (INACTIVE) and remove the device admin. */
    data class Unlocked(val row: StrictModeRow) : UnlockResult

    /** Wrong PIN: persist [row] (failure count / lockout). [lockedUntil] is the wall lockout end, if any. */
    data class WrongPin(val row: StrictModeRow, val lockedUntil: Long?) : UnlockResult

    /** [remainingMs] for waits (cooldown, lockout), else null. */
    data class Refused(val reason: UnlockRefusal, val remainingMs: Long? = null) : UnlockResult
}

/** Result of a tamper check (§9.6). Persist [row] and [emergencyLog] when [changed]. */
data class TamperCheck(
    val row: StrictModeRow,
    val emergencyLog: List<EmergencyUnblock>,
    val changed: Boolean,
    val skewApplied: Long,
    val bootDetected: Boolean,
)

sealed interface EmergencyResult {
    /** Persist [row] (INACTIVE), insert [entry], remove the device admin. */
    data class Done(val row: StrictModeRow, val entry: EmergencyUnblock) : EmergencyResult

    data class NotAvailableUntil(val at: Long) : EmergencyResult

    data class CountdownRunning(val remainingMs: Long) : EmergencyResult

    data object NotOn : EmergencyResult
}

/** §9: activation, displayed state, unlock, tamper resistance, emergency unblock. */
object StrictPolicy {
    const val TIMER_MIN_MS = 15 * Periods.MINUTE
    const val TIMER_MAX_MS = 30 * Periods.DAY
    val COOLDOWN_CHOICES = setOf(5, 10, 15, 30)
    const val STANDBY_TURN_OFF_COOLDOWN_MS = 5 * Periods.MINUTE
    const val SKEW_THRESHOLD_MS = 60_000L
    const val PIN_ITERATIONS = 120_000
    const val PIN_MAX_FAILURES = 5
    const val PIN_BASE_LOCKOUT_MS = 5 * Periods.MINUTE
    const val PIN_MAX_LOCKOUT_MS = Periods.HOUR
    const val EMERGENCY_WINDOW_MS = 30 * Periods.DAY
    const val EMERGENCY_COUNTDOWN_MS = 60_000L
    private val pinRegex = Regex("^\\d{4,8}$")

    /** §9.5: followAll = every non-ALLOWLIST schedule plus Quick Block; else strictFollowed BLOCKLIST ones. */
    fun followed(row: StrictModeRow, schedules: List<Schedule>): FollowedSet = if (row.followAllSchedules) {
        FollowedSet(schedules.filter { it.mode != Mode.ALLOWLIST }.mapTo(HashSet()) { it.id }, true)
    } else {
        FollowedSet(schedules.filter { it.strictFollowed && it.mode == Mode.BLOCKLIST }.mapTo(HashSet()) { it.id }, false)
    }

    /** ALLOWLIST schedules shown as "not covered by Strict Mode" when following all (§9.5). */
    fun notCovered(schedules: List<Schedule>): List<Long> = schedules.filter { it.mode == Mode.ALLOWLIST }.map { it.id }

    /** Schedules paused or turned off, for the activation screen's "Resume all" (§9.2). */
    fun pausedOrOff(schedules: List<Schedule>, now: Long): List<Long> =
        schedules.filter { !it.enabled || ScheduleEvaluator.isPaused(it, now) }.map { it.id }

    /** Displayed state (§9.5). [statuses] by schedule id, including [QUICK_BLOCK_ID]. */
    fun displayState(row: StrictModeRow, schedules: List<Schedule>, statuses: Map<Long, ScheduleStatus>): StrictDisplayState {
        if (!row.isOn) return StrictDisplayState.INACTIVE
        if (row.method != StrictMethod.SCHEDULES) return StrictDisplayState.ACTIVE
        val f = followed(row, schedules)
        if (f.isEmpty) return StrictDisplayState.INACTIVE
        val active = statuses.values.any { it.scheduleId in f && it.blockingOrLaunchesExhausted }
        return if (active) StrictDisplayState.ACTIVE else StrictDisplayState.STANDBY
    }

    /** SCHEDULES with an empty followed set becomes INACTIVE (§9.5). */
    fun shouldEndForEmptyFollowedSet(row: StrictModeRow, schedules: List<Schedule>): Boolean =
        row.isOn && row.method == StrictMethod.SCHEDULES && followed(row, schedules).isEmpty

    fun checkActivation(
        req: StrictActivation,
        facts: StrictFacts,
        schedules: List<Schedule>,
        current: StrictModeRow,
    ): ActivationRefusal? = when {
        current.isOn -> ActivationRefusal.ALREADY_ON
        !facts.accessibilityConnected -> ActivationRefusal.ACCESSIBILITY_NOT_CONNECTED
        !facts.usageAccessGranted -> ActivationRefusal.NO_USAGE_ACCESS
        facts.inAccessibilityShortcut -> ActivationRefusal.IN_ACCESSIBILITY_SHORTCUT
        !StrictOptions.isValid(req.options) -> ActivationRefusal.INVALID_OPTIONS
        StrictOptions.has(req.options, StrictOptions.BLOCK_UNINSTALL) && !facts.adminActive -> ActivationRefusal.ADMIN_NOT_ACTIVE
        req.method == StrictMethod.TIMER && (req.durationMs == null || req.durationMs !in TIMER_MIN_MS..TIMER_MAX_MS) ->
            ActivationRefusal.TIMER_OUT_OF_RANGE
        req.method == StrictMethod.PIN && (req.pin == null || !pinRegex.matches(req.pin)) -> ActivationRefusal.INVALID_PIN
        req.method == StrictMethod.COOLDOWN && req.cooldownMin !in COOLDOWN_CHOICES -> ActivationRefusal.INVALID_COOLDOWN
        req.method == StrictMethod.SCHEDULES &&
            followed(StrictModeRow(followAllSchedules = req.followAllSchedules), schedules).isEmpty -> ActivationRefusal.FOLLOWED_SET_EMPTY
        else -> null
    }

    /** Checks preconditions and builds the ON row. Keeps the tamper bookkeeping of [current]. */
    fun activate(
        req: StrictActivation,
        facts: StrictFacts,
        schedules: List<Schedule>,
        current: StrictModeRow,
        wallNow: Long,
        elapsedNow: Long,
        salt: ByteArray = newSalt(),
    ): ActivationResult {
        checkActivation(req, facts, schedules, current)?.let { return ActivationResult.Refused(it) }
        val timer = req.method == StrictMethod.TIMER
        val pin = req.pin?.takeIf { req.method == StrictMethod.PIN }
        return ActivationResult.Activated(
            StrictModeRow(
                state = StrictState.ON,
                options = req.options,
                method = req.method,
                followAllSchedules = req.method == StrictMethod.SCHEDULES && req.followAllSchedules,
                endsAt = if (timer) wallNow + req.durationMs!! else null,
                endsAtElapsed = if (timer) elapsedNow + req.durationMs!! else null,
                lastCheckWall = wallNow,
                lastCheckElapsed = elapsedNow,
                pinHash = pin?.let { hashPin(it, salt) },
                pinSalt = pin?.let { Base64.getEncoder().encodeToString(salt) },
                cooldownMin = req.cooldownMin ?: current.cooldownMin,
                activatedAt = wallNow,
                bootCount = current.bootCount,
            ),
        )
    }

    /** The INACTIVE row after any unlock; tamper bookkeeping is kept (it also guards the emergency window). */
    fun deactivated(row: StrictModeRow): StrictModeRow =
        StrictModeRow(
            lastCheckWall = row.lastCheckWall,
            lastCheckElapsed = row.lastCheckElapsed,
            cooldownMin = row.cooldownMin,
            bootCount = row.bootCount,
        )

    /** Whether [extendTimer] applies: Strict Mode is ON with a TIMER, and [extraMs] > 0. */
    fun canExtendTimer(row: StrictModeRow, extraMs: Long): Boolean = row.isOn && row.method == StrictMethod.TIMER && extraMs > 0

    /** Extends a running TIMER by [extraMs] (allowed under LOCK_EDITING). Precondition: [canExtendTimer]. */
    fun extendTimer(row: StrictModeRow, extraMs: Long): StrictModeRow {
        require(canExtendTimer(row, extraMs))
        return row.copy(endsAt = row.endsAt?.plus(extraMs), endsAtElapsed = row.endsAtElapsed?.plus(extraMs))
    }

    /**
     * §9.6, on TIME_SET, TIMEZONE_CHANGED, every minute and on ACTION_SHUTDOWN. Applies the wall/elapsed
     * skew (> 60 s, both directions) to endsAt, pinLockedUntilWall and the emergency log, detects a
     * reboot and stores lastCheck. Runs whether Strict Mode is on or not.
     *
     * A reboot is detected when [bootCount] (`Settings.Global.BOOT_COUNT`) differs from the stored one, or when
     * elapsed went back; without the boot count a reboot after a short previous uptime would look like a
     * forward jump. A skew of 60 s or less is not applied but carried: `lastCheckWall` moves by the elapsed
     * time only, so small jumps add up until they pass the threshold.
     */
    fun check(row: StrictModeRow, log: List<EmergencyUnblock>, wallNow: Long, elapsedNow: Long, bootCount: Int? = null): TamperCheck {
        val lw = row.lastCheckWall
        val le = row.lastCheckElapsed
        val boot = bootCount ?: row.bootCount
        if (lw == null || le == null) {
            return TamperCheck(row.copy(lastCheckWall = wallNow, lastCheckElapsed = elapsedNow, bootCount = boot), log, true, 0, false)
        }
        val rebooted = elapsedNow < le || (bootCount != null && row.bootCount != null && bootCount != row.bootCount)
        if (rebooted) return TamperCheck(afterBoot(row, wallNow, elapsedNow).copy(bootCount = boot), log, true, 0, true)
        val skew = (wallNow - lw) - (elapsedNow - le)
        var r = row
        var newLog = log
        val applied = if (kotlin.math.abs(skew) > SKEW_THRESHOLD_MS) skew else 0L
        if (applied != 0L) {
            r = r.copy(endsAt = r.endsAt?.plus(applied), pinLockedUntilWall = r.pinLockedUntilWall?.plus(applied))
            newLog = log.map { it.copy(at = it.at + applied) }
        }
        // Unapplied skew stays in lastCheckWall (wallNow - skew), so it accumulates.
        r = r.copy(lastCheckWall = wallNow - (skew - applied), lastCheckElapsed = elapsedNow, bootCount = boot)
        return TamperCheck(r, newLog, true, applied, false)
    }

    /** After boot elapsed restarts: `endsAtElapsed = elapsedNow + max(0, endsAt − wallNow)`, same for the PIN lockout. */
    fun afterBoot(row: StrictModeRow, wallNow: Long, elapsedNow: Long): StrictModeRow = row.copy(
        endsAtElapsed = row.endsAt?.let { elapsedNow + maxOf(0, it - wallNow) },
        pinLockedUntilElapsed = row.pinLockedUntilWall?.let { elapsedNow + maxOf(0, it - wallNow) },
        lastCheckWall = wallNow,
        lastCheckElapsed = elapsedNow,
    )

    /** Expired means both deadlines passed (§9.6). */
    fun deadlinePassed(wallDeadline: Long?, elapsedDeadline: Long?, wallNow: Long, elapsedNow: Long): Boolean =
        (wallDeadline == null || wallNow >= wallDeadline) && (elapsedDeadline == null || elapsedNow >= elapsedDeadline)

    fun timerExpired(row: StrictModeRow, wallNow: Long, elapsedNow: Long): Boolean =
        row.isOn &&
            row.method == StrictMethod.TIMER &&
            row.endsAt != null &&
            deadlinePassed(row.endsAt, row.endsAtElapsed, wallNow, elapsedNow)

    /** Remaining TIMER time (the larger of the wall and elapsed remainders). */
    fun timerRemaining(row: StrictModeRow, wallNow: Long, elapsedNow: Long): Long {
        val endsAt = row.endsAt ?: return 0
        val wall = endsAt - wallNow
        val el = row.endsAtElapsed?.let { it - elapsedNow } ?: wall
        return maxOf(0, wall, el)
    }

    fun unlockWithCharger(row: StrictModeRow, pluggedIn: Boolean): UnlockResult = when {
        !row.isOn -> UnlockResult.Refused(UnlockRefusal.NOT_ON)
        row.method != StrictMethod.CHARGER -> UnlockResult.Refused(UnlockRefusal.WRONG_METHOD)
        !pluggedIn -> UnlockResult.Refused(UnlockRefusal.NOT_PLUGGED)
        else -> UnlockResult.Unlocked(deactivated(row))
    }

    fun pinLockedOut(row: StrictModeRow, wallNow: Long, elapsedNow: Long): Boolean =
        (row.pinLockedUntilWall != null || row.pinLockedUntilElapsed != null) &&
            !deadlinePassed(row.pinLockedUntilWall, row.pinLockedUntilElapsed, wallNow, elapsedNow)

    /** Lockout after [failures] failures: 5 min at 5, doubling, capped at 1 h; 0 below 5. */
    fun lockoutMs(failures: Int): Long {
        if (failures < PIN_MAX_FAILURES) return 0
        val shift = (failures - PIN_MAX_FAILURES).coerceAtMost(10)
        return (PIN_BASE_LOCKOUT_MS shl shift).coerceAtMost(PIN_MAX_LOCKOUT_MS)
    }

    fun unlockWithPin(row: StrictModeRow, pin: String, wallNow: Long, elapsedNow: Long): UnlockResult {
        if (!row.isOn) return UnlockResult.Refused(UnlockRefusal.NOT_ON)
        if (row.method != StrictMethod.PIN) return UnlockResult.Refused(UnlockRefusal.WRONG_METHOD)
        if (pinLockedOut(row, wallNow, elapsedNow)) {
            val rem = maxOf((row.pinLockedUntilWall ?: 0) - wallNow, (row.pinLockedUntilElapsed ?: 0) - elapsedNow)
            return UnlockResult.Refused(UnlockRefusal.PIN_LOCKED_OUT, rem)
        }
        if (verifyPin(row, pin)) return UnlockResult.Unlocked(deactivated(row))
        val failures = row.pinFailures + 1
        val lock = lockoutMs(failures)
        val r = row.copy(
            pinFailures = failures,
            pinLockedUntilWall = if (lock > 0) wallNow + lock else null,
            pinLockedUntilElapsed = if (lock > 0) elapsedNow + lock else null,
        )
        return UnlockResult.WrongPin(r, r.pinLockedUntilWall)
    }

    fun newSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /** PBKDF2-HMAC-SHA256, 120 000 iterations, 256-bit; Base64. */
    fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PIN_ITERATIONS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Base64.getEncoder().encodeToString(key)
    }

    fun verifyPin(row: StrictModeRow, pin: String): Boolean {
        val hash = row.pinHash ?: return false
        val salt = row.pinSalt?.let { Base64.getDecoder().decode(it) } ?: return false
        return MessageDigest.isEqual(Base64.getDecoder().decode(hash), Base64.getDecoder().decode(hashPin(pin, salt)))
    }

    /** Remaining wait of an on-screen elapsed-time wait. */
    fun waitRemaining(startedElapsed: Long, nowElapsed: Long, durationMs: Long): Long =
        (durationMs - (nowElapsed - startedElapsed)).coerceIn(0, durationMs)

    /** COOLDOWN unlock: the wait started at [startedElapsed] on the Strict screen (§9.5). */
    fun unlockAfterCooldown(row: StrictModeRow, startedElapsed: Long, nowElapsed: Long): UnlockResult {
        if (!row.isOn) return UnlockResult.Refused(UnlockRefusal.NOT_ON)
        if (row.method != StrictMethod.COOLDOWN) return UnlockResult.Refused(UnlockRefusal.WRONG_METHOD)
        val rem = waitRemaining(startedElapsed, nowElapsed, row.cooldownMin * Periods.MINUTE)
        return if (rem > 0) UnlockResult.Refused(UnlockRefusal.COOLDOWN_RUNNING, rem) else UnlockResult.Unlocked(deactivated(row))
    }

    /** SCHEDULES: turn off from STANDBY after the fixed 5-minute wait (§9.5). */
    fun turnOffFromStandby(row: StrictModeRow, display: StrictDisplayState, startedElapsed: Long, nowElapsed: Long): UnlockResult {
        if (!row.isOn) return UnlockResult.Refused(UnlockRefusal.NOT_ON)
        if (row.method != StrictMethod.SCHEDULES) return UnlockResult.Refused(UnlockRefusal.WRONG_METHOD)
        if (display != StrictDisplayState.STANDBY) return UnlockResult.Refused(UnlockRefusal.NOT_IN_STANDBY)
        val rem = waitRemaining(startedElapsed, nowElapsed, STANDBY_TURN_OFF_COOLDOWN_MS)
        return if (rem > 0) UnlockResult.Refused(UnlockRefusal.COOLDOWN_RUNNING, rem) else UnlockResult.Unlocked(deactivated(row))
    }

    /** Null when emergency unblock is available now; else when it will be (1 per rolling 30 days, §9.7). */
    fun emergencyAvailableAt(log: List<EmergencyUnblock>, wallNow: Long): Long? {
        val latest = log.maxOfOrNull { it.at } ?: return null
        val at = latest + EMERGENCY_WINDOW_MS
        return if (wallNow >= at) null else at
    }

    /** Completes emergency unblock after the 60 s countdown that started at [countdownStartedElapsed]. */
    fun emergencyUnblock(
        row: StrictModeRow,
        log: List<EmergencyUnblock>,
        wallNow: Long,
        countdownStartedElapsed: Long,
        elapsedNow: Long,
    ): EmergencyResult {
        if (!row.isOn) return EmergencyResult.NotOn
        emergencyAvailableAt(log, wallNow)?.let { return EmergencyResult.NotAvailableUntil(it) }
        val rem = waitRemaining(countdownStartedElapsed, elapsedNow, EMERGENCY_COUNTDOWN_MS)
        if (rem > 0) return EmergencyResult.CountdownRunning(rem)
        return EmergencyResult.Done(deactivated(row), EmergencyUnblock(at = wallNow))
    }
}
