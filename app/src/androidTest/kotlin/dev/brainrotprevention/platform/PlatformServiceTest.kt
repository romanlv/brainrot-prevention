package dev.brainrotprevention.platform

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.testing.Milestone
import java.util.regex.Pattern
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G3 (SPEC §14.4): the accessibility service reads the fixture's URL bar, the block overlay renders, and the
 * service stays connected while UiDevice is in use. Runs in the app process (after grant.sh).
 */
@RunWith(AndroidJUnit4::class)
class PlatformServiceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext.applicationContext
    private val g: PlatformGraph get() = ctx.platform
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        // Keep our accessibility service connected while UiAutomation is in use (§14.4).
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        device = UiDevice.getInstance(inst)
        device.pressHome()
        assertTrue("accessibility service not connected", ServiceRebind.ensure(g))
        runBlocking { g.data.wipe() }
    }

    @After
    fun tearDown() {
        runBlocking {
            g.data.wipe()
            g.resetForDebug()
        }
        device.pressHome()
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            if (cond()) return true
            SystemClock.sleep(100)
        }
        return cond()
    }

    private fun start(pkg: String, cls: String, extras: Intent.() -> Unit = {}) {
        val i = Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(extras)
        ctx.startActivity(i)
    }

    @Test
    @Milestone("M3")
    fun serviceStaysConnectedWhileUiDeviceIsUsed() {
        device.pressHome()
        device.waitForIdle()
        device.findObjects(By.pkg(Pattern.compile(".*")))
        device.pressRecentApps()
        device.waitForIdle()
        device.pressHome()
        SystemClock.sleep(1_000)
        assertNotNull("service disconnected while UiDevice was used", g.service)
        assertTrue(g.health.accessibilityConnected())
    }

    @Test
    @Milestone("M3")
    fun overlayRenders() {
        runBlocking {
            assertTrue(g.data.quickBlock.save(QuickBlock(apps = listOf(TARGET))) is WriteResult.Ok)
            assertTrue(g.data.quickBlock.start(null) is WriteResult.Ok)
            g.engineHost.evaluateNow("test")
        }
        start(TARGET, "$TARGET.MainActivity")
        val close = device.wait(Until.findObject(By.res(Pattern.compile("(.*:id/)?block_close"))), 5_000)
        assertNotNull("block overlay with block_close did not render", close)
        assertTrue(device.hasObject(By.text("Blocked")))
        close.click()
        assertTrue("overlay not dismissed", device.wait(Until.gone(By.res(Pattern.compile("(.*:id/)?block_close"))), 5_000))
        assertTrue("service disconnected", g.service != null)
    }

    @Test
    @Milestone("M6")
    fun serviceReadsFixtureUrlBar() {
        // A web rule keeps the browser judged; the URL itself is allowed.
        start(BROWSER, "$BROWSER.MainActivity") { putExtra("go", "https://example.org/g3") }
        val ok = waitFor(8_000) {
            runBlocking { g.engineHost.evaluateNow("test") }
            g.engineHost.lastResult?.pages?.any { it.target.browserPackage == BROWSER && it.target.url.contains("example.org/g3") } == true
        }
        assertTrue("the URL bar of the fixture browser was not read", ok)
        val page = g.engineHost.lastResult!!.pages.first { it.target.browserPackage == BROWSER }
        assertEquals("example.org", page.url.hostAscii)
        assertTrue(page.target.reliable)
    }

    /**
     * Review round 2, adversarial 4: a Google Translate proxy URL read from a real URL bar maps back to the original
     * host, so a DOMAIN rule for it matches. (No blocking rule is active, so the enforcer doesn't redirect the page
     * before the read is inspected.)
     */
    @Test
    @Milestone("M6")
    fun translateProxyUrlMatchesTheOriginalDomain() {
        start(BROWSER, "$BROWSER.MainActivity") { putExtra("go", "https://old-reddit-com.translate.goog/r/all?_x_tr_sl=auto") }
        val ok = waitFor(8_000) {
            runBlocking { g.engineHost.evaluateNow("test") }
            g.engineHost.lastResult?.pages?.any {
                it.target.browserPackage == BROWSER && it.url.hostAscii == "old-reddit-com.translate.goog"
            } ==
                true
        }
        assertTrue("the translate.goog URL was not read", ok)
        val page = g.engineHost.lastResult!!.pages.first { it.target.browserPackage == BROWSER }
        assertEquals(listOf("old.reddit.com"), page.url.proxiedHosts)
        val rule = dev.brainrotprevention.model.WebRule(kind = dev.brainrotprevention.model.WebRuleKind.DOMAIN, value = "reddit.com")
        assertTrue(dev.brainrotprevention.engine.UrlMatcher.matches(rule, page.url))
    }

    private companion object {
        const val TARGET = "dev.brainrotprevention.fixture.target"
        const val BROWSER = "dev.brainrotprevention.fixture.browser"
    }
}
