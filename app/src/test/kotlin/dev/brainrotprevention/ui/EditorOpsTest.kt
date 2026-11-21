package dev.brainrotprevention.ui

import dev.brainrotprevention.engine.ScheduleValidator
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.TimeWindow
import dev.brainrotprevention.model.UsageLimit
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind
import dev.brainrotprevention.model.WifiCondition
import dev.brainrotprevention.ui.editor.EditorOps
import dev.brainrotprevention.ui.editor.RulePreview
import dev.brainrotprevention.ui.editor.Template
import dev.brainrotprevention.ui.home.HomeViewModel
import dev.brainrotprevention.ui.strict.StrictForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorOpsTest {
    @Test fun templatesAreValidAndMatchTheSpec() {
        Template.entries.forEach { t -> assertNull(t.name, ScheduleValidator.validate(t.schedule().copy(apps = listOf("com.example.a")))) }
        assertEquals(TimeWindow(days = 31, startMin = 540, endMin = 1020), Template.WORKDAY.schedule().timeWindows.single())
        assertEquals(TimeWindow(days = 127, startMin = 360, endMin = 540), Template.MORNING.schedule().timeWindows.single())
        assertEquals(TimeWindow(days = 127, startMin = 1260, endMin = 1440), Template.WIND_DOWN.schedule().timeWindows.single())
        assertEquals(TimeWindow(days = 64, startMin = 0, endMin = 1440), Template.SUNDAY.schedule().timeWindows.single())
        assertEquals(
            UsageLimit(kind = LimitKind.TIME, period = Period.DAILY, allowance = 1_800_000),
            Template.SOCIAL_CAP.schedule().limits.single()
        )
    }

    @Test fun endOfMidnightIs1440() {
        assertEquals(1440, EditorOps.endMinute(0, 0))
        assertEquals(1020, EditorOps.endMinute(17, 0))
    }

    @Test fun previewAndChoice() {
        val p = EditorOps.preview(WebRuleKind.DOMAIN, "https://www.Reddit.com", false)
        assertEquals("reddit.com", (p as RulePreview.Adds).rule.value)
        assertEquals("Blocks reddit.com and every subdomain", p.text)
        val c = EditorOps.preview(WebRuleKind.DOMAIN, "youtube.com/shorts", false)
        assertTrue(c is RulePreview.Choose)
        assertEquals("youtube.com", (c as RulePreview.Choose).domain.value)
        assertTrue(c.keyword.anywhereInUrl)
        assertTrue(EditorOps.preview(WebRuleKind.KEYWORD, "a", false) is RulePreview.Invalid)
        assertEquals(RulePreview.Empty, EditorOps.preview(WebRuleKind.DOMAIN, "  ", false))
    }

    @Test fun duplicatesAndBulk() {
        val r = listOf(WebRule(kind = WebRuleKind.DOMAIN, value = "a.com"))
        assertNull(EditorOps.addRule(r, WebRule(kind = WebRuleKind.DOMAIN, value = "a.com")))
        val (next, res) = EditorOps.bulk(r, "a.com\nb.com\nnot valid here\nb.com,comment\n\nc.org")
        assertEquals(listOf("a.com", "b.com", "c.org"), next.map { it.value })
        assertEquals("Added 2 · 2 duplicates · 1 invalid", EditorOps.bulkSummary(res))
    }

    @Test fun allowlistDropsLimitsAndFollow() {
        val s = Schedule(
            name = "x",
            strictFollowed = true,
            limits = listOf(UsageLimit(kind = LimitKind.TIME, period = Period.DAILY, allowance = 60_000)),
        )
        val a = EditorOps.setMode(s, Mode.ALLOWLIST)
        assertTrue(a.limits.isEmpty())
        assertFalse(a.strictFollowed)
    }

    @Test fun limitsAndSteps() {
        var s = Schedule(name = "x")
        s = EditorOps.setLimit(s, LimitKind.LAUNCHES, 10)
        s = EditorOps.setLimit(s, LimitKind.TIME, 30 * 60_000L, Period.HOURLY)
        assertEquals(listOf(LimitKind.TIME, LimitKind.LAUNCHES), s.limits.map { it.kind })
        assertEquals(Period.HOURLY, s.limit(LimitKind.TIME)!!.period)
        s = EditorOps.setLimit(s, LimitKind.LAUNCHES, null)
        assertEquals(1, s.limits.size)
        assertEquals(5 * 60_000L, EditorOps.stepTime(60_000, true))
        assertEquals(60_000L, EditorOps.stepTime(5 * 60_000L, false))
        assertEquals(75 * 60_000L, EditorOps.stepTime(60 * 60_000L, true))
        assertEquals(55 * 60_000L, EditorOps.stepTime(60 * 60_000L, false))
    }

    @Test fun wifiStripsQuotes() {
        assertEquals("Home", EditorOps.addWifi(emptyList(), "\"Home\"")!!.single().ssid)
        assertNull(EditorOps.addWifi(listOf(WifiCondition(ssid = "Home")), "Home"))
        assertNull(EditorOps.addWifi(emptyList(), "  "))
    }

    @Test fun alwaysPrompt() {
        assertTrue(EditorOps.needsAlwaysPrompt(Schedule(name = "x")))
        assertFalse(EditorOps.needsAlwaysPrompt(Template.SOCIAL_CAP.schedule()))
    }

    @Test fun domainSuggestionsSkipExisting() {
        val m = mapOf("com.instagram.android" to listOf("instagram.com", "cdninstagram.com"))
        val rules = listOf(WebRule(kind = WebRuleKind.DOMAIN, value = "instagram.com"))
        assertEquals(listOf("cdninstagram.com"), EditorOps.domainSuggestions("com.instagram.android", m, rules))
    }

    @Test fun duplicateHasFreshIdsAndName() {
        val s = Schedule(
            id = 7,
            name = "Focus",
            pausedUntil = 5,
            manualUntil = 6,
            timeWindows = listOf(TimeWindow(id = 3, days = 1, startMin = 0, endMin = 60)),
        )
        val d = HomeViewModel.duplicateOf(s, listOf(s, s.copy(id = 8, name = "Focus copy", sortOrder = 4)))
        assertEquals(0, d.id)
        assertEquals("Focus copy 2", d.name)
        assertEquals(5, d.sortOrder)
        assertNull(d.pausedUntil)
        assertNull(d.manualUntil)
        assertEquals(0, d.timeWindows.single().id)
    }

    @Test fun strictFormCouplesUninstallToSettings() {
        val f = StrictForm(options = StrictOptions.LOCK_EDITING)
        val u = f.toggle(StrictOptions.BLOCK_UNINSTALL, true)
        assertTrue(StrictOptions.has(u.options, StrictOptions.BLOCK_SETTINGS))
        val off = u.toggle(StrictOptions.BLOCK_SETTINGS, false)
        assertFalse(StrictOptions.has(off.options, StrictOptions.BLOCK_UNINSTALL))
        assertTrue(StrictOptions.isValid(off.options))
        val pin = StrictForm(method = StrictMethod.PIN, pin = "1234", pinConfirm = "1235")
        assertEquals("The two PINs don't match.", pin.pinProblem)
        assertNull(pin.copy(pinConfirm = "1234").pinProblem)
        assertEquals("1234", pin.request().pin)
        assertNull(StrictForm().request().pin)
    }
}
