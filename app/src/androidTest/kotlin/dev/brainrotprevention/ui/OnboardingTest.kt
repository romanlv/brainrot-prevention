package dev.brainrotprevention.ui

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.brainrotprevention.platform.platform
import dev.brainrotprevention.testing.Milestone
import dev.brainrotprevention.testing.NoGrant
import dev.brainrotprevention.ui.nav.Routes
import dev.brainrotprevention.ui.platform.PlatformPorts
import dev.brainrotprevention.ui.platform.UiGrant
import dev.brainrotprevention.ui.platform.UiGrantStatus
import dev.brainrotprevention.ui.platform.UiHealth
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real grant detection (the platform's PermissionHealth) on the onboarding checklist; run after grant.sh. */
@RunWith(AndroidJUnit4::class)
@Milestone("M9")
class OnboardingGrantedTest {
    @get:Rule val rule = createComposeRule()
    private val env = TestEnv()

    @After fun tearDown() = env.close()

    @Test
    fun checklistShowsGrantedRowsGreen() {
        val real = PlatformPorts.of(env.context.platform)
        val deps =
            UiDeps(
                env.graph,
                dev.brainrotprevention.ui.platform.UiPlatform(env.engine, real.health, env.apps, env.strictPort),
                tickMs = 60_000
            )
        rule.setContent { dev.brainrotprevention.ui.nav.BrpUi(deps, startRoute = Routes.ONBOARDING) }
        rule.tap("onboarding_skip_intro")
        rule.waitForTag("onboarding_permissions")
        rule.waitForTag("onboarding_accessibility_ok", 20_000)
        rule.waitForTag("onboarding_usage_access_ok")
        rule.onNodeWithTag("onboarding_continue").performScrollTo().assertTextContains("Continue")
    }
}

/** The no-grant pass (after ungrant.sh): required rows are red with a Grant button. */
@RunWith(AndroidJUnit4::class)
@Milestone("M9")
@NoGrant
class OnboardingNoGrantTest {
    @get:Rule val rule = createComposeRule()
    private val env = TestEnv()

    @After fun tearDown() = env.close()

    @Test
    fun checklistShowsMissingRequiredGrants() {
        val real = PlatformPorts.of(env.context.platform)
        val deps =
            UiDeps(
                env.graph,
                dev.brainrotprevention.ui.platform.UiPlatform(env.engine, real.health, env.apps, env.strictPort),
                tickMs = 60_000
            )
        rule.setContent { dev.brainrotprevention.ui.nav.BrpUi(deps, startRoute = Routes.ONBOARDING) }
        rule.tap("onboarding_next")
        rule.tap("onboarding_next")
        rule.tap("onboarding_next")
        rule.waitForTag("onboarding_accessibility_missing")
        rule.waitForTag("onboarding_usage_access_missing")
        rule.waitForTag("onboarding_fix_accessibility")
        rule.onNodeWithTag("onboarding_continue").performScrollTo().assertTextContains("Continue anyway")
    }
}

/** Flow with fake health: intro → checklist → template → editor, and the restricted-settings card. */
@RunWith(AndroidJUnit4::class)
@Milestone("M9")
class OnboardingFlowTest {
    @get:Rule val rule = createComposeRule()
    private val env = TestEnv()

    @After fun tearDown() = env.close()

    @Test
    fun restrictedInstallShowsAppInfoPath() {
        env.health.health = UiHealth(
            items = listOf(
                UiGrantStatus(UiGrant.ACCESSIBILITY, granted = false, required = true),
                UiGrantStatus(UiGrant.USAGE_ACCESS, granted = true, required = true),
            ),
            restrictedInstall = true,
        )
        rule.show(env, Routes.ONBOARDING)
        rule.tap("onboarding_skip_intro")
        rule.tap("onboarding_fix_accessibility")
        rule.waitForTag("onboarding_restricted")
        rule.scrollTap("onboarding_app_info")
    }

    @Test
    fun templatePickOpensEditorAndMarksDone() {
        rule.show(env, Routes.ONBOARDING)
        rule.tap("onboarding_skip_intro")
        rule.scrollTap("onboarding_continue")
        rule.scrollTap("template_morning")
        rule.waitForTag("editor_name")
        rule.waitUntil(5_000) { kotlinx.coroutines.runBlocking { env.graph.settings.getPrefs() }.onboardingDone }
    }
}
