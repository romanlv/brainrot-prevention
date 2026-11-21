package dev.brainrotprevention.engine

import dev.brainrotprevention.model.Backup
import dev.brainrotprevention.model.BackupCodec
import dev.brainrotprevention.model.BackupLimit
import dev.brainrotprevention.model.BackupLocation
import dev.brainrotprevention.model.BackupPrefs
import dev.brainrotprevention.model.BackupQuickBlock
import dev.brainrotprevention.model.BackupSchedule
import dev.brainrotprevention.model.BackupTimeWindow
import dev.brainrotprevention.model.BackupWebRule
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.WebRuleKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidatorTest {
    private val good = BackupSchedule(
        name = "Focus",
        timeWindows = listOf(BackupTimeWindow(31, 540, 1020)),
        limits = listOf(BackupLimit(LimitKind.TIME, Period.DAILY, 30 * MIN)),
        wifi = listOf("Home"),
        locations = listOf(BackupLocation("Gym", 52.5, 13.4, 200)),
        apps = listOf(T),
        webRules = listOf(BackupWebRule(WebRuleKind.DOMAIN, "https://www.Example.com/"), BackupWebRule(WebRuleKind.KEYWORD, "Games", true)),
    )
    private val backup =
        Backup(
            exportedAt = 1,
            schedules = listOf(good),
            quickBlock = BackupQuickBlock(apps = listOf(T), webRules = listOf(BackupWebRule(WebRuleKind.KEYWORD, "game")))
        )

    private fun invalidPath(b: Backup): String = ((BackupValidator.validate(b) as BackupValidation.Invalid).error).path

    @Test
    fun validNormalisesRules() {
        val v = BackupValidator.validate(backup) as BackupValidation.Valid
        assertEquals(listOf("example.com", "games"), v.backup.schedules[0].webRules.map { it.value })
        assertTrue(v.backup.schedules[0].webRules[1].anywhereInUrl)
    }

    @Test
    fun firstOffendingRow() {
        data class C(val b: Backup, val path: String)
        fun s(f: BackupSchedule.() -> BackupSchedule) = backup.copy(schedules = listOf(good, good.f()))
        val cases = listOf(
            C(s { copy(name = "") }, "schedules[1].name"),
            C(s { copy(name = "x".repeat(41)) }, "schedules[1].name"),
            C(s { copy(timeWindows = listOf(BackupTimeWindow(0, 0, 60))) }, "schedules[1].timeWindows[0]"),
            C(s { copy(timeWindows = listOf(BackupTimeWindow(1, 1440, 60))) }, "schedules[1].timeWindows[0]"),
            C(s { copy(timeWindows = listOf(BackupTimeWindow(1, 0, 0))) }, "schedules[1].timeWindows[0]"),
            C(s { copy(timeWindows = listOf(BackupTimeWindow(1, 60, 60))) }, "schedules[1].timeWindows[0]"),
            C(s { copy(limits = listOf(BackupLimit(LimitKind.TIME, Period.DAILY, 30_000))) }, "schedules[1].limits[0]"),
            C(s { copy(limits = listOf(BackupLimit(LimitKind.TIME, Period.DAILY, 24 * Periods.HOUR))) }, "schedules[1].limits[0]"),
            C(s { copy(limits = listOf(BackupLimit(LimitKind.LAUNCHES, Period.DAILY, 501))) }, "schedules[1].limits[0]"),
            C(
                s {
                    copy(
                        limits = listOf(BackupLimit(LimitKind.LAUNCHES, Period.DAILY, 3), BackupLimit(LimitKind.LAUNCHES, Period.HOURLY, 3))
                    )
                },
                "schedules[1].limits[1]"
            ),
            C(s { copy(mode = Mode.ALLOWLIST) }, "schedules[1].limits[0]"), // §5.4 no limits in allowlist mode
            C(s { copy(mode = Mode.ALLOWLIST, limits = emptyList(), strictFollowed = true) }, "schedules[1].strictFollowed"), // §6.3
            C(s { copy(wifi = listOf(" ")) }, "schedules[1].wifi[0]"),
            C(s { copy(wifi = listOf("a", "a")) }, "schedules[1].wifi[1]"),
            C(s { copy(locations = listOf(BackupLocation("x", 0.0, 0.0, 50))) }, "schedules[1].locations[0]"),
            C(s { copy(locations = listOf(BackupLocation("x", 91.0, 0.0, 500))) }, "schedules[1].locations[0]"),
            C(s { copy(apps = listOf("not a package")) }, "schedules[1].apps[0]"),
            C(s { copy(apps = listOf(T, T)) }, "schedules[1].apps[1]"),
            C(s { copy(webRules = listOf(BackupWebRule(WebRuleKind.DOMAIN, "nodot"))) }, "schedules[1].webRules[0]"),
            C(s { copy(webRules = listOf(BackupWebRule(WebRuleKind.KEYWORD, "x"))) }, "schedules[1].webRules[0]"),
            C(
                s {
                    copy(webRules = listOf(BackupWebRule(WebRuleKind.DOMAIN, "a.com"), BackupWebRule(WebRuleKind.DOMAIN, "www.a.com")))
                },
                "schedules[1].webRules[1]"
            ),
            C(
                backup.copy(
                    schedules = List(51) {
                        good.copy(locations = List(2) { BackupLocation("l$it", 1.0, 1.0, 100) })
                    }
                ),
                "schedules[50].locations"
            ),
            C(backup.copy(quickBlock = BackupQuickBlock(apps = listOf("bad"))), "quickBlock.apps[0]"),
            C(
                backup.copy(quickBlock = BackupQuickBlock(webRules = listOf(BackupWebRule(WebRuleKind.KEYWORD, "two words")))),
                "quickBlock.webRules[0]"
            ),
            C(
                backup.copy(
                    quickBlock = BackupQuickBlock(
                        webRules = listOf(BackupWebRule(WebRuleKind.KEYWORD, "aa"), BackupWebRule(WebRuleKind.KEYWORD, "AA"))
                    )
                ),
                "quickBlock.webRules[1]"
            ),
            C(backup.copy(excludedBrowsers = listOf("")), "excludedBrowsers[0]"),
            C(backup.copy(prefs = BackupPrefs(dayStartHour = 24)), "prefs.dayStartHour"),
            C(backup.copy(prefs = BackupPrefs(limitWarningMin = -1)), "prefs.limitWarningMin"),
            C(backup.copy(prefs = BackupPrefs(notifyBeforeStartMin = 999)), "prefs.notifyBeforeStartMin"),
            C(backup.copy(prefs = BackupPrefs(notifyBeforePauseEndMin = 61)), "prefs.notifyBeforePauseEndMin"),
        )
        for (c in cases) assertEquals(c.path, invalidPath(c.b))
    }

    @Test
    fun textValidationAndRoundTrip() {
        val text = BackupCodec.encode(backup)
        val v = BackupValidator.validateText(text) as BackupValidation.Valid
        // Round trip: validated → import plan → export → identical JSON (except exportedAt).
        val plan = BackupCodec.toImportPlan(v.backup, now = 5)
        val schedules = plan.schedules.mapIndexed { i, s -> s.copy(id = i + 1L) }
        val qb = dev.brainrotprevention.model.QuickBlock(
            apps = plan.quickBlockApps,
            webRules = plan.quickBlockWebRules,
            blockNotifications = plan.quickBlockNotifications
        )
        val prefs = dev.brainrotprevention.model.Prefs(dayStartHour = plan.prefs.dayStartHour)
        val again = BackupCodec.fromConfig(schedules, qb, plan.excludedBrowsers, prefs, exportedAt = 1)
        assertEquals(BackupCodec.encode(v.backup), BackupCodec.encode(again))
        val bad = BackupValidator.validateText("""{"format":1,"schedules":[{"name":"x","bogus":1}]}""") as BackupValidation.Invalid
        assertEquals("file", bad.error.path)
        assertTrue(bad.error.toString().startsWith("file: "))
        val wrongFormat = BackupValidator.validateText("""{"format":2}""") as BackupValidation.Invalid
        assertTrue(wrongFormat.error.message.contains("format 2"))
    }

    @Test
    fun ruleNormalisationHelper() {
        assertEquals("a.com", ScheduleValidator.normalizeRule(domain("https://a.com/x/y"))?.value)
        assertEquals(null, ScheduleValidator.normalizeRule(domain("??")))
        assertTrue(ScheduleValidator.isPackageName("com.a_b.c1"))
        assertEquals(null, ScheduleValidator.validate(schedule(1, T)))
    }
}
