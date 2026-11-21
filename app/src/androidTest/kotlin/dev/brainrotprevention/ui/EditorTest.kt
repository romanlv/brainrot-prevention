package dev.brainrotprevention.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.brainrotprevention.model.Combine
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.TimeWindow
import dev.brainrotprevention.model.UsageLimit
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind
import dev.brainrotprevention.model.Weekdays
import dev.brainrotprevention.testing.Milestone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The editor writes exactly the §4.1 rows (values, units, normalisation, 24:00). */
@RunWith(AndroidJUnit4::class)
@Milestone("M4")
class EditorTest {
    @get:Rule val rule = createComposeRule()
    private val env = TestEnv()

    @After fun tearDown() = env.close()

    private fun type(tag: String, text: String) {
        rule.waitForTag(tag)
        rule.onNodeWithTag(tag).performScrollTo().performTextReplacement(text)
        Espresso.closeSoftKeyboard()
    }

    @Test
    fun workdayTemplateWritesSpecRows() {
        rule.show(env)
        rule.tap("fab_new_schedule")
        rule.tap("template_workday")
        type("editor_name", "  Focus ")
        // A URL with a path offers domain vs keyword; the domain is stored as ASCII without www.
        type("web_input", "https://www.Facebook.com/groups/feed")
        rule.scrollTap("web_choose_domain")
        // A keyword, anywhere in the URL, stored lower-cased.
        rule.scrollTap("web_kind_keyword")
        type("web_input", "Casino")
        rule.scrollTap("web_anywhere")
        rule.onNodeWithTag("web_preview").assertTextContains("casino", substring = true)
        rule.scrollTap("web_add")
        // App picker; Instagram triggers "Also block instagram.com?".
        rule.scrollTap("apps_add")
        rule.tap("app_row_com.example.a")
        rule.tap("app_row_com.instagram.android")
        rule.tap("app_picker_done")
        rule.tap("domain_offer_confirm")
        // A 30 min daily budget, then 35 min with one step up.
        rule.scrollTap("budget_time_toggle")
        rule.scrollTap("budget_time_plus")
        rule.scrollTap("editor_block_notifications")
        rule.scrollTap("editor_save")

        rule.waitUntil(10_000) { env.schedules().isNotEmpty() }
        val s = env.schedules().single()
        assertEquals("Focus", s.name)
        assertEquals("💼", s.icon)
        assertEquals(Mode.BLOCKLIST, s.mode)
        assertEquals(Combine.ANY, s.combine)
        assertTrue(s.enabled)
        assertNull(s.pausedUntil)
        assertNull(s.manualUntil)
        assertEquals(T0, s.createdAt)
        assertEquals(listOf(TimeWindow(days = Weekdays.WEEKDAYS, startMin = 540, endMin = 1020)), s.timeWindows.map { it.copy(id = 0) })
        assertEquals(setOf("com.example.a", "com.instagram.android"), s.apps.toSet())
        assertEquals(
            setOf(
                WebRule(kind = WebRuleKind.DOMAIN, value = "facebook.com", anywhereInUrl = false),
                WebRule(kind = WebRuleKind.KEYWORD, value = "casino", anywhereInUrl = true),
                WebRule(kind = WebRuleKind.DOMAIN, value = "instagram.com", anywhereInUrl = false),
            ),
            s.webRules.map { it.copy(id = 0) }.toSet(),
        )
        assertEquals(
            listOf(UsageLimit(kind = LimitKind.TIME, period = Period.DAILY, allowance = 35 * 60_000L)),
            s.limits.map {
                it.copy(id = 0)
            }
        )
        assertTrue(s.blockNotifications)
        assertTrue(s.blockUnsupportedBrowsers)
        assertFalse(s.strictFollowed)
        assertTrue(env.engine.evaluations > 0)
    }

