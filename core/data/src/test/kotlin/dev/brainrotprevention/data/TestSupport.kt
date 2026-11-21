package dev.brainrotprevention.data

import androidx.test.core.app.ApplicationProvider
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.PolicyClock
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.StrictState
import dev.brainrotprevention.model.TimeWindow
import dev.brainrotprevention.model.UsageLimit
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind
import dev.brainrotprevention.model.Weekdays
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before

/** Mon 2026-11-23 10:00 UTC. */
const val T0 = 1_795_428_000_000L

class FakeClock(var wall: Long = T0, var el: Long = 1_000_000L) : PolicyClock {
    override fun now() = wall

    override fun zone(): ZoneId = ZoneId.of("UTC")

    override fun elapsed() = el
}

abstract class GraphTest {
    lateinit var dir: File
    lateinit var clock: FakeClock
    lateinit var g: DataGraph

    @Before
    fun setUpGraph() {
        dir = Files.createTempDirectory("brp-data").toFile()
        clock = FakeClock()
        g = DataGraph.inMemory(ApplicationProvider.getApplicationContext(), clock, dir)
    }

    @After
    fun tearDownGraph() {
        g.close()
        dir.deleteRecursively()
    }

    fun <T> WriteResult<T>.ok(): T {
        assertTrue("expected Ok, got ${describe()}", this is WriteResult.Ok)
        return (this as WriteResult.Ok).value
    }

    fun schedule(name: String = "Work", apps: List<String> = listOf("com.example.a")) = Schedule(
        name = name,
        icon = "⛔",
        timeWindows = listOf(TimeWindow(days = Weekdays.ALL, startMin = 540, endMin = 1020)),
        limits = listOf(UsageLimit(kind = LimitKind.TIME, period = Period.DAILY, allowance = 30 * 60_000L)),
        apps = apps,
        webRules = listOf(WebRule(kind = WebRuleKind.DOMAIN, value = "example.com")),
    )

    /** Strict Mode ON (TIMER, LOCK_EDITING): displayed ACTIVE, so every weakening edit is locked. */
    fun strictOn(options: Int = StrictOptions.LOCK_EDITING) = StrictModeRow(
        state = StrictState.ON,
        options = options,
        method = StrictMethod.TIMER,
        endsAt = T0 + 3_600_000,
        endsAtElapsed = clock.el + 3_600_000,
        activatedAt = T0,
    )

    companion object {
        /** scripts/e2e/seeds, relative to the module directory (the unit-test working dir). */
        val seedsDir: File = File("../../scripts/e2e/seeds")
    }
}
