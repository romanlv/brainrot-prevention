package dev.brainrotprevention.engine

import dev.brainrotprevention.model.Combine
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.ForegroundApp
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.StrictState
import dev.brainrotprevention.model.WebTarget
import dev.brainrotprevention.model.Weekdays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the round-2 reviews (diff + adversarial). */
class Round2RegressionTest {
    private val facts =
        StrictFacts(accessibilityConnected = true, usageAccessGranted = true, inAccessibilityShortcut = false, adminActive = true)
    private val now = at("2026-11-23T10:00")
    private val workday = schedule(1, T) { copy(timeWindows = listOf(win(Weekdays.ALL, "09:00", "17:00"))) }
    private val alwaysOn = schedule(2, T)
    private val qbItems = QuickBlock(apps = listOf(T))
    private val schedulesLock = StrictActivation(StrictMethod.SCHEDULES, StrictOptions.LOCK_EDITING, followAllSchedules = true)
    private val onRow = StrictModeRow(
        state = StrictState.ON,
        method = StrictMethod.SCHEDULES,
        options = StrictOptions.LOCK_EDITING,
        followAllSchedules = true,
    )

    // --- Adversarial 7: SCHEDULES lock-in ---
    @Test
    fun unboundedSchedulesAreRecognised() {
        assertTrue(StrictPolicy.isUnbounded(alwaysOn))
        assertFalse(StrictPolicy.isUnbounded(workday))
        assertTrue(StrictPolicy.isUnbounded(workday.copy(manualUntil = Schedule.UNTIL_STOPPED)))
        assertFalse(StrictPolicy.isUnbounded(workday.copy(manualUntil = now + 30 * MIN)))
        // Whole-week windows (two halves, also overnight) are always on.
        val week = workday.copy(timeWindows = listOf(win(Weekdays.ALL, "00:00", "12:00"), win(Weekdays.ALL, "12:00", "24:00")))
        assertTrue(StrictPolicy.isUnbounded(week))
        assertTrue(StrictPolicy.isUnbounded(workday.copy(timeWindows = listOf(win(Weekdays.ALL, "06:00", "06:00")))))
        // ALL with a Wi-Fi condition ends when the Wi-Fi does; ANY with whole-week windows doesn't.
        assertFalse(StrictPolicy.isUnbounded(week.copy(combine = Combine.ALL, wifi = wifi("Home"))))
        assertTrue(StrictPolicy.isUnbounded(week.copy(wifi = wifi("Home"))))
        // Wi-Fi only: the user can leave.
        assertFalse(StrictPolicy.isUnbounded(alwaysOn.copy(wifi = wifi("Home"))))
        // A budget resets at the next period; an off schedule doesn't block; an allowlist isn't followed.
        assertFalse(StrictPolicy.isUnbounded(alwaysOn.copy(limits = listOf(timeLimit(30)))))
        assertFalse(StrictPolicy.isUnbounded(alwaysOn.copy(enabled = false)))
        assertFalse(StrictPolicy.isUnbounded(alwaysOn.copy(mode = Mode.ALLOWLIST)))
        // Quick Block without a timer.
        assertTrue(StrictPolicy.isUnbounded(qbItems.copy(active = true, until = null)))
        assertFalse(StrictPolicy.isUnbounded(qbItems.copy(active = true, until = now + MIN)))
        assertFalse(StrictPolicy.isUnbounded(qbItems))
    }

    @Test
    fun activationIsRefusedWhileAFollowedItemIsUnbounded() {
        val row = StrictModeRow()
        assertEquals(
            ActivationRefusal.UNBOUNDED_FOLLOWED,
            StrictPolicy.checkActivation(schedulesLock, facts, listOf(workday, alwaysOn), row),
        )
        assertEquals(
            ActivationRefusal.UNBOUNDED_FOLLOWED,
            StrictPolicy.checkActivation(
                schedulesLock,
                facts,
                listOf(workday.copy(manualUntil = Schedule.UNTIL_STOPPED)),
                row,
            ),
        )
        assertEquals(
            ActivationRefusal.UNBOUNDED_FOLLOWED,
            StrictPolicy.checkActivation(schedulesLock, facts, listOf(workday), row, qbItems.copy(active = true, until = null)),
        )
        val r = StrictPolicy.activate(schedulesLock, facts, listOf(workday), row, now, 0, quickBlock = qbItems.copy(active = true))
        assertEquals(ActivationResult.Refused(ActivationRefusal.UNBOUNDED_FOLLOWED), r)
        // Bounded items activate.
        assertNull(StrictPolicy.checkActivation(schedulesLock, facts, listOf(workday), row, qbItems.copy(active = true, until = now + MIN)))
        // Not followed (followAll off, not strictFollowed): ignored.
        val some = schedulesLock.copy(followAllSchedules = false)
        assertNull(StrictPolicy.checkActivation(some, facts, listOf(workday.copy(strictFollowed = true), alwaysOn), row))
        assertEquals(
            ActivationRefusal.UNBOUNDED_FOLLOWED,
            StrictPolicy.checkActivation(some, facts, listOf(workday, alwaysOn.copy(strictFollowed = true)), row),
        )
        // Without LOCK_EDITING the user can still stop the item, so there's no lock-in; other methods always end.
        assertNull(StrictPolicy.checkActivation(schedulesLock.copy(options = 0), facts, listOf(alwaysOn), row))
        assertNull(
            StrictPolicy.checkActivation(
                StrictActivation(StrictMethod.TIMER, StrictOptions.LOCK_EDITING, durationMs = 60 * MIN),
                facts,
                listOf(alwaysOn),
                row,
            ),
        )
    }

