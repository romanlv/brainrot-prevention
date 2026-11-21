package dev.brainrotprevention.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.brainrotprevention.engine.StrictPolicy
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.StrictState
import dev.brainrotprevention.testing.Milestone
import dev.brainrotprevention.ui.format.Format
import dev.brainrotprevention.ui.nav.Routes
import dev.brainrotprevention.ui.platform.LocalStrictPort
import dev.brainrotprevention.ui.platform.UiGrant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** §9.4: while Strict Mode is ON with LOCK_EDITING, weakening edits are refused with "Locked by Strict Mode". */
@RunWith(AndroidJUnit4::class)
@Milestone("M8")
class StrictUiTest {
    @get:Rule val rule = createComposeRule()
    private val env = TestEnv()

    @After fun tearDown() = env.close()

    @Test
    fun deleteIsRefused() {
        env.seed(TestEnv.workday("Strictly"))
        env.strictOn()
        rule.show(env)
        rule.tap("schedule_menu_Strictly")
        rule.tap("menu_delete")
        rule.waitForTag("locked_by_strict")
        rule.onNodeWithTag("locked_by_strict", useUnmergedTree = true).assertIsDisplayed()
        // The dialog names the unlock condition: TIMER 1 h from T0 → "ends in 1 h (11:00)".
        rule.assertTextInside("locked_by_strict", "Strict Mode ends in 1 h (11:00).")
        assertEquals(listOf("Strictly"), env.schedules().map { it.name })
    }

    @Test
    fun pauseIsRefused() {
        env.seed(TestEnv.workday("Strictly"))
        env.strictOn()
        rule.show(env)
        rule.tap("schedule_menu_Strictly")
        rule.tap("menu_pause")
        rule.waitForTag("locked_by_strict")
        assertNull(env.schedules().single().pausedUntil)
    }

    @Test
    fun removingABlockedAppInTheEditorIsRefused() {
        env.seed(TestEnv.workday("Strictly", apps = listOf("com.example.a", "com.example.b")))
        env.strictOn()
        rule.show(env)
        rule.tap("schedule_card_Strictly")
        rule.waitForTag("editor_strict_banner")
        rule.scrollTap("app_remove_com.example.b")
        rule.scrollTap("editor_save")
        rule.waitForTag("locked_by_strict")
        assertEquals(listOf("com.example.a", "com.example.b"), env.schedules().single().apps.sorted())
    }

    @Test
    fun addingABlockedAppIsAllowed() {
        env.seed(TestEnv.workday("Strictly"))
        env.strictOn()
        rule.show(env)
        rule.tap("schedule_card_Strictly")
        rule.scrollTap("apps_add")
        rule.tap("app_row_com.example.b")
        rule.tap("app_picker_done")
        rule.scrollTap("editor_save")
        rule.waitUntil(10_000) { env.schedules().single().apps.size == 2 }
    }

    private fun missingListener() {
        env.health.health = env.health.health.copy(
            items = env.health.health.items.map { if (it.grant.name == "NOTIFICATION_LISTENER") it.copy(granted = false) else it },
        )
        // Guard decided from the real strict row and displayed state, not a canned result.
        env.useRealSettingsGuard()
    }

    @Test
    fun settingsDeepLinkLockedWhileGuardActive() {
        missingListener()
        env.strictOn(StrictOptions.LOCK_EDITING or StrictOptions.BLOCK_SETTINGS)
        rule.show(env, Routes.SETTINGS)
        rule.scrollTap("health_fix_notification_listener")
        rule.waitForTag("locked_by_strict")
        rule.assertTextInside("locked_by_strict", "Strict Mode ends in 1 h")
        assertTrue("nothing may be opened while the guard is active", env.health.opened.isEmpty())
    }

