package dev.brainrotprevention.ui

import android.app.Activity
import android.graphics.drawable.Drawable
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotprevention.data.DataGraph
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.engine.ActivationResult
import dev.brainrotprevention.engine.StrictActivation
import dev.brainrotprevention.engine.StrictPolicy
import dev.brainrotprevention.model.PolicyClock
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.SensorState
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.model.TimeWindow
import dev.brainrotprevention.model.Weekdays
import dev.brainrotprevention.ui.nav.BrpUi
import dev.brainrotprevention.ui.nav.Routes
import dev.brainrotprevention.ui.platform.AppsPort
import dev.brainrotprevention.ui.platform.EnginePort
import dev.brainrotprevention.ui.platform.FixResult
import dev.brainrotprevention.ui.platform.HealthPort
import dev.brainrotprevention.ui.platform.LocalStrictPort
import dev.brainrotprevention.ui.platform.UiApp
import dev.brainrotprevention.ui.platform.UiGrant
import dev.brainrotprevention.ui.platform.UiGrantStatus
import dev.brainrotprevention.ui.platform.UiHealth
import dev.brainrotprevention.ui.platform.UiPlatform
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue

/** Mon 2026-11-23 10:00 UTC. */
const val T0 = 1_795_428_000_000L

class FakeClock(@Volatile var wall: Long = T0, @Volatile var el: Long = 5_000_000L) : PolicyClock {
    override fun now() = wall

    override fun zone(): ZoneId = ZoneId.of("UTC")

    override fun elapsed() = el
}

class FakeEngine : EnginePort {
    var evaluations = 0

    override fun requestEvaluate() {
        evaluations++
    }

    override fun sensors() = SensorState()
}

class FakeHealth(var health: UiHealth = allGranted(), var openResult: FixResult = FixResult.OPENED) : HealthPort {
    val opened = mutableListOf<UiGrant>()

    /** How often App info (the restricted-settings path) was opened. */
    var appDetailsOpened = 0

    /**
     * When set, decides "Settings guard active" (§9.3) instead of [openResult]: true → LOCKED_BY_STRICT and nothing
     * is opened. [TestEnv] wires it to the real strict row + displayed state.
     */
    var settingsGuard: (() -> Boolean)? = null

    override fun check() = health

    private fun result(): FixResult = settingsGuard?.let { if (it()) FixResult.LOCKED_BY_STRICT else FixResult.OPENED } ?: openResult

    override fun open(activity: Activity, grant: UiGrant): FixResult {
        val r = result()
        if (r == FixResult.OPENED) opened += grant
        return r
    }

    override fun runtimePermission(grant: UiGrant): String? = null

    override fun openAppDetails(activity: Activity): FixResult {
        val r = result()
        if (r == FixResult.OPENED) appDetailsOpened++
        return r
    }

    companion object {
        fun allGranted() = UiHealth(
            UiGrant.entries.map {
                UiGrantStatus(it, true, it == UiGrant.ACCESSIBILITY || it == UiGrant.USAGE_ACCESS)
            }
        )
    }
}

class FakeApps(
    val apps: List<UiApp> = listOf(
        UiApp("com.example.a", "Alpha"),
        UiApp("com.example.b", "Beta"),
        UiApp("com.instagram.android", "Instagram"),
    ),
    override val appDomains: Map<String, List<String>> = mapOf("com.instagram.android" to listOf("instagram.com")),
) : AppsPort {
    override suspend fun launchable() = apps

    override fun icon(pkg: String): Drawable? = null

    override fun label(pkg: String) = apps.firstOrNull { it.packageName == pkg }?.label ?: pkg

    override suspend fun unsupportedBrowsers() = emptyList<UiApp>()

    override suspend fun allowlistEssentials() = listOf(UiApp("com.android.documentsui", "Files"))

    override fun currentSsid(): String? = "HomeNet"

    override suspend fun currentLocation(): Pair<Double, Double>? = 50.0 to 14.0
}

/** An in-memory data graph + fake ports; [close] after each test. */
class TestEnv {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val dir: File = Files.createTempDirectory(context.cacheDir.toPath(), "brp-ui").toFile()
    val clock = FakeClock()
    val graph: DataGraph = DataGraph.inMemory(context, clock, dir)
    val engine = FakeEngine()
    val health = FakeHealth()
    val apps = FakeApps()
    val strictPort = LocalStrictPort(graph, facts = { LocalStrictPort.allGranted(admin = false) })
    val deps = UiDeps(graph, UiPlatform(engine, health, apps, strictPort), tickMs = 60_000)

    fun close() {
        graph.close()
        dir.deleteRecursively()
    }

    /**
     * The Settings guard computed from the real strict row and displayed state, as the platform decides it (§9.3):
     * ON with BLOCK_SETTINGS and displayed ACTIVE.
     */
    fun useRealSettingsGuard() {
        health.settingsGuard = {
            runBlocking {
                val row = graph.strict.get()
                val display = graph.strictGate.display(graph.snapshots.load())
                row.isOn && row.has(StrictOptions.BLOCK_SETTINGS) && display == StrictDisplayState.ACTIVE
            }
        }
    }

    fun <T> ok(r: WriteResult<T>): T {
        assertTrue("expected Ok, got ${r.describe()}", r is WriteResult.Ok)
        return (r as WriteResult.Ok).value
    }

    fun seed(s: Schedule): Long = runBlocking { ok(graph.schedules.save(s)) }

    fun schedules(): List<Schedule> = runBlocking { graph.schedules.getAll() }

    /** Strict Mode ON with TIMER 1 h and [options], written as the platform would. */
    fun strictOn(options: Int = StrictOptions.LOCK_EDITING): StrictModeRow = runBlocking {
        val req = StrictActivation(StrictMethod.TIMER, options, durationMs = 3_600_000)
        val r = StrictPolicy.activate(
            req,
            LocalStrictPort.allGranted(admin = true),
            graph.schedules.getAll(),
            graph.strict.get(),
            clock.wall,
            clock.el
        )
        val row = (r as ActivationResult.Activated).row
        graph.strict.writePolicyResult(row)
        row
    }

    companion object {
        fun workday(name: String, apps: List<String> = listOf("com.example.a")) = Schedule(
            name = name,
            icon = "💼",
            apps = apps,
            timeWindows = listOf(TimeWindow(days = Weekdays.WEEKDAYS, startMin = 540, endMin = 1020)),
        )
    }
}

fun ComposeContentTestRule.show(env: TestEnv, route: String = Routes.HOME) {
    setContent { BrpUi(env.deps, startRoute = route) }
}

fun ComposeContentTestRule.waitForTag(tag: String, timeoutMs: Long = 10_000) {
    waitUntil(timeoutMs) { onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
}

fun ComposeContentTestRule.tap(tag: String): SemanticsNodeInteraction {
    waitForTag(tag)
    return onNodeWithTag(tag, useUnmergedTree = true).performClick()
}

/** Scrolls the editor/settings column to [tag] first. */
fun ComposeContentTestRule.scrollTap(tag: String): SemanticsNodeInteraction {
    waitForTag(tag)
    return onNodeWithTag(tag).performScrollTo().performClick()
}
