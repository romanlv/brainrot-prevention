package dev.brainrotprevention.engine

import dev.brainrotprevention.model.AllowlistWeb
import dev.brainrotprevention.model.ClockReading
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.ForegroundApp
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.SensorState
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.StrictState
import dev.brainrotprevention.model.TimeWindow
import dev.brainrotprevention.model.WebTarget
import dev.brainrotprevention.model.Weekdays.ALL
import dev.brainrotprevention.model.Weekdays.MON
import dev.brainrotprevention.model.Weekdays.WEEKDAYS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {
    private val engine = Engine()
    private val mon10 = clock("2026-11-23T10:00", elapsed = 5_000_000)

    private fun eval(
        snap: ConfigSnapshot,
        vararg pkgs: String,
        clock: ClockReading = mon10,
        web: List<WebTarget> = emptyList(),
        state: EngineState = EngineState(),
        sensors: SensorState = SensorState(),
        keyguard: Boolean = false,
        urlBarMissing: Set<String> = emptySet(),
        components: Map<String, String> = emptyMap(),
        interactive: Boolean = true,
        adminPending: Boolean = false,
    ) = engine.evaluate(
        EvaluationInput(
            snapshot = snap,
            clock = clock,
            state = state,
            sensors = sensors,
            foreground = pkgs.map { ForegroundApp(it, components[it]) },
            webTargets = web,
            screenInteractive = interactive,
            keyguardLocked = keyguard,
            urlBarMissing = urlBarMissing,
            adminActivationPending = adminPending,
        ),
    )

    private fun reasons(d: Decision?) = (d as? Decision.Block)?.reasons?.map { it.source }.orEmpty()

    private val strictTimer = StrictModeRow(
        state = StrictState.ON,
        options = StrictOptions.ALL,
        method = StrictMethod.TIMER,
        endsAt = at("2026-11-23T12:00"),
        endsAtElapsed = 99_000_000,
    )

    @Test
    fun precedenceExemptThenStrictThenSchedules() {
        val s = schedule(1, T, "com.android.settings", "com.android.systemui", "my.launcher")
        val snap =
            ConfigSnapshot(
                schedules = listOf(s),
                strict = strictTimer,
                exemptPackages = setOf("my.launcher"),
                ownPackage = "dev.brainrotprevention"
            )
        val r = eval(
            snap,
            T,
            "com.android.settings",
            "com.android.systemui",
            "my.launcher",
            "com.android.packageinstaller",
            "dev.brainrotprevention",
        )
        assertEquals(listOf(ReasonSource.Schedule(1)), reasons(r.apps[T]))
        assertEquals(listOf(ReasonSource.StrictSettings), reasons(r.apps["com.android.settings"])) // guard wins over schedule
        assertEquals(Decision.Allow, r.apps["com.android.systemui"]) // exempt even when listed
        assertEquals(Decision.Allow, r.apps["my.launcher"])
        assertEquals(Decision.Allow, r.apps["dev.brainrotprevention"])
        assertEquals(listOf(ReasonSource.StrictUninstall), reasons(r.apps["com.android.packageinstaller"]))
        val until = (r.apps["com.android.settings"] as Decision.Block).until
        assertEquals(Until.UnlockCondition(StrictMethod.TIMER, at("2026-11-23T12:00")), until)
        assertEquals(StrictDisplayState.ACTIVE, r.summary.strict)
    }

    @Test
    fun exemptComponentAndAdminFlow() {
        val snap = ConfigSnapshot(strict = strictTimer)
        // The real class names (checked on the API 37 emulator): the credential prompt and DeviceAdminAdd.
        val comps = mapOf("com.android.settings" to "com.android.settings/com.android.settings.password.ConfirmDeviceCredentialActivity")
        assertEquals(Decision.Allow, eval(snap, "com.android.settings", components = comps).apps["com.android.settings"])
        val inner = mapOf("com.android.settings" to "com.android.settings/.password.ConfirmDeviceCredentialActivity\$InternalActivity")
        assertEquals(Decision.Allow, eval(snap, "com.android.settings", components = inner).apps["com.android.settings"])
        val old = mapOf("com.android.settings" to "com.android.settings/.ConfirmDeviceCredentialActivity") // not a real class
        assertTrue(eval(snap, "com.android.settings", components = old).apps.getValue("com.android.settings").isBlock)
        val admin = mapOf("com.android.settings" to "com.android.settings/.applications.specialaccess.deviceadmin.DeviceAdminAdd")
        assertTrue(eval(snap, "com.android.settings", components = admin).apps.getValue("com.android.settings").isBlock)
        assertEquals(
            Decision.Allow,
            eval(snap, "com.android.settings", components = admin, adminPending = true).apps["com.android.settings"]
        )
        // Guards off when strict is not ACTIVE or the option is not set.
        val noSettings = snap.copy(strict = strictTimer.copy(options = StrictOptions.LOCK_EDITING))
        assertEquals(Decision.Allow, eval(noSettings, "com.android.settings").apps["com.android.settings"])
    }

    @Test
    fun allowlistEssentialsAndKeyguard() {
        val s = schedule(1, "com.whatsapp") { copy(mode = Mode.ALLOWLIST) }
        val snap = ConfigSnapshot(schedules = listOf(s), allowlistEssentials = setOf("com.google.android.deskclock"))
        val r =
            eval(
                snap,
                "com.whatsapp",
                T,
                "com.google.android.deskclock",
                "com.android.documentsui",
                "com.android.settings",
                "other.launcher"
            )
        assertEquals(Decision.Allow, r.apps["com.whatsapp"])
        assertTrue(r.apps.getValue(T).isBlock)
        assertEquals(Decision.Allow, r.apps["com.google.android.deskclock"])
        assertEquals(Decision.Allow, r.apps["com.android.documentsui"])
        assertEquals(Decision.Allow, r.apps["com.android.settings"])
        assertTrue(r.apps.getValue("other.launcher").isBlock) // third-party home apps are not essential
        assertEquals(Decision.Allow, eval(snap, T, keyguard = true).apps[T])
        // Settings in allowlist mode is still guarded by Strict first.
        val strict = snap.copy(strict = strictTimer)
        assertEquals(listOf(ReasonSource.StrictSettings), reasons(eval(strict, "com.android.settings").apps["com.android.settings"]))
    }

    @Test
    fun blocklistAlwaysHasPriorityAndReasonsCombine() {
        val allow = schedule(1, T) { copy(mode = Mode.ALLOWLIST) }
        val block = schedule(2, T) { copy(timeWindows = listOf(win(MON, "09:00", "11:00"))) }
        val qb = QuickBlock(active = true, apps = listOf(T))
        val r = eval(ConfigSnapshot(schedules = listOf(allow, block), quickBlock = qb), T)
        val d = r.apps.getValue(T) as Decision.Block
        assertEquals(listOf(ReasonSource.Schedule(2), ReasonSource.QuickBlock), d.reasons.map { it.source })
        assertEquals(Until.UntilStopped, d.until) // UntilStopped wins over At
        assertEquals(Until.At(at("2026-11-23T11:00")), d.reasons[0].until)
        assertEquals(listOf(1L, 2L, QUICK_BLOCK_ID), r.summary.blocking)
    }

    @Test
    fun untilKinds() {
        val noTrig = schedule(1, T)
        assertEquals(Until.Ongoing, (eval(ConfigSnapshot(schedules = listOf(noTrig)), T).apps[T] as Decision.Block).until)
        val manual = schedule(1, T) { copy(timeWindows = listOf(win(MON, "20:00", "21:00")), manualUntil = at("2026-11-23T10:30")) }
        assertEquals(
            Until.At(at("2026-11-23T10:30")),
            (eval(ConfigSnapshot(schedules = listOf(manual)), T).apps[T] as Decision.Block).until
        )
        val stopped = manual.copy(manualUntil = Schedule.UNTIL_STOPPED)
        assertEquals(Until.UntilStopped, (eval(ConfigSnapshot(schedules = listOf(stopped)), T).apps[T] as Decision.Block).until)
        val manualAndWindow =
            schedule(1, T) { copy(timeWindows = listOf(win(MON, "09:00", "11:00")), manualUntil = at("2026-11-23T10:30")) }
        assertEquals(
            Until.At(at("2026-11-23T11:00")),
            (eval(ConfigSnapshot(schedules = listOf(manualAndWindow)), T).apps[T] as Decision.Block).until
        )
        val wifiArmed = schedule(1, T) { copy(wifi = wifi("Home")) }
        val wr = eval(ConfigSnapshot(schedules = listOf(wifiArmed)), T, sensors = SensorState(ssid = "Home"))
        assertEquals(Until.WhileConditions("Wi-Fi Home"), (wr.apps[T] as Decision.Block).until)
        val wifiAndManual = wifiArmed.copy(manualUntil = at("2026-11-23T10:30"))
        val wm = eval(ConfigSnapshot(schedules = listOf(wifiAndManual)), T, sensors = SensorState(ssid = "Home"))
        assertEquals(Until.WhileConditions("Wi-Fi Home"), (wm.apps[T] as Decision.Block).until)
        val anyBoth = schedule(1, T) { copy(wifi = wifi("Home"), timeWindows = listOf(win(MON, "09:00", "11:00"))) }
        val ab = eval(ConfigSnapshot(schedules = listOf(anyBoth)), T, sensors = SensorState(ssid = "Home"))
        assertTrue((ab.apps[T] as Decision.Block).until is Until.WhileConditions)
        val allWeek = schedule(1, T) { copy(timeWindows = listOf(win(ALL, "00:00", "24:00"))) }
        assertEquals(Until.Ongoing, (eval(ConfigSnapshot(schedules = listOf(allWeek)), T).apps[T] as Decision.Block).until)
        val qbTimer = QuickBlock(active = true, until = at("2026-11-23T10:25"), apps = listOf(T))
        assertEquals(Until.At(at("2026-11-23T10:25")), (eval(ConfigSnapshot(quickBlock = qbTimer), T).apps[T] as Decision.Block).until)
    }

    @Test
    fun untilCombination() {
        assertEquals(Until.At(5), Until.combine(listOf(Until.At(3), Until.At(5))))
        assertEquals(Until.WhileConditions("x"), Until.combine(listOf(Until.At(3), Until.WhileConditions("x"), Until.Ongoing)))
        assertEquals(Until.Ongoing, Until.combine(listOf(Until.At(3), Until.Ongoing)))
        val u = Until.UnlockCondition(StrictMethod.PIN, null)
        assertEquals(u, Until.combine(listOf(Until.At(3), u)))
        assertEquals(Until.Ongoing, Until.combine(emptyList()))
    }

    @Test
    fun timeBudgetBlocksWithPeriodUntil() {
        val s = schedule(1, T) { copy(limits = listOf(timeLimit(5))) }
        val snap = ConfigSnapshot(schedules = listOf(s))
        var state = EngineState()
        var el = 1_000_000L
        var now = at("2026-11-23T10:00")
        fun step(): EvaluationResult {
            val r = engine.evaluate(
                EvaluationInput(snap, ClockReading(now, BERLIN, el), state, foreground = listOf(ForegroundApp(T)), countFullDelta = true),
            )
            state = r.state
            return r
        }
        assertEquals(Decision.Allow, step().apps[T])
        now += 4 * MIN
        el += 4 * MIN
        val warn = step()
        assertTrue(warn.ledgerEvents.any { it is LedgerEvent.LimitWarning })
        assertEquals(listOf(BudgetLeft(1, MIN, null)), warn.summary.budgets)
        now += MIN
        el += MIN
        val r = step()
        val d = r.apps[T] as Decision.Block
        assertEquals(LimitKind.TIME, d.reasons.single().limit)
        assertEquals(Until.At(at("2026-11-24T00:00")), d.until) // resets at the next day start
        // Next day: allowed again.
        val next = engine.evaluate(EvaluationInput(snap, clock("2026-11-24T00:01", el + 1), state, foreground = listOf(ForegroundApp(T))))
        assertEquals(Decision.Allow, next.apps[T])
    }

    @Test
    fun launchGateThroughFacade() {
        val s = schedule(1, T) { copy(limits = listOf(launchLimit(3)), timeWindows = listOf(win(MON, "09:00", "10:30"))) }
        val snap =
            ConfigSnapshot(
                schedules = listOf(s),
                strict = StrictModeRow(state = StrictState.ON, method = StrictMethod.SCHEDULES, followAllSchedules = true)
            )
        var state = EngineState()
        var now = at("2026-11-23T10:00")
        var el = 1_000_000L
        fun step(vararg pkgs: String): EvaluationResult {
            val r = engine.evaluate(
                EvaluationInput(
                    snap,
                    ClockReading(now, BERLIN, el),
                    state,
                    foreground = pkgs.map {
                        ForegroundApp(it)
                    }
                )
            )
            state = r.state
            now += 31_000
            el += 31_000
            return r
        }
        repeat(3) {
            assertEquals(Decision.Allow, step(T).apps[T])
            step()
        }
        assertEquals(StrictDisplayState.ACTIVE, step().summary.strict) // armed + launches exhausted
        val blocked = step(T).apps[T] as Decision.Block
        assertEquals(LimitKind.LAUNCHES, blocked.reasons.single().limit)
        assertEquals(Until.At(at("2026-11-23T10:30")), blocked.until) // min(periodEnd, armEnd)
    }

    @Test
    fun unsupportedBrowsersAndWebApps() {
        val s = schedule(1) { copy(webRules = listOf(domain("example.com"))) }
        val snap = ConfigSnapshot(
            schedules = listOf(s),
            unsupportedBrowsers = setOf("odd.browser", "excluded.browser"),
            excludedBrowsers = setOf("excluded.browser"),
            webApps = mapOf("org.chromium.webapk.a1" to "https://example.com/app", "org.chromium.webapk.b2" to "https://fine.org/"),
        )
        val r =
            eval(
                snap,
                "odd.browser",
                "excluded.browser",
                "missing.bar",
                "org.chromium.webapk.a1",
                "org.chromium.webapk.b2",
                urlBarMissing = setOf("missing.bar")
            )
        assertEquals(listOf(ReasonSource.UnsupportedBrowser(1)), reasons(r.apps["odd.browser"]))
        assertEquals(Decision.Allow, r.apps["excluded.browser"])
        assertEquals(listOf(ReasonSource.UnsupportedBrowser(1)), reasons(r.apps["missing.bar"]))
        assertEquals(listOf(ReasonSource.Schedule(1)), reasons(r.apps["org.chromium.webapk.a1"]))
        assertEquals(Decision.Allow, r.apps["org.chromium.webapk.b2"])
        // blockUnsupportedBrowsers off, or no web rules → no F8.
        val off = snap.copy(schedules = listOf(s.copy(blockUnsupportedBrowsers = false)))
        assertEquals(Decision.Allow, eval(off, "odd.browser").apps["odd.browser"])
        val noRules = snap.copy(schedules = listOf(s.copy(webRules = emptyList())))
        assertEquals(Decision.Allow, eval(noRules, "odd.browser").apps["odd.browser"])
        // Quick Block web rules count too, with a null schedule id.
        val qb =
            ConfigSnapshot(
                quickBlock = QuickBlock(active = true, webRules = listOf(keyword("game"))),
                unsupportedBrowsers = setOf("odd.browser")
            )
        assertEquals(listOf(ReasonSource.UnsupportedBrowser(null)), reasons(eval(qb, "odd.browser").apps["odd.browser"]))
        // ONLY_LISTED: a WebAPK listed as allowed is judged as an unsupported browser.
        val only = schedule(2, "org.chromium.webapk.b2") { copy(mode = Mode.ALLOWLIST, allowlistWeb = AllowlistWeb.ONLY_LISTED) }
        assertEquals(
            listOf(ReasonSource.UnsupportedBrowser(2)),
            reasons(eval(snap.copy(schedules = listOf(only)), "org.chromium.webapk.b2").apps["org.chromium.webapk.b2"])
        )
    }

    @Test
    fun webBlocklist() {
        val s = schedule(1) { copy(webRules = listOf(domain("example.com"), keyword("game"), keyword("games", anywhere = true))) }
        val snap = ConfigSnapshot(schedules = listOf(s), redirectUriPrefix = "content://dev.brainrotprevention.blank/")
        data class W(val url: String, val block: Boolean)
        val cases = listOf(
            W("https://m.example.com/x", true),
            W("https://other.org", false),
            W("https://epicgame.test", true),
            W("https://site.test/r/games", true),
            W("https://example.com.", true),
            W("", false),
            W("about:blank", false),
            W("content://dev.brainrotprevention.blank/blank.html", false),
            W("chrome://newtab", false),
        )
        val r = eval(snap, CHROME, web = cases.map { WebTarget(CHROME, it.url) })
        for ((c, p) in cases.zip(r.pages)) assertEquals(c.url, c.block, p.decision.isBlock)
        assertEquals(Decision.Allow, r.apps[CHROME])
        assertTrue(r.anyBlock)
    }

    @Test
    fun webAllowlistOnlyListedAndOpen() {
        val only =
            schedule(1, CHROME) {
                copy(mode = Mode.ALLOWLIST, allowlistWeb = AllowlistWeb.ONLY_LISTED, webRules = listOf(domain("wikipedia.org")))
            }
        val snap = ConfigSnapshot(schedules = listOf(only), redirectUriPrefix = "content://dev.brainrotprevention.blank/")
        data class W(val url: String, val reliable: Boolean, val block: Boolean)
        val cases = listOf(
            W("https://en.wikipedia.org/wiki/X", true, false),
            W("https://example.com", true, true),
            W("https://example.com", false, false), // unreliable node never blocks under ONLY_LISTED
            W("content://dev.brainrotprevention.blank/blank.html", true, false), // S9: no loop
            W("chrome://newtab", true, false),
            W("chrome-native://newtab/", true, false),
            W("about:home", true, false),
            W("chrome://settings", true, true),
        )
        val r = eval(snap, CHROME, T, web = cases.map { WebTarget(CHROME, it.url, it.reliable) })
        for ((c, p) in cases.zip(r.pages)) assertEquals(c.toString(), c.block, p.decision.isBlock)
        assertEquals(Decision.Allow, r.apps[CHROME])
        assertTrue(r.apps.getValue(T).isBlock)
        // Browser not in allowed apps: the page isn't judged (the browser is blocked as an app).
        val other = eval(snap, "org.mozilla.firefox", web = listOf(WebTarget("org.mozilla.firefox", "https://example.com")))
        assertFalse(other.pages.single().decision.isBlock)
        assertTrue(other.apps.getValue("org.mozilla.firefox").isBlock)
        // OPEN: no web restrictions.
        val open = snap.copy(schedules = listOf(only.copy(allowlistWeb = AllowlistWeb.OPEN)))
        assertFalse(eval(open, CHROME, web = listOf(WebTarget(CHROME, "https://example.com"))).pages.single().decision.isBlock)
    }

    @Test
    fun webLaunchGateAndCounting() {
        val s = schedule(1) { copy(webRules = listOf(domain("example.com")), limits = listOf(launchLimit(1))) }
        val snap = ConfigSnapshot(schedules = listOf(s))
        var state = EngineState()
        var now = at("2026-11-23T10:00")
        fun step(url: String?): EvaluationResult {
            val r = engine.evaluate(
                EvaluationInput(
                    snap,
                    ClockReading(now, BERLIN, now),
                    state,
                    foreground = listOf(ForegroundApp(CHROME)),
                    webTargets = listOfNotNull(
                        url?.let {
                            WebTarget(CHROME, it)
                        }
                    )
                ),
            )
            state = r.state
            now += 31_000
            return r
        }
        assertFalse(step("https://www.example.com/a").pages.single().decision.isBlock)
        step(null)
        val r = step("https://example.com/b") // same item key (www stripped), new launch, exhausted
        assertEquals(LimitKind.LAUNCHES, (r.pages.single().decision as Decision.Block).reasons.single().limit)
        assertTrue(r.ledgerEvents.any { it is LedgerEvent.LaunchBlocked && it.key == "example.com" })
    }

    @Test
    fun screenOffMeansNoDecisions() {
        val snap = ConfigSnapshot(schedules = listOf(schedule(1, T)))
        val r = eval(snap, T, interactive = false)
        assertTrue(r.apps.isEmpty())
        assertEquals(listOf(1L), r.summary.blocking)
    }

    @Test
    fun failClosedOnEvaluationError() {
        val broken = TimeWindow(0, ALL, -5, 700) // contained at 10:00, but LocalTime.of(-5) throws for "until"
        val bl = schedule(1, T) { copy(timeWindows = listOf(broken)) }
        val al = schedule(2, "com.whatsapp") { copy(mode = Mode.ALLOWLIST, timeWindows = listOf(broken)) }
        val r = eval(ConfigSnapshot(schedules = listOf(bl, al)), T, "x.y")
        assertEquals(listOf(1L, 2L), r.errors)
        assertEquals(listOf(ReasonSource.Schedule(1)), reasons(r.apps[T]))
        assertEquals(Decision.Allow, r.apps["x.y"]) // ALLOWLIST without a last good result does not block
        // ALLOWLIST keeps its last successful result.
        val good = al.copy(timeWindows = emptyList())
        val first = eval(ConfigSnapshot(schedules = listOf(good)), "x.y")
        assertTrue(first.apps.getValue("x.y").isBlock)
        val again = eval(ConfigSnapshot(schedules = listOf(al)), "x.y", state = first.state)
        assertTrue(again.apps.getValue("x.y").isBlock)
        // Status-only path fails closed too.
        val sv = engine.status(ConfigSnapshot(schedules = listOf(bl)), mon10, emptyMap())
        assertTrue(sv.statuses.first().error)
    }

    @Test
    fun cleanupAndNextInstant() {
        val paused = schedule(1, T) { copy(pausedUntil = at("2026-11-23T09:00"), manualUntil = at("2026-11-23T09:30")) }
        val qb = QuickBlock(active = true, until = at("2026-11-23T09:59"), apps = listOf(T))
        val r = eval(ConfigSnapshot(schedules = listOf(paused), quickBlock = qb), T)
        assertEquals(listOf(1L), r.cleanup.expiredPauses)
        assertEquals(listOf(1L), r.cleanup.expiredManual)
        assertTrue(r.cleanup.quickBlockExpired)
        assertNull(r.cleanup.strictEnd)
        val timer = eval(ConfigSnapshot(strict = strictTimer.copy(endsAt = at("2026-11-23T09:00"), endsAtElapsed = 1)), T)
        assertEquals(StrictEnd.TIMER_EXPIRED, timer.cleanup.strictEnd)
        val empty = eval(ConfigSnapshot(strict = StrictModeRow(state = StrictState.ON, method = StrictMethod.SCHEDULES)), T)
        assertEquals(StrictEnd.FOLLOWED_SET_EMPTY, empty.cleanup.strictEnd)
        assertEquals(StrictDisplayState.INACTIVE, empty.summary.strict)
        val win = schedule(1, T) { copy(timeWindows = listOf(win(WEEKDAYS, "11:00", "12:00"))) }
        val n = eval(ConfigSnapshot(schedules = listOf(win)), T).nextInstant
        assertEquals(PolicyInstant(at("2026-11-23T11:00"), InstantKind.WINDOW_START, 1), n)
    }

    @Test
    fun preparedSnapshotIsCached() {
        val snap = ConfigSnapshot(schedules = listOf(schedule(1, T)))
        assertSame(engine.prepare(snap), engine.prepare(snap))
        assertEquals("a/a.B", PreparedSnapshot.canonicalComponent("a/.B"))
        assertEquals("a/x.B", PreparedSnapshot.canonicalComponent("a/x.B"))
    }

    @Test
    fun statusViewForUi() {
        val s = schedule(1, T) { copy(timeWindows = listOf(win(MON, "09:00", "11:00")), strictFollowed = true) }
        val strict = StrictModeRow(state = StrictState.ON, method = StrictMethod.SCHEDULES, options = StrictOptions.LOCK_EDITING)
        val v = engine.status(ConfigSnapshot(schedules = listOf(s), strict = strict), mon10, emptyMap())
        assertEquals(StrictDisplayState.ACTIVE, v.strict)
        assertEquals(InstantKind.WINDOW_END, v.nextInstant?.kind)
        val later = engine.status(ConfigSnapshot(schedules = listOf(s), strict = strict), clock("2026-11-23T12:00"), emptyMap())
        assertEquals(StrictDisplayState.STANDBY, later.strict)
    }
}