    @Test
    fun whileOnStartingUnboundedFollowedItemsIsRefused() {
        val schedules = listOf(workday)
        val refused = EditCheck.Refused(Refusal.UNBOUNDED_UNDER_STRICT)
        for (display in listOf(StrictDisplayState.STANDBY, StrictDisplayState.ACTIVE)) {
            // Start now "until I stop it".
            assertEquals(
                refused,
                EditGuard.checkSchedule(onRow, display, schedules, workday, workday.copy(manualUntil = Schedule.UNTIL_STOPPED), now),
            )
            // A bounded Start now is fine.
            assertTrue(EditGuard.checkSchedule(onRow, display, schedules, workday, workday.copy(manualUntil = now + MIN), now).allowed)
            // Creating, or turning on, an always-on schedule.
            assertEquals(refused, EditGuard.checkSchedule(onRow, display, schedules, null, alwaysOn.copy(id = 0), now))
            val off = alwaysOn.copy(enabled = false)
            assertEquals(refused, EditGuard.checkSchedule(onRow, display, schedules + off, off, alwaysOn, now))
            // Removing every window turns a workday schedule into "Always on".
            assertEquals(refused, EditGuard.checkSchedule(onRow, display, schedules, workday, workday.copy(timeWindows = emptyList()), now))
            // Quick Block without a timer.
            assertEquals(
                refused,
                EditGuard.checkQuickBlock(onRow, display, schedules, qbItems, qbItems.copy(active = true, until = null), now),
            )
            assertTrue(
                EditGuard.checkQuickBlock(onRow, display, schedules, qbItems, qbItems.copy(active = true, until = now + MIN), now).allowed
            )
        }
        // Not followed: allowed (Quick Block isn't followed without followAll; the new schedule isn't strictFollowed).
        val some = onRow.copy(followAllSchedules = false)
        assertTrue(
            EditGuard.checkQuickBlock(some, StrictDisplayState.STANDBY, schedules, qbItems, qbItems.copy(active = true), now).allowed
        )
        assertTrue(EditGuard.checkSchedule(some, StrictDisplayState.STANDBY, schedules, null, alwaysOn.copy(id = 0), now).allowed)
        // Other methods and Strict off: allowed.
        val timer = onRow.copy(method = StrictMethod.TIMER, endsAt = now + 60 * MIN)
        assertTrue(
            EditGuard.checkQuickBlock(timer, StrictDisplayState.ACTIVE, schedules, qbItems, qbItems.copy(active = true), now).allowed
        )
        assertTrue(
            EditGuard.checkQuickBlock(
                StrictModeRow(),
                StrictDisplayState.INACTIVE,
                schedules,
                qbItems,
                qbItems.copy(active = true),
                now
            ).allowed
        )
    }

    // --- Adversarial 10: timer extension is capped at 30 days from now ---
    @Test
    fun timerExtensionIsCappedAtThirtyDays() {
        val row = StrictModeRow(state = StrictState.ON, method = StrictMethod.TIMER, endsAt = now + 29 * Periods.DAY, endsAtElapsed = 1)
        assertTrue(StrictPolicy.extensionWithinMax(row, Periods.DAY, now))
        assertFalse(StrictPolicy.extensionWithinMax(row, Periods.DAY + 1, now))
        assertFalse(StrictPolicy.extensionWithinMax(row.copy(endsAt = null), MIN, now))
    }

