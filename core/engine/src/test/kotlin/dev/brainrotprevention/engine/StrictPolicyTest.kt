package dev.brainrotprevention.engine

import dev.brainrotprevention.model.EmergencyUnblock
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.StrictState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictPolicyTest {
    private val facts =
        StrictFacts(accessibilityConnected = true, usageAccessGranted = true, inAccessibilityShortcut = false, adminActive = true)
    private val bl = Schedule(id = 1, name = "a", strictFollowed = true)
    private val al = Schedule(id = 2, name = "b", mode = Mode.ALLOWLIST)
    private val wall0 = at("2026-11-23T10:00")
    private val el0 = 10_000_000L

    private fun timerRow(durationMs: Long = 30 * MIN): StrictModeRow {
        val r = StrictPolicy.activate(
            StrictActivation(StrictMethod.TIMER, StrictOptions.LOCK_EDITING, durationMs = durationMs),
            facts,
            emptyList(),
            StrictModeRow(),
            wall0,
            el0
        )
        return (r as ActivationResult.Activated).row
    }

    @Test
    fun activationPreconditions() {
        data class C(val req: StrictActivation, val facts: StrictFacts, val schedules: List<Schedule>, val expected: ActivationRefusal?)
        val timer = StrictActivation(StrictMethod.TIMER, 7, durationMs = 20 * MIN)
        val cases = listOf(
            C(timer, facts, emptyList(), null),
            C(timer, facts.copy(accessibilityConnected = false), emptyList(), ActivationRefusal.ACCESSIBILITY_NOT_CONNECTED),
            C(timer, facts.copy(usageAccessGranted = false), emptyList(), ActivationRefusal.NO_USAGE_ACCESS),
            C(timer, facts.copy(inAccessibilityShortcut = true), emptyList(), ActivationRefusal.IN_ACCESSIBILITY_SHORTCUT),
            C(timer, facts.copy(adminActive = false), emptyList(), ActivationRefusal.ADMIN_NOT_ACTIVE),
            C(timer.copy(options = StrictOptions.BLOCK_UNINSTALL), facts, emptyList(), ActivationRefusal.INVALID_OPTIONS),
            C(timer.copy(options = 3), facts.copy(adminActive = false), emptyList(), null),
            C(timer.copy(durationMs = 14 * MIN), facts, emptyList(), ActivationRefusal.TIMER_OUT_OF_RANGE),
            C(timer.copy(durationMs = 31 * Periods.DAY), facts, emptyList(), ActivationRefusal.TIMER_OUT_OF_RANGE),
            C(StrictActivation(StrictMethod.PIN, 1, pin = "123"), facts, emptyList(), ActivationRefusal.INVALID_PIN),
            C(StrictActivation(StrictMethod.PIN, 1, pin = "12a4"), facts, emptyList(), ActivationRefusal.INVALID_PIN),
            C(StrictActivation(StrictMethod.COOLDOWN, 1, cooldownMin = 7), facts, emptyList(), ActivationRefusal.INVALID_COOLDOWN),
            C(StrictActivation(StrictMethod.COOLDOWN, 1, cooldownMin = 10), facts, emptyList(), null),
            C(StrictActivation(StrictMethod.SCHEDULES, 1), facts, listOf(al), ActivationRefusal.FOLLOWED_SET_EMPTY),
            C(StrictActivation(StrictMethod.SCHEDULES, 1), facts, listOf(bl), null),
            C(StrictActivation(StrictMethod.SCHEDULES, 1, followAllSchedules = true), facts, listOf(al), null), // Quick Block is followed
            C(StrictActivation(StrictMethod.CHARGER, 1), facts, emptyList(), null),
        )
        for (c in cases) assertEquals(c.toString(), c.expected, StrictPolicy.checkActivation(c.req, c.facts, c.schedules, StrictModeRow()))
        assertEquals(ActivationRefusal.ALREADY_ON, StrictPolicy.checkActivation(timer, facts, emptyList(), timerRow()))
        assertEquals(
            ActivationResult.Refused(ActivationRefusal.NO_USAGE_ACCESS),
            StrictPolicy.activate(timer, facts.copy(usageAccessGranted = false), emptyList(), StrictModeRow(), 0, 0)
        )
    }

    @Test
    fun followedSetAndScheduleStates() {
        val other = Schedule(id = 3, name = "c")
        assertEquals(FollowedSet(setOf(1L), false), StrictPolicy.followed(StrictModeRow(), listOf(bl, al, other)))
        val all = StrictModeRow(state = StrictState.ON, method = StrictMethod.SCHEDULES, followAllSchedules = true)
        val f = StrictPolicy.followed(all, listOf(bl, al, other))
        assertEquals(setOf(1L, 3L), f.scheduleIds) // ALLOWLIST not followed
        assertTrue(QUICK_BLOCK_ID in f)
        assertEquals(listOf(2L), StrictPolicy.notCovered(listOf(bl, al, other)))
        assertEquals(listOf(3L), StrictPolicy.pausedOrOff(listOf(bl, other.copy(enabled = false)), 0))

        fun st(id: Long, blocking: Boolean, armed: Boolean = blocking, launchesExhausted: Boolean = false): ScheduleStatus {
            val l = launchLimit(1)
            val ls = if (launchesExhausted) LimitStatus(l, dev.brainrotprevention.model.UsageCounter(l.id, 0, 1), 0) else null
            return ScheduleStatus(id, id == QUICK_BLOCK_ID, true, false, armed, blocking, null, null, null, null, ls)
        }
        val row = StrictModeRow(state = StrictState.ON, method = StrictMethod.SCHEDULES)
        val schedules = listOf(bl, other)
        assertEquals(StrictDisplayState.ACTIVE, StrictPolicy.displayState(row, schedules, mapOf(1L to st(1, true))))
        assertEquals(StrictDisplayState.STANDBY, StrictPolicy.displayState(row, schedules, mapOf(1L to st(1, false), 3L to st(3, true))))
        assertEquals(
            StrictDisplayState.ACTIVE,
            StrictPolicy.displayState(
                row,
                schedules,
                mapOf(
                    1L to st(1, false, armed = true, launchesExhausted = true)
                )
            )
        )
        assertEquals(
            StrictDisplayState.STANDBY,
            StrictPolicy.displayState(
                row,
                schedules,
                mapOf(
                    1L to st(1, false, armed = false, launchesExhausted = true)
                )
            )
        )
        assertEquals(
            StrictDisplayState.ACTIVE,
            StrictPolicy.displayState(all, schedules, mapOf(QUICK_BLOCK_ID to st(QUICK_BLOCK_ID, true)))
        )
        assertEquals(StrictDisplayState.INACTIVE, StrictPolicy.displayState(row, listOf(other), emptyMap()))
        assertTrue(StrictPolicy.shouldEndForEmptyFollowedSet(row, listOf(other)))
        assertFalse(StrictPolicy.shouldEndForEmptyFollowedSet(row, schedules))
        assertEquals(StrictDisplayState.ACTIVE, StrictPolicy.displayState(timerRow(), emptyList(), emptyMap()))
        assertEquals(StrictDisplayState.INACTIVE, StrictPolicy.displayState(StrictModeRow(), emptyList(), emptyMap()))
    }

    @Test
    fun tamperTimerBothDirectionsAndReboot() {
        // S12: 30 min timer; jump +1 day, jump −1 day, then 30 min pass.
        var row = timerRow()
        var wall = wall0
        var el = el0
        var log = listOf(EmergencyUnblock(1, wall0 - Periods.DAY))
        fun check() {
            val c = StrictPolicy.check(row, log, wall, el)
            row = c.row
            log = c.emergencyLog
        }
        wall += Periods.DAY
        assertFalse(StrictPolicy.timerExpired(row, wall, el)) // elapsed deadline holds even before the check
        check()
        assertEquals(wall0 + Periods.DAY + 30 * MIN, row.endsAt)
        assertEquals(wall0, log.single().at) // log shifted too
        assertFalse(StrictPolicy.timerExpired(row, wall, el))
        wall -= Periods.DAY
        check()
        assertEquals(wall0 + 30 * MIN, row.endsAt) // backward jump never adds time
        assertEquals(30 * MIN, StrictPolicy.timerRemaining(row, wall, el))
        // Small drift (< 60 s) is ignored.
        wall += 10 * MIN + 30_000
        el += 10 * MIN
        val drift = StrictPolicy.check(row, log, wall, el)
        assertEquals(0, drift.skewApplied)
        row = drift.row
        // Reboot: elapsed restarts.
        el = 5_000
        val boot = StrictPolicy.check(row, log, wall, el)
        assertTrue(boot.bootDetected)
        row = boot.row
        assertEquals(el + (row.endsAt!! - wall), row.endsAtElapsed)
        wall += 20 * MIN
        el += 20 * MIN
        assertTrue(StrictPolicy.timerExpired(row, wall, el))
        // First check without bookkeeping only records lastCheck.
        val fresh = StrictPolicy.check(StrictModeRow(), emptyList(), 1, 2)
        assertEquals(1L, fresh.row.lastCheckWall)
        assertEquals(0, StrictPolicy.timerRemaining(StrictModeRow(), 0, 0))
    }

    @Test
    fun timerExpiryNeedsBothClocks() {
        val row = timerRow(20 * MIN)
        assertFalse(StrictPolicy.timerExpired(row, wall0 + 20 * MIN, el0 + 19 * MIN))
        assertFalse(StrictPolicy.timerExpired(row, wall0 + 19 * MIN, el0 + 20 * MIN))
        assertTrue(StrictPolicy.timerExpired(row, wall0 + 20 * MIN, el0 + 20 * MIN))
        val ext = StrictPolicy.extendTimer(row, 10 * MIN)
        assertFalse(StrictPolicy.timerExpired(ext, wall0 + 20 * MIN, el0 + 20 * MIN))
    }

    @Test
    fun pinUnlockAndLockout() {
        val act = StrictPolicy.activate(
            StrictActivation(StrictMethod.PIN, 1, pin = "4711"),
            facts,
            emptyList(),
            StrictModeRow(),
            wall0,
            el0
        ) as ActivationResult.Activated
        var row = act.row
        assertTrue(row.pinHash != null && row.pinHash != "4711")
        var wall = wall0
        var el = el0
        repeat(4) {
            val r = StrictPolicy.unlockWithPin(row, "0000", wall, el) as UnlockResult.WrongPin
            assertNull(r.lockedUntil)
            row = r.row
        }
        val fifth = StrictPolicy.unlockWithPin(row, "0000", wall, el) as UnlockResult.WrongPin
        assertEquals(wall + 5 * MIN, fifth.lockedUntil)
        row = fifth.row
        // Locked out even with the right PIN.
        assertEquals(UnlockRefusal.PIN_LOCKED_OUT, (StrictPolicy.unlockWithPin(row, "4711", wall, el) as UnlockResult.Refused).reason)
        // Clock jump forward doesn't end the lockout (elapsed deadline).
        assertTrue(StrictPolicy.pinLockedOut(row, wall + Periods.DAY, el))
        wall += 5 * MIN
        el += 5 * MIN
        val sixth = StrictPolicy.unlockWithPin(row, "0000", wall, el) as UnlockResult.WrongPin
        assertEquals(wall + 10 * MIN, sixth.lockedUntil)
        row = sixth.row
        wall += 10 * MIN
        el += 10 * MIN
        assertTrue(StrictPolicy.unlockWithPin(row, "4711", wall, el) is UnlockResult.Unlocked)
        assertEquals(
            listOf(0L, 5 * MIN, 10 * MIN, 20 * MIN, 40 * MIN, 60 * MIN, 60 * MIN),
            listOf(4, 5, 6, 7, 8, 9, 30).map {
                StrictPolicy.lockoutMs(it)
            }
        )
        assertEquals(UnlockRefusal.WRONG_METHOD, (StrictPolicy.unlockWithPin(timerRow(), "1", wall, el) as UnlockResult.Refused).reason)
        assertEquals(UnlockRefusal.NOT_ON, (StrictPolicy.unlockWithPin(StrictModeRow(), "1", wall, el) as UnlockResult.Refused).reason)
        assertFalse(StrictPolicy.verifyPin(StrictModeRow(), "1"))
        // Reboot recomputes the lockout's elapsed deadline from the wall remainder.
        val locked = fifth.row
        val booted = StrictPolicy.afterBoot(locked, wall0 + MIN, 100)
        assertEquals(100 + 4 * MIN, booted.pinLockedUntilElapsed)
    }

    @Test
    fun chargerCooldownAndStandbyTurnOff() {
        val charger = StrictModeRow(state = StrictState.ON, method = StrictMethod.CHARGER)
        assertEquals(UnlockRefusal.NOT_PLUGGED, (StrictPolicy.unlockWithCharger(charger, false) as UnlockResult.Refused).reason)
        val unlocked = StrictPolicy.unlockWithCharger(charger, true) as UnlockResult.Unlocked
        assertEquals(StrictState.INACTIVE, unlocked.row.state)
        assertEquals(UnlockRefusal.WRONG_METHOD, (StrictPolicy.unlockWithCharger(timerRow(), true) as UnlockResult.Refused).reason)
        assertEquals(UnlockRefusal.NOT_ON, (StrictPolicy.unlockWithCharger(StrictModeRow(), true) as UnlockResult.Refused).reason)

        val cd = StrictModeRow(state = StrictState.ON, method = StrictMethod.COOLDOWN, cooldownMin = 10)
        assertEquals(UnlockResult.Refused(UnlockRefusal.COOLDOWN_RUNNING, MIN), StrictPolicy.unlockAfterCooldown(cd, 0, 9 * MIN))
        assertTrue(StrictPolicy.unlockAfterCooldown(cd, 0, 10 * MIN) is UnlockResult.Unlocked)
        assertEquals(UnlockRefusal.WRONG_METHOD, (StrictPolicy.unlockAfterCooldown(charger, 0, 0) as UnlockResult.Refused).reason)
        assertEquals(UnlockRefusal.NOT_ON, (StrictPolicy.unlockAfterCooldown(StrictModeRow(), 0, 0) as UnlockResult.Refused).reason)

        val sch = StrictModeRow(state = StrictState.ON, method = StrictMethod.SCHEDULES)
        assertEquals(
            UnlockRefusal.NOT_IN_STANDBY,
            (StrictPolicy.turnOffFromStandby(sch, StrictDisplayState.ACTIVE, 0, 10 * MIN) as UnlockResult.Refused).reason
        )
        assertEquals(
            UnlockRefusal.COOLDOWN_RUNNING,
            (StrictPolicy.turnOffFromStandby(sch, StrictDisplayState.STANDBY, 0, 4 * MIN) as UnlockResult.Refused).reason
        )
        assertTrue(StrictPolicy.turnOffFromStandby(sch, StrictDisplayState.STANDBY, 0, 5 * MIN) is UnlockResult.Unlocked)
        assertEquals(
            UnlockRefusal.WRONG_METHOD,
            (StrictPolicy.turnOffFromStandby(cd, StrictDisplayState.STANDBY, 0, 5 * MIN) as UnlockResult.Refused).reason
        )
        assertEquals(
            UnlockRefusal.NOT_ON,
            (StrictPolicy.turnOffFromStandby(StrictModeRow(), StrictDisplayState.STANDBY, 0, 5 * MIN) as UnlockResult.Refused).reason
        )
    }

    @Test
    fun emergencyRateLimit() {
        val row = timerRow()
        // Countdown must run 60 s.
        assertEquals(EmergencyResult.CountdownRunning(30_000), StrictPolicy.emergencyUnblock(row, emptyList(), wall0, 0, 30_000))
        val done = StrictPolicy.emergencyUnblock(row, emptyList(), wall0, 0, 60_000) as EmergencyResult.Done
        assertEquals(StrictState.INACTIVE, done.row.state)
        assertEquals(wall0, done.entry.at)
        val log = listOf(done.entry)
        // Second use within 30 days refused, with the date it becomes available.
        val again = StrictPolicy.emergencyUnblock(row, log, wall0 + 29 * Periods.DAY, 0, 60_000)
        assertEquals(EmergencyResult.NotAvailableUntil(wall0 + 30 * Periods.DAY), again)
        assertNull(StrictPolicy.emergencyAvailableAt(log, wall0 + 30 * Periods.DAY))
        assertTrue(StrictPolicy.emergencyUnblock(row, log, wall0 + 30 * Periods.DAY, 0, 60_000) is EmergencyResult.Done)
        assertEquals(EmergencyResult.NotOn, StrictPolicy.emergencyUnblock(StrictModeRow(), emptyList(), wall0, 0, 60_000))
        // A forward clock jump is skew-adjusted into the log, so it doesn't make it available early.
        val jumped = StrictPolicy.check(
            done.row.copy(lastCheckWall = wall0, lastCheckElapsed = el0),
            log,
            wall0 + 31 * Periods.DAY,
            el0 + MIN
        )
        assertEquals(wall0 + 31 * Periods.DAY - MIN, jumped.emergencyLog.single().at)
        assertTrue(StrictPolicy.emergencyAvailableAt(jumped.emergencyLog, wall0 + 31 * Periods.DAY) != null)
    }
}
