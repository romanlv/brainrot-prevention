package dev.brainrotprevention.engine

import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.Prefs
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.SensorState
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictState
import dev.brainrotprevention.model.UsageCounter
import dev.brainrotprevention.model.Weekdays.MON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextInstantsAndNotificationsTest {
    private val now = at("2026-11-23T10:00")

    @Test
    fun allInstantKinds() {
        val s1 = Schedule(id = 1, name = "w", timeWindows = listOf(win(MON, "11:00", "12:00")), limits = listOf(timeLimit(30)))
        val s2 =
            Schedule(
                id = 2,
                name = "p",
                pausedUntil = now + 30 * MIN,
                manualUntil = now + 40 * MIN,
                limits = listOf(launchLimit(3, id = 201, period = Period.HOURLY))
            )
        val s3 = Schedule(id = 3, name = "off", enabled = false, timeWindows = listOf(win(MON, "10:30", "11:00")))
        val snap = ConfigSnapshot(
            schedules = listOf(s1, s2, s3),
            quickBlock = QuickBlock(active = true, until = now + 50 * MIN, apps = listOf(T)),
            strict = StrictModeRow(state = StrictState.ON, method = StrictMethod.TIMER, endsAt = now + 60 * MIN),
            prefs = Prefs(notifyBeforeStartMin = 10, notifyBeforePauseEndMin = 1, limitWarningMin = 5),
        )
        val all = NextInstants.all(snap, now, BERLIN, listOf(CountingBudget(1, 20 * MIN, false), CountingBudget(9, 20 * MIN, true)))
        val byKind = all.associate { it.kind to it.at }
        assertEquals(now + 15 * MIN, byKind[InstantKind.LIMIT_WARNING])
        assertEquals(now + 29 * MIN, byKind[InstantKind.PAUSE_END_WARNING])
        assertEquals(now + 30 * MIN, byKind[InstantKind.PAUSE_END])
        assertEquals(now + 40 * MIN, byKind[InstantKind.MANUAL_END])
        assertEquals(now + 50 * MIN, byKind[InstantKind.BEFORE_START])
        assertEquals(now + 50 * MIN, byKind[InstantKind.QUICK_BLOCK_END])
        assertEquals(now + 60 * MIN, byKind[InstantKind.WINDOW_START])
        assertEquals(now + 60 * MIN, byKind[InstantKind.STRICT_TIMER_END])
        assertEquals(now + 60 * MIN, byKind[InstantKind.HOURLY_RESET])
        assertEquals(now + 120 * MIN, byKind[InstantKind.WINDOW_END])
        assertEquals(at("2026-11-24T00:00"), byKind[InstantKind.DAILY_RESET])
        assertEquals(11, all.size) // disabled schedule and the already-warned budget add nothing
        assertEquals(all.sortedBy { it.at }, all)
        assertEquals(InstantKind.LIMIT_WARNING, NextInstants.next(snap, now, BERLIN, listOf(CountingBudget(1, 20 * MIN, false)))?.kind)
        assertNull(NextInstants.next(ConfigSnapshot(), now, BERLIN))
        // Manual "until stopped" has no end instant.
        val stopped = ConfigSnapshot(schedules = listOf(Schedule(id = 4, name = "m", manualUntil = Schedule.UNTIL_STOPPED)))
        assertNull(NextInstants.next(stopped, now, BERLIN))
    }

    private val facts = NotificationFacts(T)
    private val chunk = 15 * MIN

    @Test
    fun notificationNeverTouched() {
        val block = Until.At(now + 60 * MIN)
        val untouched = listOf(
            facts.copy(isOwn = true),
            facts.copy(ongoing = true),
            facts.copy(noClear = true),
            facts.copy(foregroundService = true),
            facts.copy(category = "call"),
            facts.copy(category = "ALARM"),
            facts.copy(category = "reminder"),
            facts.copy(hasFullScreenIntent = true),
        )
        for (f in untouched) assertEquals(f.toString(), NotificationAction.Leave, NotificationPolicy.decide(f, block, now, { it }, chunk))
        assertEquals(NotificationAction.Leave, NotificationPolicy.decide(facts, null, now, { it }, chunk))
        assertEquals(NotificationAction.Snooze(chunk), NotificationPolicy.decide(facts.copy(category = "msg"), block, now, { it }, chunk))
    }

    @Test
    fun snoozeDurations() {
        // Until soon: snooze until the end, converted to system time.
        assertEquals(
            NotificationAction.Snooze(4 * MIN),
            NotificationPolicy.decide(facts, Until.At(now + 5 * MIN), now - MIN + MIN, {
                it -
                    MIN
            }, chunk)
        )
        // Ongoing / while conditions: one chunk.
        assertEquals(NotificationAction.Snooze(chunk), NotificationPolicy.decide(facts, Until.Ongoing, now, { it }, chunk))
        assertEquals(NotificationAction.Snooze(5_000), NotificationPolicy.decide(facts, Until.WhileConditions("x"), now, { it }, 5_000))
        // Already over.
        assertEquals(NotificationAction.Leave, NotificationPolicy.decide(facts, Until.At(now - 1), now, { it }, chunk))
        assertEquals(NotificationAction.Snooze(1_000), NotificationPolicy.decide(facts, Until.At(now + 10), now, { it }, chunk))
    }

    @Test
    fun notificationBlockFromStatuses() {
        val engine = Engine()
        val notif = Schedule(id = 1, name = "n", apps = listOf(T), blockNotifications = true)
        val quiet = Schedule(id = 2, name = "q", apps = listOf("other.app"))
        val limited =
            Schedule(id = 3, name = "l", apps = listOf("lim.app"), blockNotifications = true, limits = listOf(launchLimit(1, id = 300)))
        val allow = Schedule(id = 4, name = "a", mode = Mode.ALLOWLIST, apps = listOf("ok.app"), blockNotifications = true)
        val snap =
            ConfigSnapshot(
                schedules = listOf(notif, quiet, limited, allow),
                quickBlock = QuickBlock(active = true, blockNotifications = true, apps = listOf("qb.app"))
            )
        val c = clock("2026-11-23T10:00")
        val st = engine.status(snap, c, emptyMap(), SensorState()).statuses
        assertEquals(Until.Ongoing, engine.notificationBlock(snap, st, T))
        assertNull(engine.notificationBlock(snap, st, "ok.app"))
        assertEquals(Until.Ongoing, engine.notificationBlock(snap, st, "other.app")) // via the allowlist schedule
        assertNull(engine.notificationBlock(snap, st, "com.android.systemui")) // exempt
        assertNull(engine.notificationBlock(snap, st, "com.android.documentsui")) // essential
        assertEquals(Until.UntilStopped, engine.notificationBlock(snap, st, "qb.app"))
        // Limited schedule hides only once exhausted.
        val noAllow = snap.copy(schedules = listOf(limited))
        assertNull(engine.notificationBlock(noAllow, engine.status(noAllow, c, emptyMap()).statuses, "lim.app"))
        val exhausted = engine.status(noAllow, c, mapOf(300L to UsageCounter(300, at("2026-11-23T00:00"), 1))).statuses
        assertEquals(Until.At(at("2026-11-24T00:00")), engine.notificationBlock(noAllow, exhausted, "lim.app"))
        assertTrue(exhausted.first().blockingOrLaunchesExhausted)
    }
}