    @Test
    fun windDownEndsAt1440() {
        rule.show(env)
        rule.tap("fab_new_schedule")
        rule.tap("template_wind_down")
        rule.onNodeWithTag("window_0_end").performScrollTo().assertTextContains("24:00", substring = true)
        rule.scrollTap("apps_add")
        rule.tap("app_row_com.example.b")
        rule.tap("app_picker_done")
        rule.scrollTap("editor_save")
        rule.waitUntil(10_000) { env.schedules().isNotEmpty() }
        val w = env.schedules().single().timeWindows.single()
        assertEquals(Weekdays.ALL, w.days)
        assertEquals(1260, w.startMin)
        assertEquals(1440, w.endMin)
    }

    @Test
    fun overnightWindowIsLabelled() {
        env.seed(TestEnv.workday("Night").copy(timeWindows = listOf(TimeWindow(days = Weekdays.ALL, startMin = 1320, endMin = 360))))
        rule.show(env)
        rule.tap("schedule_card_Night")
        rule.waitForTag("window_0_overnight")
        rule.onNodeWithTag("window_0_overnight").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun noTriggerNoBudgetAsksBlockAllTheTime() {
        rule.show(env)
        rule.tap("fab_new_schedule")
        rule.tap("template_scratch")
        type("editor_name", "Always")
        rule.scrollTap("apps_add")
        rule.tap("app_row_com.example.a")
        rule.tap("app_picker_done")
        rule.scrollTap("editor_save")
        rule.tap("always_prompt_confirm")
        rule.waitUntil(10_000) { env.schedules().isNotEmpty() }
        val s = env.schedules().single()
        assertTrue(s.timeWindows.isEmpty() && s.wifi.isEmpty() && s.locations.isEmpty() && s.limits.isEmpty())
    }

    @Test
    fun wifiTriggerStripsQuotesAndCombineAppearsWithTwoTypes() {
        env.seed(TestEnv.workday("Office"))
        rule.show(env)
        rule.tap("schedule_card_Office")
        rule.scrollTap("when_add_wifi")
        rule.tap("wifi_use_current")
        rule.tap("wifi_ok")
        rule.waitForTag("combine_all")
        rule.scrollTap("combine_all")
        rule.scrollTap("editor_save")
        rule.waitUntil(10_000) { env.schedules().single().wifi.isNotEmpty() }
        val s = env.schedules().single()
        assertEquals(listOf("HomeNet"), s.wifi.map { it.ssid })
        assertEquals(Combine.ALL, s.combine)
    }

    @Test
    fun homePause15MinutesWritesPausedUntil() {
        val id = env.seed(TestEnv.workday("Allday"))
        rule.show(env)
        rule.tap("schedule_menu_Allday")
        rule.tap("menu_pause")
        rule.tap("pause_option_15m")
        rule.waitUntil(10_000) { env.schedules().single().pausedUntil != null }
        assertEquals(T0 + 15 * 60_000L, env.schedules().single { it.id == id }.pausedUntil)
        rule.waitForTag("schedule_resume_Allday")
    }

    @Test
    fun startNowUntilStoppedWritesManualUntil() {
        env.seed(TestEnv.workday("Later"))
        rule.show(env)
        rule.tap("schedule_menu_Later")
        rule.tap("menu_start_now")
        rule.tap("start_option_stop")
        rule.waitUntil(10_000) { env.schedules().single().manualUntil != null }
        assertEquals(Long.MAX_VALUE, env.schedules().single().manualUntil)
    }

    @Test
    fun deleteAsksFirstThenDeletes() {
        env.seed(TestEnv.workday("Gone"))
        rule.show(env)
        rule.tap("schedule_menu_Gone")
        rule.tap("menu_delete")
        rule.tap("confirm_delete_confirm")
        rule.waitUntil(10_000) { env.schedules().isEmpty() }
    }

    @Test
    fun duplicateCopiesTheSchedule() {
        env.seed(TestEnv.workday("Twin"))
        rule.show(env)
        rule.tap("schedule_menu_Twin")
        rule.tap("menu_duplicate")
        rule.waitUntil(10_000) { env.schedules().size == 2 }
        val copy = env.schedules().single { it.name == "Twin copy" }
        assertEquals(
            env.schedules().single {
                it.name == "Twin"
            }.timeWindows.map { it.copy(id = 0) },
            copy.timeWindows.map { it.copy(id = 0) }
        )
    }
}
