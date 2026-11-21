package dev.brainrotprevention.ui

import dev.brainrotprevention.engine.ScheduleStatus
import dev.brainrotprevention.engine.Until
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.StrictState
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind
import dev.brainrotprevention.ui.editor.EditorOps
import dev.brainrotprevention.ui.editor.PendingWeb
import dev.brainrotprevention.ui.editor.WebInput
import dev.brainrotprevention.ui.format.Format
import dev.brainrotprevention.ui.home.startNowAlsoArms
import dev.brainrotprevention.ui.onboarding.shownInOnboarding
import dev.brainrotprevention.ui.platform.UiGrant
import dev.brainrotprevention.ui.platform.UiGrantStatus
import dev.brainrotprevention.ui.strict.StrictForm
import dev.brainrotprevention.ui.strict.StrictSummary
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure parts of the round-1 UI review fixes. */
class ReviewFixesTest {
    private val utc = ZoneId.of("UTC")

    /** Mon 2026-11-23 10:00 UTC. */
    private val t0 = 1_795_428_000_000L

    @Test fun typedWebsiteIsCommittedOnSave() {
        val existing = listOf(WebRule(kind = WebRuleKind.DOMAIN, value = "x.com"))
        val r = EditorOps.commitPending(existing, WebInput(text = "Reddit.com"))
        assertTrue(r is PendingWeb.Ready && r.consumedInput)
        assertEquals(listOf("x.com", "reddit.com"), (r as PendingWeb.Ready).rules.map { it.value })
        // Empty field: nothing to do.
        assertEquals(PendingWeb.Ready(existing, consumedInput = false), EditorOps.commitPending(existing, WebInput(text = "  ")))
        // Already in the list: consumed, list unchanged.
        assertEquals(PendingWeb.Ready(existing, consumedInput = true), EditorOps.commitPending(existing, WebInput(text = "x.com")))
        // A path needs the user's choice; invalid input stops too.
        assertTrue(EditorOps.commitPending(existing, WebInput(text = "https://reddit.com/r/all")) is PendingWeb.Unfinished)
        assertTrue(EditorOps.commitPending(existing, WebInput(text = "not a site!")) is PendingWeb.Unfinished)
        // Keyword input keeps the anywhere flag.
        val k = EditorOps.commitPending(emptyList(), WebInput(WebRuleKind.KEYWORD, "Casino", anywhere = true)) as PendingWeb.Ready
        assertEquals(listOf(WebRule(kind = WebRuleKind.KEYWORD, value = "casino", anywhereInUrl = true)), k.rules)
    }

    @Test fun strictSummaryNamesMethodEndLocksAndFollowed() {
        val focus = Schedule(id = 1, name = "Focus", strictFollowed = true)
        val evening = Schedule(id = 2, name = "Evening")
        val allow = Schedule(id = 3, name = "Only work", mode = Mode.ALLOWLIST)
        val all = listOf(focus, evening, allow)
        val timer = StrictSummary.of(
            StrictForm(
                options = StrictOptions.LOCK_EDITING or StrictOptions.BLOCK_SETTINGS or StrictOptions.BLOCK_UNINSTALL,
                timerMs =
                7 * 86_400_000L
            ),
            all,
            t0,
            utc,
        )
        assertEquals("Timer", timer.method)
        assertTrue(timer.ends, timer.ends.startsWith("Ends in 7 d, at "))
        assertEquals(3, timer.locks.size)
        assertTrue(timer.locks.last().contains("Uninstalling"))
        val followAll = StrictSummary.of(StrictForm(method = StrictMethod.SCHEDULES, followAll = true), all, t0, utc)
        assertEquals("Follows all schedules and Quick Block, including ones you add later: Focus, Evening.", followAll.applies)
        val some = StrictSummary.of(StrictForm(method = StrictMethod.SCHEDULES, followAll = false), all, t0, utc)
        assertEquals("Follows: Focus.", some.applies)
        val cooldown = StrictSummary.of(StrictForm(method = StrictMethod.COOLDOWN, cooldownMin = 30, options = 0), all, t0, utc)
        assertTrue(cooldown.ends.contains("30 minutes"))
        assertTrue(cooldown.locks.isEmpty())
    }

    @Test fun onboardingAsksOnlyForWhatBlockingNeedsNow() {
        fun shown(g: UiGrant, granted: Boolean = false, required: Boolean = false) = shownInOnboarding(UiGrantStatus(g, granted, required))
        assertTrue(shown(UiGrant.ACCESSIBILITY, required = true))
        assertTrue(shown(UiGrant.USAGE_ACCESS, required = true))
        assertTrue(shown(UiGrant.NOTIFICATION_LISTENER))
        assertTrue(shown(UiGrant.POST_NOTIFICATIONS))
        assertTrue(shown(UiGrant.EXACT_ALARMS))
        assertFalse(shown(UiGrant.EXACT_ALARMS, granted = true))
        assertFalse(shown(UiGrant.LOCATION_FINE))
        assertFalse(shown(UiGrant.LOCATION_BACKGROUND))
        assertTrue("required once a Wi-Fi/place trigger exists", shown(UiGrant.LOCATION_BACKGROUND, required = true))
        assertFalse(shown(UiGrant.BATTERY_OPTIMIZATION))
        assertFalse(shown(UiGrant.DEVICE_ADMIN))
    }

    @Test fun startNowSheetSaysWhenItAlsoArms() {
        val s = Schedule(id = 1, name = "Focus")
        assertNull(startNowAlsoArms(s, t0))
        assertEquals("Focus is off. Starting it also turns it on.", startNowAlsoArms(s.copy(enabled = false), t0))
        assertEquals("Focus is paused. Starting it also ends the pause.", startNowAlsoArms(s.copy(pausedUntil = t0 + 60_000), t0))
        assertNull(startNowAlsoArms(s.copy(pausedUntil = t0 - 1), t0))
    }

    @Test fun schedulesUnlockConditionNamesTheBlockingSchedules() {
        val row =
            StrictModeRow(
                state = StrictState.ON,
                method = StrictMethod.SCHEDULES,
                followAllSchedules = true,
                options = StrictOptions.LOCK_EDITING
            )
        val focus = Schedule(id = 1, name = "Focus")
        val other = Schedule(id = 2, name = "Other")
        fun status(id: Long, blocking: Boolean, until: Until?) =
            ScheduleStatus(id, false, true, false, blocking, blocking, null, until, until, null, null)
        val statuses = mapOf(
            1L to status(1, true, Until.At(t0 + 7 * 3_600_000L)),
            2L to status(2, false, null),
        )
        val blocking = Format.followedBlocking(row, listOf(focus, other), statuses, t0, utc)
        assertEquals("Focus until 17:00", blocking)
        val text = Format.unlockCondition(row, dev.brainrotprevention.model.StrictDisplayState.ACTIVE, t0, 0, utc, blocking)
        assertEquals("Strict Mode stays active while a followed schedule is blocking: Focus until 17:00.", text)
    }
}
