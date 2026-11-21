package dev.brainrotprevention.ui

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind
import dev.brainrotprevention.testing.Milestone
import dev.brainrotprevention.ui.common.Notice
import dev.brainrotprevention.ui.editor.EditorViewModel
import dev.brainrotprevention.ui.editor.Template
import dev.brainrotprevention.ui.editor.WebInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Editor ViewModel behaviour that is hard to time through the UI: double submit, deleted schedule, saved state. */
@RunWith(AndroidJUnit4::class)
@Milestone("M4")
class EditorViewModelTest {
    private val env = TestEnv()
    private val instr = InstrumentationRegistry.getInstrumentation()

    @After fun tearDown() = env.close()

    private fun <T> onMain(block: () -> T): T {
        var r: Result<T>? = null
        instr.runOnMainSync { r = runCatching(block) }
        return r!!.getOrThrow()
    }

    private fun waitUntil(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            check(System.nanoTime() < end) { "condition not met in $timeoutMs ms" }
            Thread.sleep(20)
        }
    }

    @Test
    fun secondSaveWhileTheFirstIsInFlightIsIgnored() {
        val vm = onMain { EditorViewModel(env.deps, 0, Template.WIND_DOWN) }
        onMain {
            vm.update { it.copy(apps = listOf("com.example.a")) }
            // Two taps (bottom and top Save) before the first write has finished.
            vm.save()
            assertTrue(vm.saving.value)
            vm.save()
        }
        waitUntil { vm.saved.value }
        Thread.sleep(300)
        assertEquals(1, env.schedules().size)
    }

    @Test
    fun deletedScheduleClosesAfterTheNotice() {
        val vm = onMain { EditorViewModel(env.deps, 999, null) }
        waitUntil { vm.notice.value != null }
        assertEquals("Not found", (vm.notice.value as Notice.Message).title)
        assertFalse(vm.saved.value)
        onMain { vm.dismissNotice() }
        assertTrue(vm.saved.value)
    }

    @Test
    fun draftAndTypedWebsiteSurviveProcessDeath() {
        val state = SavedStateHandle()
        val vm = onMain { EditorViewModel(env.deps, 0, Template.WORKDAY, savedState = state) }
        onMain {
            vm.update { it.copy(name = "Half done", apps = listOf("com.example.b")) }
            vm.setWebInput(WebInput(text = "reddit.com"))
        }
        waitUntil { state.contains("editor_draft") && state.contains("editor_web_input") }
        // A new ViewModel from the same saved state (as after the process was killed).
        val restored = onMain { EditorViewModel(env.deps, 0, Template.WORKDAY, savedState = state) }
        assertEquals("Half done", restored.draft.value?.name)
        assertEquals(listOf("com.example.b"), restored.draft.value?.apps)
        assertEquals("reddit.com", restored.webInput.value.text)
        assertTrue("restored edits still count as unsaved", restored.hasChanges())
        onMain { restored.save() }
        waitUntil { restored.saved.value }
        assertEquals(
            listOf(WebRule(kind = WebRuleKind.DOMAIN, value = "reddit.com")),
            env.schedules().single().webRules.map {
                it.copy(id = 0)
            }
        )
    }
}
