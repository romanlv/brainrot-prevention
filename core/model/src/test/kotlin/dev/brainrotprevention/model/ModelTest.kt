package dev.brainrotprevention.model

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    @Test
    fun strictOptions() {
        assertTrue(StrictOptions.isValid(0))
        assertTrue(StrictOptions.isValid(7))
        assertTrue(StrictOptions.isValid(3))
        assertFalse(StrictOptions.isValid(5)) // uninstall without settings
        assertFalse(StrictOptions.isValid(8))
        assertTrue(StrictModeRow(options = 3).has(StrictOptions.BLOCK_SETTINGS))
    }

    @Test
    fun scheduleHelpers() {
        val s =
            Schedule(
                name = "a",
                timeWindows = listOf(TimeWindow(days = 1, startMin = 1320, endMin = 360)),
                wifi = listOf(WifiCondition(ssid = "x"))
            )
        assertEquals(setOf(TriggerType.TIME, TriggerType.WIFI), s.triggerTypes)
        assertTrue(s.timeWindows[0].overnight)
        assertEquals(null, s.limit(LimitKind.TIME))
        assertTrue(QuickBlock().isEmpty)
        assertFalse(QuickBlock(apps = listOf("a.b")).isEmpty)
    }

    @Test
    fun backupJsonUsesSpecNames() {
        val b = Backup(
            exportedAt = 42,
            schedules = listOf(
                BackupSchedule(
                    name = "Focus",
                    mode = Mode.ALLOWLIST,
                    combine = Combine.ALL,
                    allowlistWeb = AllowlistWeb.ONLY_LISTED,
                    timeWindows = listOf(BackupTimeWindow(31, 540, 1020)),
                    limits = listOf(BackupLimit(LimitKind.LAUNCHES, Period.HOURLY, 3)),
                    wifi = listOf("Home"),
                    locations = listOf(BackupLocation("Gym", 1.5, 2.5, 100, true)),
                    apps = listOf("a.b"),
                    webRules = listOf(BackupWebRule(WebRuleKind.KEYWORD, "game", true)),
                ),
            ),
        )
        val text = BackupCodec.encode(b)
        for (k in listOf(
            "\"format\": 1", "\"exportedAt\": 42", "\"ALLOWLIST\"", "\"ALL\"", "\"ONLY_LISTED\"", "\"LAUNCHES\"", "\"HOURLY\"",
            "\"KEYWORD\"", "\"anywhereInUrl\": true", "\"startMin\": 540", "\"radiusM\": 100", "\"quickBlock\"", "\"excludedBrowsers\"",
            "\"dayStartHour\"", "\"hidePauseOption\"",
        )) {
            assertTrue(k, text.contains(k))
        }
        assertEquals(b, BackupCodec.decode(text))
        // Minimal seed with defaults.
        val seed = BackupCodec.decode(
            """{"format":1,"schedules":[{"name":"S","apps":["a.b"],"webRules":[{"kind":"DOMAIN","value":"x.com"}]}]}"""
        )
        assertEquals(Mode.BLOCKLIST, seed.schedules[0].mode)
        assertTrue(seed.schedules[0].blockUnsupportedBrowsers)
    }

    @Test
    fun backupDecodeErrors() {
        for (bad in listOf(
            "not json",
            """{"format":1,"extra":true}""",
            """{"format":1,"schedules":[{"name":"a","mode":"NOPE"}]}""",
            """{"format":3}"""
        )) {
            try {
                BackupCodec.decode(bad)
                throw AssertionError("expected failure for $bad")
            } catch (e: BackupFormatException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }

    @Test
    fun backupConversion() {
        val s = Schedule(
            id = 7, name = "x", pausedUntil = 5, manualUntil = 6, sortOrder = 2,
            apps = listOf("b.b", "a.a"), wifi = listOf(WifiCondition(ssid = "z"), WifiCondition(ssid = "y")),
            locations = listOf(LocationCondition(label = "L", lat = 1.0, lon = 2.0, radiusM = 100, lastState = LocationState.INSIDE)),
            limits = listOf(UsageLimit(kind = LimitKind.TIME, period = Period.DAILY, allowance = 60_000)),
            timeWindows = listOf(TimeWindow(days = 1, startMin = 10, endMin = 20)),
            webRules = listOf(WebRule(kind = WebRuleKind.DOMAIN, value = "x.com")),
        )
        val b = BackupCodec.fromConfig(
            listOf(s),
            QuickBlock(active = true, until = 9, apps = listOf("q.q")),
            setOf("e.e"),
            Prefs(dayStartHour = 1, pendingDayStartHour = 4),
            99
        )
        assertEquals(listOf("a.a", "b.b"), b.schedules[0].apps)
        assertEquals(listOf("y", "z"), b.schedules[0].wifi)
        assertEquals(4, b.prefs.dayStartHour)
        val plan = BackupCodec.toImportPlan(b, now = 1000)
        val back = plan.schedules.single()
        assertEquals(null, back.pausedUntil)
        assertEquals(null, back.manualUntil)
        assertEquals(1000, back.createdAt)
        assertEquals(LocationState.UNKNOWN, back.locations[0].lastState)
        assertEquals(listOf("q.q"), plan.quickBlockApps)
        assertEquals(setOf("e.e"), plan.excludedBrowsers)
    }

    @Test
    fun snapshotSerializes() {
        val snap = ConfigSnapshot(
            schedules = listOf(Schedule(id = 1, name = "a")),
            browsers = listOf(
                BrowserEntry(
                    "com.android.chrome",
                    listOf(UrlBarId("url_bar"), UrlBarId("ADDRESSBAR_URL_BOX", UrlBarIdKind.COMPOSE_TAG, UrlBarSource.CONTENT_DESC, false))
                )
            ),
            webApps = mapOf("w.a" to "https://x.com"),
        )
        val json = Json { encodeDefaults = false }
        val text = json.encodeToString(ConfigSnapshot.serializer(), snap)
        assertTrue(text.contains("\"package\":\"com.android.chrome\""))
        assertEquals(snap, json.decodeFromString(ConfigSnapshot.serializer(), text))
        assertEquals(listOf("com.android.settings", "com.miui.securitycenter", "com.samsung.android.lool"), StrictGuards().settings)
    }

    private class FakeSystem(var wall: Long, var el: Long) : SystemTime {
        override fun currentTimeMillis() = wall

        override fun elapsedRealtime() = el

        override fun uptimeMillis() = el
    }

    @Test
    fun clocks() {
        val sys = FakeSystem(1_000_000, 500)
        val zone = ZoneId.of("Europe/Berlin")
        val real = SystemPolicyClock(sys) { zone }
        assertEquals(ClockReading(1_000_000, zone, 500), real.read())
        assertEquals(7L, real.toSystemMillis(7))
        val debug = OffsetPolicyClock(sys, { zone })
        debug.advance(10_000)
        assertEquals(1_010_000, debug.now())
        assertEquals(10_500, debug.elapsed())
        debug.jump(-50_000)
        assertEquals(960_000, debug.now())
        assertEquals(10_500, debug.elapsed())
        assertEquals(1_000_000, debug.toSystemMillis(960_000))
        debug.setWall(Instant.ofEpochMilli(5_000_000))
        assertEquals(5_000_000, debug.now())
        assertEquals(zone, debug.zone())
        debug.real()
        assertEquals(1_000_000, debug.now())
        assertTrue(JvmSystemTime.currentTimeMillis() > 0)
        assertTrue(JvmSystemTime.elapsedRealtime() >= 0)
        assertTrue(JvmSystemTime.uptimeMillis() >= 0)
        assertEquals(ZoneId.systemDefault(), SystemPolicyClock(sys).zone())
    }
}
