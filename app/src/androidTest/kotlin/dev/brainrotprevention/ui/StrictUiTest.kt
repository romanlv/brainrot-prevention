package dev.brainrotprevention.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.StrictState
import dev.brainrotprevention.testing.Milestone
import dev.brainrotprevention.ui.nav.Routes
import dev.brainrotprevention.ui.platform.FixResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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

    @Test
    fun settingsDeepLinkLockedWhileGuardActive() {
        env.health.health = env.health.health.copy(
            items = env.health.health.items.map { if (it.grant.name == "NOTIFICATION_LISTENER") it.copy(granted = false) else it },
        )
        env.health.openResult = FixResult.LOCKED_BY_STRICT
        env.strictOn(StrictOptions.LOCK_EDITING or StrictOptions.BLOCK_SETTINGS)
        rule.show(env, Routes.SETTINGS)
        rule.scrollTap("health_fix_notification_listener")
        rule.waitForTag("locked_by_strict")
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
        rule.waitUntil(10_000) { runBlocking { env.graph.strict.get() }.state == StrictState.ON }
        val row = runBlocking { env.graph.strict.get() }
        assertEquals(StrictMethod.COOLDOWN, row.method)
        assertEquals(10, row.cooldownMin)
        assertEquals(StrictOptions.LOCK_EDITING, row.options)
        rule.waitForTag("strict_cooldown_start")
        rule.waitForTag("strict_emergency")
    }

    @Test
    fun emergencyFlowNeedsThePledge() {
        env.strictOn()
        rule.show(env, Routes.STRICT)
        rule.scrollTap("strict_emergency")
        rule.tap("emergency_yes")
        rule.waitForTag("emergency_pledge")
        rule.onNodeWithTag("emergency_pledge").performScrollTo()
        rule.tap("emergency_pledge_ok")
        // A wrong pledge keeps the user on the pledge step.
        rule.waitForTag("emergency_pledge")
        assertTrue(runBlocking { env.graph.strict.get() }.isOn)
    }
}