    @Test
    fun settingsDeepLinkOpensWithoutTheSettingsOption() {
        missingListener()
        env.strictOn(StrictOptions.LOCK_EDITING)
        rule.show(env, Routes.SETTINGS)
        rule.scrollTap("health_fix_notification_listener")
        rule.waitUntil(5_000) { env.health.opened.isNotEmpty() }
        assertEquals(listOf(UiGrant.NOTIFICATION_LISTENER), env.health.opened)
        assertTrue(rule.onAllNodesWithTag("locked_by_strict", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun uninstallOptionCouplesToSettingsAndActivationWritesRow() {
        rule.show(env, Routes.STRICT)
        rule.scrollTap("strict_opt_settings")
        rule.onNodeWithTag("strict_settings_warning").performScrollTo().assertIsDisplayed()
        rule.scrollTap("strict_opt_uninstall")
        rule.onNodeWithTag("strict_opt_settings").assertIsOn()
        rule.scrollTap("strict_opt_settings")
        rule.onNodeWithTag("strict_opt_uninstall").assertIsOff()
        rule.scrollTap("strict_method_cooldown")
        rule.scrollTap("strict_cooldown_10")
        rule.scrollTap("strict_activate")
        // Confirmation first, with the summary; nothing is written before it is confirmed.
        rule.waitForTag("strict_confirm")
        rule.onNodeWithTag("strict_confirm_ends", useUnmergedTree = true).assertTextContains("10 minutes", substring = true)
        assertFalse(runBlocking { env.graph.strict.get() }.isOn)
        rule.tap("strict_confirm_confirm")
        rule.waitUntil(10_000) { runBlocking { env.graph.strict.get() }.state == StrictState.ON }
        val row = runBlocking { env.graph.strict.get() }
        assertEquals(StrictMethod.COOLDOWN, row.method)
        assertEquals(10, row.cooldownMin)
        assertEquals(StrictOptions.LOCK_EDITING, row.options)
        rule.waitForTag("strict_cooldown_start")
        rule.waitForTag("strict_emergency")
    }

    @Test
    fun activationCanBeCancelledAtTheConfirmation() {
        rule.show(env, Routes.STRICT)
        rule.scrollTap("strict_timer_7days")
        rule.scrollTap("strict_activate")
        rule.waitForTag("strict_confirm")
        rule.onNodeWithTag("strict_confirm_ends", useUnmergedTree = true).assertTextContains("Ends in 7 d", substring = true)
        rule.onNodeWithTag("strict_confirm_applies", useUnmergedTree = true).assertTextContains("every schedule", substring = true)
        rule.tap("strict_confirm_dismiss")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("strict_confirm", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
        assertFalse(runBlocking { env.graph.strict.get() }.isOn)
        rule.waitForTag("strict_activate")
    }

    @Test
    fun wrongPledgeDoesNotStartTheCountdown() {
        env.strictOn()
        rule.show(env, Routes.STRICT)
        rule.scrollTap("strict_emergency")
        rule.tap("emergency_yes")
        rule.waitForTag("emergency_pledge")
        rule.onNodeWithTag("emergency_pledge").performScrollTo().performTextReplacement("I want my apps back")
        rule.tap("emergency_pledge_ok")
        rule.onNodeWithText("That doesn't match yet.", useUnmergedTree = true).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag("emergency_countdown", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // Even after the countdown time has passed, a wrong pledge leaves Strict Mode on.
        env.clock.el += 61_000
        rule.waitForIdle()
        assertTrue(runBlocking { env.graph.strict.get() }.isOn)
        assertTrue(runBlocking { env.graph.strict.getEmergencyLog() }.isEmpty())
    }

    /** §9.7: correct pledge → 60 s on-screen countdown (policy elapsed time) → INACTIVE; then not again for 30 days. */
    @Test
    fun emergencyUnblockEndsStrictModeOnceIn30Days() {
        env.strictOn(StrictOptions.LOCK_EDITING or StrictOptions.BLOCK_SETTINGS)
        rule.show(env, Routes.STRICT)
        rule.scrollTap("strict_emergency")
        rule.tap("emergency_yes")
        rule.waitForTag("emergency_pledge")
        rule.onNodeWithTag("emergency_pledge").performScrollTo().performTextReplacement(LocalStrictPort.PLEDGE)
        rule.tap("emergency_pledge_ok")
        rule.waitForTag("emergency_countdown")
        rule.onNodeWithTag("emergency_countdown", useUnmergedTree = true).assertTextContains("1:00")
        // Half-way: still on.
        env.clock.el += 30_000
        rule.waitUntil(5_000) { textOf("emergency_countdown").contains("0:30") }
        assertTrue(runBlocking { env.graph.strict.get() }.isOn)
        // The full minute: Strict Mode ends and the use is logged.
        env.clock.el += 30_001
        rule.waitUntil(10_000) { !runBlocking { env.graph.strict.get() }.isOn }
        assertEquals(listOf(T0), runBlocking { env.graph.strict.getEmergencyLog() }.map { it.at })
        rule.waitForTag("notice_dialog")
        rule.assertTextInside("notice_dialog", "available again in 30 days")
        rule.tap("notice_ok")
        rule.waitForTag("strict_activate")

        // Strict Mode on again a day later: the emergency unblock says when it is available again.
        env.clock.wall += 86_400_000L
        env.strictOn()
        rule.scrollTap("strict_emergency")
        rule.waitForTag("emergency_unavailable")
        val expected = Format.dateLong(T0 + StrictPolicy.EMERGENCY_WINDOW_MS, env.clock.zone())
        rule.onNodeWithTag(
            "emergency_unavailable",
            useUnmergedTree = true
        ).assertTextContains("available again on $expected", substring = true)
        assertTrue(rule.onAllNodesWithTag("emergency_yes", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    private fun textOf(tag: String): String = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
}
