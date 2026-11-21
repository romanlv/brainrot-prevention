package dev.brainrotprevention.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.model.AllowlistWeb
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.UsageLimit
import dev.brainrotprevention.testing.Milestone
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §5.4: budgets are refused in ALLOWLIST mode — the editor asks, removes them, and hides the Budget section. */
@RunWith(AndroidJUnit4::class)
@Milestone("M4")
class AllowlistTest {
    @get:Rule val rule = createComposeRule()
    private val env = TestEnv()

    @After fun tearDown() = env.close()

    @Test
    fun switchingToAllowlistRemovesBudget() {
        rule.show(env)
        rule.tap("fab_new_schedule")
        rule.tap("template_social_cap")
        rule.scrollTap("apps_add")
        rule.tap("app_row_com.example.a")
        rule.tap("app_picker_done")
        rule.scrollTap("editor_mode_allowlist")
        rule.waitForTag("allowlist_limits_prompt")
        rule.tap("allowlist_limits_prompt_confirm")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("budget_time_toggle").fetchSemanticsNodes().isEmpty() }
        rule.scrollTap("editor_allowweb_only")
        rule.scrollTap("editor_save")
        // No times or places: "Block these all the time?"
        rule.tap("always_prompt_confirm")
        rule.waitUntil(10_000) { env.schedules().isNotEmpty() }
        val s = env.schedules().single()
        assertEquals(Mode.ALLOWLIST, s.mode)
        assertTrue(s.limits.isEmpty())
        assertEquals(AllowlistWeb.ONLY_LISTED, s.allowlistWeb)
        assertEquals(listOf("com.example.a"), s.apps)
    }

    @Test
    fun cancellingKeepsBlocklistAndBudget() {
        rule.show(env)
        rule.tap("fab_new_schedule")
        rule.tap("template_social_cap")
        rule.scrollTap("apps_add")
        rule.tap("app_row_com.example.a")
        rule.tap("app_picker_done")
        rule.scrollTap("editor_mode_allowlist")
        rule.tap("allowlist_limits_prompt_dismiss")
        rule.waitForTag("budget_time_toggle")
        rule.scrollTap("editor_save")
        rule.waitUntil(10_000) { env.schedules().isNotEmpty() }
        val s = env.schedules().single()
        assertEquals(Mode.BLOCKLIST, s.mode)
        assertEquals(
            listOf(UsageLimit(kind = LimitKind.TIME, period = Period.DAILY, allowance = 30 * 60_000L)),
            s.limits.map {
                it.copy(id = 0)
            }
        )
    }

    @Test
    fun repositoryRefusesAllowlistWithLimits() {
        val s = TestEnv.workday("Mixed").copy(
            mode = Mode.ALLOWLIST,
            limits = listOf(UsageLimit(kind = LimitKind.TIME, period = Period.DAILY, allowance = 60_000)),
        )
        val r = runBlocking { env.graph.schedules.save(s) }
        assertTrue(r.describe(), r is WriteResult.Invalid)
        assertTrue(env.schedules().isEmpty())
    }
}
