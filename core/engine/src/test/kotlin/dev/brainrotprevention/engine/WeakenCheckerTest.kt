package dev.brainrotprevention.engine

import dev.brainrotprevention.model.AllowlistWeb
import dev.brainrotprevention.model.Combine
import dev.brainrotprevention.model.LimitKind
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
import dev.brainrotprevention.model.StrictState
import dev.brainrotprevention.model.UsageLimit
import dev.brainrotprevention.model.Weekdays.ALL
import dev.brainrotprevention.model.Weekdays.FRI
import dev.brainrotprevention.model.Weekdays.MON
import dev.brainrotprevention.model.Weekdays.SAT
import dev.brainrotprevention.model.Weekdays.WEEKDAYS
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeakenCheckerTest {
    private val now = at("2026-11-23T10:00")
    private val loc = LocationCondition(id = 3, label = "Home", lat = 1.0, lon = 2.0, radiusM = 200)
    private val base = Schedule(
        id = 1,
        name = "Focus",
        timeWindows = listOf(win(WEEKDAYS, "09:00", "17:00")),
        limits = listOf(timeLimit(30)),
        apps = listOf("a.one", "b.two"),
        webRules = listOf(domain("x.com"), keyword("game")),
        blockNotifications = true,
    )
    private val allow =
        Schedule(
            id = 2,
            name = "Allow",
            mode = Mode.ALLOWLIST,
            allowlistWeb = AllowlistWeb.ONLY_LISTED,
            apps = listOf("a.one"),
            webRules = listOf(domain("wiki.org"))
        )

    private data class Row(val name: String, val old: Schedule, val new: Schedule?, val expected: Refusal?)

    @Test
    fun scheduleTable() {
        val rows = listOf(
            // Time coverage
            Row("widen window", base, base.copy(timeWindows = listOf(win(WEEKDAYS, "08:00", "18:00"))), null),
            Row("add days", base, base.copy(timeWindows = listOf(win(ALL, "09:00", "17:00"))), null),
            Row("narrow window", base, base.copy(timeWindows = listOf(win(WEEKDAYS, "10:00", "17:00"))), Refusal.TIME_COVERAGE_REDUCED),
            Row(
                "drop a day",
                base,
                base.copy(timeWindows = listOf(win(WEEKDAYS and FRI.inv(), "09:00", "17:00"))),
                Refusal.TIME_COVERAGE_REDUCED
            ),
            Row(
                "split into touching windows",
                base,
                base.copy(timeWindows = listOf(win(WEEKDAYS, "09:00", "12:00"), win(WEEKDAYS, "12:00", "17:00"))),
                null
            ),
            Row("remove all windows (always on)", base, base.copy(timeWindows = emptyList()), null),
            Row(
                "overnight spills to next day",
                base.copy(timeWindows = listOf(win(FRI, "22:00", "06:00"))),
                base.copy(timeWindows = listOf(win(FRI, "22:00", "24:00"), win(SAT, "00:00", "06:00"))),
                null,
            ),
            Row(
                "overnight spill removed",
                base.copy(timeWindows = listOf(win(FRI, "22:00", "06:00"))),
                base.copy(timeWindows = listOf(win(FRI, "22:00", "24:00"))),
                Refusal.TIME_COVERAGE_REDUCED,
            ),
            Row("add first window to always-on", base.copy(timeWindows = emptyList()), base, Refusal.TIME_COVERAGE_REDUCED),
            Row(
                "add window to ANY wifi-only (covers nothing before)",
                base.copy(timeWindows = emptyList(), wifi = wifi("Home")),
                base.copy(wifi = wifi("Home")),
                null,
            ),
            // Trigger structure
            Row("ALL → ANY", base.copy(combine = Combine.ALL, wifi = wifi("H")), base.copy(combine = Combine.ANY, wifi = wifi("H")), null),
            Row("ANY → ALL", base.copy(wifi = wifi("H")), base.copy(combine = Combine.ALL, wifi = wifi("H")), Refusal.COMBINE_TIGHTENED),
            Row("add wifi when ANY", base, base.copy(wifi = wifi("H")), null),
            Row("add location when ANY", base, base.copy(locations = listOf(loc)), null),
            Row(
                "add wifi when ALL",
                base.copy(combine = Combine.ALL),
                base.copy(combine = Combine.ALL, wifi = wifi("H")),
                Refusal.TRIGGER_ADDED
            ),
            Row(
                "add first trigger to always-on",
                base.copy(timeWindows = emptyList()),
                base.copy(timeWindows = emptyList(), wifi = wifi("H")),
                Refusal.TRIGGER_ADDED
            ),
            Row(
                "add second ssid under ALL",
                base.copy(combine = Combine.ALL, wifi = wifi("H")),
                base.copy(combine = Combine.ALL, wifi = wifi("H", "W")),
                null
            ),
            Row("remove wifi", base.copy(wifi = wifi("H", "W")), base.copy(wifi = wifi("H")), Refusal.TRIGGER_REMOVED_OR_EDITED),
            Row(
                "edit location",
                base.copy(locations = listOf(loc)),
                base.copy(locations = listOf(loc.copy(radiusM = 500))),
                Refusal.TRIGGER_REMOVED_OR_EDITED
            ),
            Row(
                "location state is not an edit",
                base.copy(locations = listOf(loc)),
                base.copy(locations = listOf(loc.copy(lastState = dev.brainrotprevention.model.LocationState.INSIDE))),
                null
            ),
            // Limits
            Row("lower allowance", base, base.copy(limits = listOf(timeLimit(20))), null),
            Row("raise allowance", base, base.copy(limits = listOf(timeLimit(40))), Refusal.LIMIT_RAISED),
            Row("HOURLY → DAILY", base.copy(limits = listOf(timeLimit(30, period = Period.HOURLY))), base, null),
            Row("DAILY → HOURLY", base, base.copy(limits = listOf(timeLimit(30, period = Period.HOURLY))), Refusal.LIMIT_PERIOD_SHORTENED),
            Row("delete last limit", base, base.copy(limits = emptyList()), null),
            Row("add limit", base.copy(limits = emptyList()), base, Refusal.LIMIT_ADDED),
            Row("add second limit", base, base.copy(limits = listOf(timeLimit(30), launchLimit(5))), Refusal.LIMIT_ADDED),
            Row("delete one of two", base.copy(limits = listOf(timeLimit(30), launchLimit(5))), base, Refusal.LIMIT_REMOVED),
            Row("change kind", base, base.copy(limits = listOf(UsageLimit(100, LimitKind.LAUNCHES, Period.DAILY, 5))), Refusal.LIMIT_ADDED),
            // Mode
            Row("BLOCKLIST → ALLOWLIST", base, base.copy(mode = Mode.ALLOWLIST), Refusal.MODE_CHANGED),
            Row("OPEN → ONLY_LISTED", allow.copy(allowlistWeb = AllowlistWeb.OPEN), allow, null),
            Row("ONLY_LISTED → OPEN", allow, allow.copy(allowlistWeb = AllowlistWeb.OPEN), Refusal.ALLOWLIST_WEB_OPENED),
            // Lists
            Row("add to blocklist", base, base.copy(apps = base.apps + "c.three", webRules = base.webRules + domain("y.com")), null),
            Row("remove app from blocklist", base, base.copy(apps = listOf("a.one")), Refusal.BLOCKLIST_ITEM_REMOVED),
            Row("remove web rule from blocklist", base, base.copy(webRules = listOf(domain("x.com"))), Refusal.BLOCKLIST_ITEM_REMOVED),
            Row("keyword widened to anywhere", base, base.copy(webRules = listOf(domain("x.com"), keyword("game", true))), null),
            Row(
                "keyword narrowed from anywhere",
                base.copy(webRules = listOf(keyword("game", true))),
                base.copy(webRules = listOf(keyword("game"))),
                Refusal.BLOCKLIST_ITEM_REMOVED
            ),
            Row("remove from allowlist", allow, allow.copy(apps = emptyList(), webRules = emptyList()), null),
            Row("add app to allowlist", allow, allow.copy(apps = allow.apps + "b.two"), Refusal.ALLOWLIST_ITEM_ADDED),
            Row("add site to ONLY_LISTED", allow, allow.copy(webRules = allow.webRules + domain("yt.com")), Refusal.ALLOWLIST_ITEM_ADDED),
            Row(
                "sites free under OPEN",
                allow.copy(allowlistWeb = AllowlistWeb.OPEN),
                allow.copy(allowlistWeb = AllowlistWeb.OPEN, webRules = listOf(domain("q.com"))),
                null
            ),
            // Flags
            Row("notifications off", base, base.copy(blockNotifications = false), Refusal.FLAG_TURNED_OFF),
            Row("unsupported browsers off", base, base.copy(blockUnsupportedBrowsers = false), Refusal.FLAG_TURNED_OFF),
            Row("unsupported browsers on", base.copy(blockUnsupportedBrowsers = false), base, null),
            // Activation
            Row("disable", base, base.copy(enabled = false), Refusal.DISABLED),
            Row("enable", base.copy(enabled = false), base, null),
            Row("pause", base, base.copy(pausedUntil = now + 15 * MIN), Refusal.PAUSED),
            Row("resume now", base.copy(pausedUntil = now + 15 * MIN), base, null),
            Row("delete", base, null, Refusal.DELETED),
            Row("set manualUntil", base, base.copy(manualUntil = now + MIN), null),
            Row("extend manualUntil", base.copy(manualUntil = now + MIN), base.copy(manualUntil = now + 2 * MIN), null),
            Row(
                "shorten manualUntil",
                base.copy(manualUntil = now + 2 * MIN),
                base.copy(manualUntil = now + MIN),
                Refusal.MANUAL_SHORTENED
            ),
            Row("clear manualUntil", base.copy(manualUntil = now + 2 * MIN), base, Refusal.MANUAL_SHORTENED),
            Row("clear expired manualUntil", base.copy(manualUntil = now - 1), base, null),
            Row("unmark strictFollowed", base.copy(strictFollowed = true), base, Refusal.UNFOLLOWED),
            Row("mark strictFollowed", base, base.copy(strictFollowed = true), null),
            Row("rename", base, base.copy(name = "Deep work", icon = "🧠", sortOrder = 9), null),
        )
        for (r in rows) {
            val got = WeakenChecker.checkSchedule(r.old, r.new, now)
            val expected = r.expected?.let { EditCheck.Refused(it) } ?: EditCheck.Allowed
            assertEquals(r.name, expected, got)
        }
        assertEquals(EditCheck.Allowed, WeakenChecker.checkSchedule(null, base, now)) // creating
    }

    @Test
    fun noWindowCoverageRule() {
        val always = Schedule(name = "a")
        assertEquals(WeakenChecker.CELLS, WeakenChecker.coverage(always).cardinality())
        assertEquals(0, WeakenChecker.coverage(always.copy(wifi = wifi("H"))).cardinality())
        assertEquals(WeakenChecker.CELLS, WeakenChecker.coverage(always.copy(wifi = wifi("H"), combine = Combine.ALL)).cardinality())
        assertEquals(60, WeakenChecker.coverage(always.copy(timeWindows = listOf(win(MON, "23:30", "00:30")))).cardinality())
    }

    @Test
    fun otherAreas() {
        val qb =
            QuickBlock(
                active = true,
                until = now + 30 * MIN,
                apps = listOf("a.one"),
                webRules = listOf(domain("x.com")),
                blockNotifications = true
            )
        assertTrue(WeakenChecker.checkQuickBlock(qb, qb.copy(until = now + 60 * MIN), now).allowed)
        assertTrue(WeakenChecker.checkQuickBlock(qb, qb.copy(until = null), now).allowed)
        assertEquals(EditCheck.Refused(Refusal.QUICK_BLOCK_SHORTENED), WeakenChecker.checkQuickBlock(qb, qb.copy(until = now + MIN), now))
        assertEquals(EditCheck.Refused(Refusal.QUICK_BLOCK_STOPPED), WeakenChecker.checkQuickBlock(qb, qb.copy(active = false), now))
        assertEquals(EditCheck.Refused(Refusal.BLOCKLIST_ITEM_REMOVED), WeakenChecker.checkQuickBlock(qb, qb.copy(apps = emptyList()), now))
        assertEquals(
            EditCheck.Refused(Refusal.FLAG_TURNED_OFF),
            WeakenChecker.checkQuickBlock(qb, qb.copy(blockNotifications = false), now)
        )
        assertTrue(WeakenChecker.checkQuickBlock(QuickBlock(), qb, now).allowed) // starting
        assertTrue(WeakenChecker.checkExcludedBrowsers(setOf("a.b"), emptySet()).allowed)
        assertFalse(WeakenChecker.checkExcludedBrowsers(emptySet(), setOf("a.b")).allowed)
        assertTrue(WeakenChecker.checkPrefs(Prefs(), Prefs(limitWarningMin = 9, notifyBeforeStartMin = 5)).allowed)
        assertEquals(EditCheck.Refused(Refusal.SETTING_LOCKED), WeakenChecker.checkPrefs(Prefs(), Prefs(pendingDayStartHour = 4)))
    }

    @Test
    fun strictRowEdits() {
        val timer = StrictModeRow(state = StrictState.ON, options = 7, method = StrictMethod.TIMER, endsAt = 1000, endsAtElapsed = 500)
        assertTrue(WeakenChecker.checkStrict(timer, StrictPolicy.extendTimer(timer, 60_000)).allowed)
        assertFalse(WeakenChecker.checkStrict(timer, timer.copy(endsAt = 900)).allowed)
        assertFalse(WeakenChecker.checkStrict(timer, timer.copy(endsAtElapsed = 400)).allowed)
        assertFalse(WeakenChecker.checkStrict(timer, timer.copy(options = 1)).allowed)
        assertFalse(WeakenChecker.checkStrict(timer, timer.copy(state = StrictState.INACTIVE)).allowed)
        val sched = StrictModeRow(state = StrictState.ON, options = 1, method = StrictMethod.SCHEDULES)
        assertTrue(WeakenChecker.checkStrict(sched, sched.copy(followAllSchedules = true)).allowed)
        assertEquals(EditCheck.Refused(Refusal.UNFOLLOWED), WeakenChecker.checkStrict(sched.copy(followAllSchedules = true), sched))
        assertFalse(WeakenChecker.checkStrict(sched, sched.copy(endsAt = 5)).allowed)
        assertTrue(EditGuard.checkStrict(StrictModeRow(), StrictModeRow(options = 3)).allowed)
        assertFalse(EditGuard.checkStrict(timer, timer.copy(options = 1)).allowed)
    }

    @Test
    fun editGuardScopes() {
        val followed = base.copy(strictFollowed = true)
        val other = base.copy(id = 9)
        val schedules = listOf(followed, other)
        val on = StrictModeRow(state = StrictState.ON, options = StrictOptions.LOCK_EDITING, method = StrictMethod.SCHEDULES)
        // ACTIVE: every schedule is locked.
        assertEquals(LockScope.ALL, EditGuard.scope(on, StrictDisplayState.ACTIVE))
        assertFalse(EditGuard.checkPause(on, StrictDisplayState.ACTIVE, schedules, other, now + MIN, now).allowed)
        assertFalse(EditGuard.checkTurnOff(on, StrictDisplayState.ACTIVE, schedules, other, now).allowed)
        assertFalse(EditGuard.checkDelete(on, StrictDisplayState.ACTIVE, schedules, other, now).allowed)
        assertFalse(EditGuard.checkExcludedBrowsers(on, StrictDisplayState.ACTIVE, emptySet(), setOf("a.b")).allowed)
        assertFalse(EditGuard.checkPrefs(on, StrictDisplayState.ACTIVE, Prefs(), Prefs(dayStartHour = 3)).allowed)
        assertFalse(
            EditGuard.checkQuickBlock(
                on,
                StrictDisplayState.ACTIVE,
                schedules,
                QuickBlock(active = true, apps = listOf("a.b")),
                QuickBlock(),
                now
            ).allowed
        )
        // STANDBY: only followed schedules.
        assertEquals(LockScope.FOLLOWED, EditGuard.scope(on, StrictDisplayState.STANDBY))
        assertFalse(EditGuard.checkPause(on, StrictDisplayState.STANDBY, schedules, followed, now + MIN, now).allowed)
        assertTrue(EditGuard.checkPause(on, StrictDisplayState.STANDBY, schedules, other, now + MIN, now).allowed)
        assertTrue(EditGuard.checkExcludedBrowsers(on, StrictDisplayState.STANDBY, emptySet(), setOf("a.b")).allowed)
        assertTrue(EditGuard.checkPrefs(on, StrictDisplayState.STANDBY, Prefs(), Prefs(dayStartHour = 3)).allowed)
        assertTrue(
            EditGuard.checkQuickBlock(
                on,
                StrictDisplayState.STANDBY,
                schedules,
                QuickBlock(active = true, apps = listOf("a.b")),
                QuickBlock(),
                now
            ).allowed
        )
        assertTrue(EditGuard.checkSchedule(on, StrictDisplayState.STANDBY, schedules, null, base, now).allowed)
        // Without LOCK_EDITING, or INACTIVE: nothing is locked.
        assertEquals(LockScope.NONE, EditGuard.scope(on.copy(options = StrictOptions.BLOCK_SETTINGS), StrictDisplayState.ACTIVE))
        assertEquals(LockScope.NONE, EditGuard.scope(StrictModeRow(), StrictDisplayState.INACTIVE))
        assertEquals(LockScope.NONE, EditGuard.scope(on, StrictDisplayState.INACTIVE))
        // Import is refused whenever ON; block-screen exclusion hidden while ON.
        assertEquals(EditCheck.Refused(Refusal.IMPORT_LOCKED), EditGuard.checkImport(on))
        assertTrue(EditGuard.checkImport(StrictModeRow()).allowed)
        assertFalse(EditGuard.canExcludeBrowserFromBlockScreen(on))
    }

    /** Property: adding a random window to any schedule never weakens it; removing all-but-one always is checked by coverage. */
    @Test
    fun propertyAddingWindowsNeverWeakens() {
        val rnd = Random(11)
        repeat(300) {
            fun rw(): dev.brainrotprevention.model.TimeWindow {
                val s = rnd.nextInt(0, 1440)
                var e = rnd.nextInt(1, 1441)
                if (e == s) e = if (s == 0) 1 else s - 1
                return dev.brainrotprevention.model.TimeWindow(0, rnd.nextInt(1, 128), s, e)
            }
            val old = base.copy(timeWindows = List(rnd.nextInt(1, 4)) { rw() })
            val new = old.copy(timeWindows = old.timeWindows + rw())
            assertEquals(EditCheck.Allowed, WeakenChecker.checkSchedule(old, new, now))
            assertTrue(WeakenChecker.covers(WeakenChecker.coverage(new), WeakenChecker.coverage(old)))
        }
    }
}