    // --- Adversarial 4: translate.goog and AMP proxies map back to the original host ---
    @Test
    fun proxyUrlsMatchTheOriginalHost() {
        val reddit = domain("reddit.com")
        val blocked = listOf(
            "https://old-reddit-com.translate.goog/r/all?_x_tr_sl=auto&_x_tr_tl=de",
            "old-reddit-com.translate.goog/r/all",
            "www-reddit-com.cdn.ampproject.org/c/s/www.reddit.com/r/all",
            "https://cdn.ampproject.org/c/s/www.reddit.com/r/all.amp",
            "https://cdn.ampproject.org/v/s/reddit.com/x",
            "https://www.google.com/amp/s/www.reddit.com/r/all.amp",
            "https://www.google.de/amp/reddit.com/x",
            "google.co.uk/amp/s/old.reddit.com/",
        )
        for (u in blocked) assertTrue(u, UrlMatcher.matches(reddit, UrlNormalizer.normalizeDetected(u)))
        // `--` encodes a hyphen in the original host.
        val n = UrlNormalizer.normalizeDetected("https://my--site-example-org.translate.goog/")
        assertEquals(listOf("my-site.example.org"), n.proxiedHosts)
        assertTrue(UrlMatcher.matches(domain("example.org"), n))
        assertTrue(UrlMatcher.matches(keyword("my-site"), n))
        // Not proxies: plain hosts, other google paths, a lookalike suffix.
        val allowed = listOf(
            "https://translate.google.com/?sl=auto&text=old.reddit.com",
            "https://www.google.com/search?q=reddit.com",
            "https://www.google.com/amp",
            "https://old-reddit-com.translate.goog.evil.com/",
            "https://example.org/amp/s/www.reddit.com/",
        )
        for (u in allowed) assertFalse(u, UrlMatcher.matches(reddit, UrlNormalizer.normalizeDetected(u)))
        assertTrue(UrlNormalizer.normalizeDetected("https://example.org/x").proxiedHosts.isEmpty())
    }

    // --- Adversarial 8: system screens stay usable in "Allow only selected" schedules ---
    @Test
    fun allowlistKeepsShareSheetPickerCredentialsAndCaptivePortal() {
        val s = schedule(1, "com.google.android.apps.messaging") { copy(mode = Mode.ALLOWLIST) }
        val snap = ConfigSnapshot(schedules = listOf(s))
        val gms = "com.google.android.gms"
        val fg = listOf(
            ForegroundApp("com.android.intentresolver", "com.android.intentresolver/.ChooserActivity"),
            ForegroundApp("com.google.android.providers.media.module", null),
            ForegroundApp("com.google.android.photopicker", null),
            ForegroundApp("com.android.credentialmanager", "com.android.credentialmanager/.CredentialSelectorActivity"),
            ForegroundApp("com.google.android.captiveportallogin", null),
            ForegroundApp(T, null),
        )
        val r = Engine().evaluate(
            EvaluationInput(snapshot = snap, clock = clock("2026-11-23T10:00"), state = EngineState(), foreground = fg)
        )
        for (a in fg.dropLast(1)) assertEquals(a.packageName, Decision.Allow, r.apps[a.packageName])
        assertTrue(r.apps.getValue(T).isBlock)
        // Play Services: only the account picker / sign-in / passkey screens, by component (short or full class).
        fun gmsDecision(component: String): Decision {
            val e = Engine().evaluate(
                EvaluationInput(
                    snapshot = snap,
                    clock = clock("2026-11-23T10:00"),
                    state = EngineState(),
                    foreground = listOf(ForegroundApp(gms, component)),
                ),
            )
            return e.apps.getValue(gms)
        }
        assertEquals(Decision.Allow, gmsDecision("$gms/.common.account.AccountPickerActivity"))
        assertEquals(Decision.Allow, gmsDecision("$gms/$gms.fido.fido2.ui.Fido2FullScreenActivity"))
        assertEquals(Decision.Allow, gmsDecision("$gms/.auth.api.signin.internal.SignInHubActivity"))
        assertTrue(gmsDecision("$gms/.app.settings.GoogleSettingsLink").isBlock)
    }

    // --- Adversarial 3: every window of a browser is judged (split screen / second window) ---
    @Test
    fun everyBrowserWindowIsJudged() {
        val s = schedule(1) { copy(webRules = listOf(domain("reddit.com"))) }
        val snap = ConfigSnapshot(schedules = listOf(s))
        val r = Engine().evaluate(
            EvaluationInput(
                snapshot = snap,
                clock = clock("2026-11-23T10:00"),
                state = EngineState(),
                foreground = listOf(ForegroundApp(CHROME, null)),
                webTargets = listOf(WebTarget(CHROME, "google.com", true, 11), WebTarget(CHROME, "reddit.com/r/all", true, 12)),
            ),
        )
        assertEquals(2, r.pages.size)
        assertEquals(Decision.Allow, r.pages.single { it.target.windowId == 11 }.decision)
        assertTrue(r.pages.single { it.target.windowId == 12 }.decision.isBlock)
    }

    // --- Adversarial 1: bulk add stops at the per-schedule cap without parsing the rest ---
    @Test
    fun bulkAddStopsAtTheCap() {
        val max = ScheduleValidator.MAX_WEB_RULES
        val text = (1..50_000).joinToString("\n") { "0.0.0.0 host$it.example" }.replace("0.0.0.0 ", "")
        val t0 = System.nanoTime()
        val r = UrlNormalizer.bulkAdd(text, listOf(domain("a.example")))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(max - 1, r.added.size)
        assertEquals(50_000 - (max - 1), r.overLimit)
        assertTrue("took $ms ms", ms < 5_000)
        assertNull(ScheduleValidator.validateRules(r.added, "r"))
        val tooMany = ScheduleValidator.validateRules(r.added + domain("a.example") + domain("b.example"), "r")
        assertEquals("r", tooMany?.path)
    }
}
