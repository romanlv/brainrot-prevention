package dev.brainrotprevention.ui

import dev.brainrotprevention.engine.ActivationRefusal
import dev.brainrotprevention.engine.Refusal
import dev.brainrotprevention.ui.editor.EditorOps
import dev.brainrotprevention.ui.format.Format
import dev.brainrotprevention.ui.home.HomeState
import dev.brainrotprevention.ui.platform.UiGrant
import dev.brainrotprevention.ui.platform.UiGrantStatus
import dev.brainrotprevention.ui.platform.UiHealth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for review round 3 (UI logic). */
class Round3UiTest {
    private fun health(vararg missing: UiGrant, starting: Boolean = false) = UiHealth(
        items = listOf(UiGrant.ACCESSIBILITY, UiGrant.USAGE_ACCESS, UiGrant.LOCATION_FINE, UiGrant.LOCATION_BACKGROUND)
            .map { UiGrantStatus(it, granted = it !in missing, required = true) },
        accessibilityStarting = starting,
    )

    private fun home(h: UiHealth) = HomeState(loaded = true, zone = ZoneId.of("UTC"), health = h)

    /** Minor 5, scenario A: missing location only stops Wi-Fi/location triggers, not blocking. */
    @Test
    fun missingLocationIsASmallerNotice() {
        val st = home(health(UiGrant.LOCATION_FINE, UiGrant.LOCATION_BACKGROUND))
        assertFalse(st.blockingOff)
        assertTrue(st.triggersUnchecked)
        assertEquals(UiGrant.LOCATION_FINE, st.missingTriggerGrant)
        // With the service off too, the big banner wins and names accessibility.
        val both = home(health(UiGrant.ACCESSIBILITY, UiGrant.LOCATION_FINE))
        assertTrue(both.blockingOff)
        assertFalse(both.triggersUnchecked)
        assertEquals(UiGrant.ACCESSIBILITY, both.missingGrant)
        assertEquals(UiGrant.USAGE_ACCESS, home(health(UiGrant.USAGE_ACCESS)).missingGrant)
        assertFalse(home(health()).triggersUnchecked)
    }

    /** Minor 5, scenario B: an enabled service that hasn't bound yet (cold start) isn't "Blocking is off". */
    @Test
    fun serviceBindGraceIsNotBlockingOff() {
        assertFalse(home(health(UiGrant.ACCESSIBILITY, starting = true)).blockingOff)
        assertTrue(home(health(UiGrant.ACCESSIBILITY, starting = false)).blockingOff)
        // The grace covers only the bind, not usage access.
        assertTrue(home(health(UiGrant.ACCESSIBILITY, UiGrant.USAGE_ACCESS, starting = true)).blockingOff)
    }

    /** Minor 7: a cut paste goes back to the last full line. */
    @Test
    fun cutPasteKeepsWholeLines() {
        assertNull(EditorOps.capPaste("a.example\nb.example"))
        val line = "site.example\n"
        val long = line.repeat(EditorOps.MAX_PASTE_CHARS / line.length) + "example.com\n"
        val cut = EditorOps.capPaste(long)!!
        assertTrue(cut.length <= EditorOps.MAX_PASTE_CHARS)
        assertTrue(cut.lines().all { it.isEmpty() || it == "site.example" })
        assertFalse(cut.contains("example.co"))
    }

    /** Major 1: the refusal says how to leave room to turn Strict Mode off. */
    @Test
    fun lockInMessagesSayToLeaveTenMinutes() {
        val hint = "leave at least 10 minutes a week unscheduled so strict mode can be turned off"
        assertTrue(Format.activationRefusal(ActivationRefusal.UNBOUNDED_FOLLOWED).lowercase().contains(hint))
        assertTrue(Format.refusal(Refusal.UNBOUNDED_UNDER_STRICT).lowercase().contains(hint))
    }
}
