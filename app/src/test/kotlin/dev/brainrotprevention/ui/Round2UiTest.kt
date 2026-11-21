package dev.brainrotprevention.ui

import dev.brainrotprevention.engine.QUICK_BLOCK_ID
import dev.brainrotprevention.engine.ScheduleStatus
import dev.brainrotprevention.engine.ScheduleValidator
import dev.brainrotprevention.engine.Until
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind
import dev.brainrotprevention.ui.editor.EditorOps
import dev.brainrotprevention.ui.home.HomeState
import dev.brainrotprevention.ui.platform.UiGrant
import dev.brainrotprevention.ui.platform.UiGrantStatus
import dev.brainrotprevention.ui.platform.UiHealth
import dev.brainrotprevention.ui.strict.StrictForm
import dev.brainrotprevention.ui.strict.StrictSummary
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the round-2 reviews (UI logic). */
class Round2UiTest {
    private fun rule(v: String) = WebRule(kind = WebRuleKind.DOMAIN, value = v)

    /** Adversarial 1: the bulk summary names the cap; merging never exceeds it or duplicates. */
    @Test
    fun bulkAddIsCappedAndMerged() {
        val max = ScheduleValidator.MAX_WEB_RULES
        val text = (1..max + 10).joinToString("\n") { "site$it.example" }
        val (next, r) = EditorOps.bulk(listOf(rule("a.example")), text)
        assertEquals(max, next.size)
        assertEquals(11, r.overLimit)
        assertTrue(EditorOps.bulkSummary(r), EditorOps.bulkSummary(r).contains("11 not added"))
        assertTrue(EditorOps.bulkSummary(r).contains("2,000"))
        assertTrue(EditorOps.atLimit(next))
        val merged = EditorOps.mergeAdded(listOf(rule("x.example"), rule("site1.example")), r.added)
        assertEquals(max, merged.size)
        assertEquals(merged.size, merged.map { it.value }.toSet().size)
        assertEquals("x.example", merged.first().value)
    }

    /** Adversarial 6: with the service off, Home says "Blocking is off" and "Would be blocking", not "Blocking now". */
    @Test
    fun homeSaysBlockingIsOffWhenTheServiceIsNotConnected() {
        val qb = ScheduleStatus(QUICK_BLOCK_ID, true, true, false, true, true, null, Until.UntilStopped, Until.UntilStopped, null, null)
        val off = UiHealth(
            listOf(
                UiGrantStatus(UiGrant.ACCESSIBILITY, granted = false, required = true, detail = "enabled, not connected"),
                UiGrantStatus(UiGrant.USAGE_ACCESS, granted = true, required = true),
            ),
        )
        val st = HomeState(loaded = true, statuses = mapOf(QUICK_BLOCK_ID to qb), zone = ZoneId.of("UTC"), health = off)
        assertTrue(st.blockingOff)
        assertEquals(UiGrant.ACCESSIBILITY, st.missingGrant)
        assertEquals("Would be blocking now: Quick Block", st.headline)
        assertFalse(st.anyBlocking)
        assertTrue(st.wouldBlock)
        val on = st.copy(health = off.copy(items = off.items.map { it.copy(granted = true) }))
        assertFalse(on.blockingOff)
        assertEquals("Blocking now: Quick Block", on.headline)
        // Health not known yet (no items): no banner.
        assertFalse(st.copy(health = UiHealth()).blockingOff)
    }

    /** Adversarial 9: for Timer/PIN/…, the confirmation says allow-only schedules are locked too. */
    @Test
    fun strictSummaryMentionsAllowlistSchedulesForOtherMethods() {
        val allow = Schedule(id = 1, name = "Only maps", mode = Mode.ALLOWLIST)
        val f = StrictForm(method = StrictMethod.TIMER, options = StrictOptions.LOCK_EDITING or StrictOptions.BLOCK_SETTINGS)
        val applies = StrictSummary.of(f, listOf(allow), 0, ZoneId.of("UTC")).applies
        assertTrue(applies, applies.contains("Allow only selected") && applies.contains("Settings is blocked"))
        assertEquals("Covers every schedule and Quick Block.", StrictSummary.of(f, emptyList(), 0, ZoneId.of("UTC")).applies)
    }
}
