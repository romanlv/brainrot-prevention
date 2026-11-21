package dev.brainrotprevention.platform

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.platform.log.Brp
import dev.brainrotprevention.testing.Milestone
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Review round 1 (platform): media stays paused while the package stays blocked, also after HOME (blocker 1), and a
 * blocked app that is only in picture-in-picture is paused and covered without a HOME/overlay loop (major 1).
 */
@RunWith(AndroidJUnit4::class)
class MediaPipTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = inst.targetContext.applicationContext
    private val g: PlatformGraph get() = ctx.platform
    private lateinit var device: UiDevice
    private val events = Collections.synchronizedList(ArrayList<String>())
    private lateinit var previousSink: (String) -> Unit

    @Before
    fun setUp() {
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        device = UiDevice.getInstance(inst)
        device.pressHome()
        assertTrue("accessibility service not connected", ServiceRebind.ensure(g))
        runBlocking { g.data.wipe() }
        previousSink = Brp.sink
        Brp.sink = { line ->
            events += line
            previousSink(line)
        }
    }

    @After
    fun tearDown() {
        Brp.sink = previousSink
        runBlocking {
            g.data.wipe()
            g.resetForDebug()
        }
        device.executeShellCommand("am force-stop $TARGET")
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

    private fun controller(): MediaController? {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        return msm.getActiveSessions(ComponentName(ctx, NotificationBlockerService::class.java)).firstOrNull { it.packageName == TARGET }
    }

    private fun state(): Int? = controller()?.playbackState?.state

    private fun startPip(vararg extras: Pair<String, Boolean>) {
        val i = Intent().setClassName(TARGET, "$TARGET.PipActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for ((k, v) in extras) i.putExtra(k, v)
        ctx.startActivity(i)
    }

    private fun blockTarget() = runBlocking {
        assertTrue(g.data.quickBlock.save(QuickBlock(apps = listOf(TARGET))) is WriteResult.Ok)
        assertTrue(g.data.quickBlock.start(null) is WriteResult.Ok)
        g.engineHost.evaluateNow("test")
    }

    private fun homeEnforcements(): Int = synchronized(events) {
        events.count { it.contains("\"ev\":\"enforced\"") && it.contains("\"how\":\"HOME\"") && it.contains(TARGET) }
    }

    @Test
    @Milestone("M7")
    fun mediaIsRePausedAfterHomeWhileStillBlocked() {
        startPip("noPip" to true)
        assertTrue("fixture media session not found", waitFor(5_000) { state() == PlaybackState.STATE_PLAYING })
        blockTarget()
        assertTrue("not paused on enforcement", waitFor(5_000) { state() == PlaybackState.STATE_PAUSED })
        assertTrue("not sent home", waitFor(5_000) { homeEnforcements() >= 1 })
        // The app has left the foreground set; give the engine a few evaluations without it.
        SystemClock.sleep(2_000)
        assertTrue(g.engineHost.lastResult?.apps?.containsKey(TARGET) != true)
        // Play from outside the app (notification, headset, lock-screen player): re-paused.
        controller()!!.transportControls.play()
        assertTrue(
            "background playback was not re-paused",
            waitFor(5_000) {
                synchronized(events) { events.any { it.contains("\"action\":\"re-pause\"") } } &&
                    state() == PlaybackState.STATE_PAUSED
            },
        )
        // Once the block ends, playback is left alone.
        runBlocking {
            g.data.quickBlock.stop()
            g.engineHost.evaluateNow("test")
        }
        controller()!!.transportControls.play()
        SystemClock.sleep(1_500)
        assertEquals(PlaybackState.STATE_PLAYING, state())
    }

    @Test
    @Milestone("M7")
    fun pipOnlyMemberIsPausedAndCoveredWithoutAHomeLoop() {
        startPip("enterPip" to true)
        val ua = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        assertTrue(
            "fixture did not enter PiP",
            waitFor(8_000) {
                ua.windows.any { w -> w.isInPictureInPictureMode && runCatching { w.root?.packageName }.getOrNull() == TARGET }
            },
        )
        assertTrue(waitFor(5_000) { state() == PlaybackState.STATE_PLAYING })
        events.clear()
        blockTarget()
        assertTrue("PiP media not paused", waitFor(5_000) { state() == PlaybackState.STATE_PAUSED })
        // §10.2 step 3: the PiP window is dismissed, or covered when it can't be.
        assertTrue(
            "PiP window neither dismissed nor covered",
            waitFor(5_000) {
                synchronized(events) { events.any { it.contains("\"pip\":true") } } ||
                    ua.windows.none { w -> w.isInPictureInPictureMode && runCatching { w.root?.packageName }.getOrNull() == TARGET }
            },
        )
        // A PiP window can't be sent home: over 8 s there is no HOME and no full-screen block for it.
        SystemClock.sleep(8_000)
        assertEquals("HOME sent for a PiP-only member", 0, homeEnforcements())
        val shownForApp = synchronized(events) { events.count { it.contains("\"ev\":\"overlay\"") && it.contains("\"reason\":\"app\"") } }
        assertEquals("full-screen block shown for a PiP-only member", 0, shownForApp)
        // Play again (inside the PiP window, or in the background after a dismiss): re-paused.
        val c = controller()
        if (c != null) {
            c.transportControls.play()
            assertTrue("PiP playback not re-paused", waitFor(5_000) { state() == PlaybackState.STATE_PAUSED })
        }
        assertNotNull(g.service)
    }

    private companion object {
        const val TARGET = "dev.brainrotprevention.fixture.target"
    }
}
