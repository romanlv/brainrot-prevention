package dev.brainrotprevention.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.brainrotprevention.engine.ActivationRefusal
import dev.brainrotprevention.engine.StrictActivation
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.testing.Milestone
import dev.brainrotprevention.testing.NoGrant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** G3: the platform health checks see the grants of §11.3, with and without `grant.sh`. */
@RunWith(AndroidJUnit4::class)
class PermissionHealthTest {
    private val g: PlatformGraph get() = InstrumentationRegistry.getInstrumentation().targetContext.platform

    @Test
    @NoGrant
    @Milestone("M3")
    fun reportsMissingGrants() {
        val r = g.health.check()
        assertFalse(r[Grant.ACCESSIBILITY]!!.granted)
        assertFalse(r[Grant.USAGE_ACCESS]!!.granted)
        assertFalse(r.ok)
        assertFalse(g.health.accessibilityEnabled())
    }

    @Test
    @NoGrant
    @Milestone("M8")
    fun strictActivationRefusedWithoutService() {
        val refusal = runBlocking { g.strict.preflight(StrictActivation(StrictMethod.TIMER, 1, durationMs = 3_600_000)) }
        assertEquals(ActivationRefusal.ACCESSIBILITY_NOT_CONNECTED, refusal)
    }

    @Test
    @Milestone("M3")
    fun reportsGrants() {
        assertTrue("accessibility service not connected", ServiceRebind.ensure(g))
        val r = g.health.check()
        assertTrue(r[Grant.ACCESSIBILITY]!!.granted)
        assertTrue(r[Grant.USAGE_ACCESS]!!.granted)
        assertTrue(r[Grant.NOTIFICATION_LISTENER]!!.granted)
        assertTrue(r[Grant.POST_NOTIFICATIONS]!!.granted)
        assertTrue(r.ok)
        assertFalse(r.settingsLockedByStrict)
    }

    /**
     * Review round 2, adversarial 2: the swipe-up gesture and keyboard shortcuts (API 36+ keys) count as "in the
     * accessibility shortcut", so Strict activation is refused while one targets us.
     *
     * Phone bug (2026-11-21): on user builds some of these keys are `@hide` without `@Readable` and reading them
     * throws SecurityException, which crashed the process (and the accessibility service) when Strict Mode was
     * started. Every key must be safe to probe; a key the app may read must be detected, an unreadable one is
     * treated as "not bound".
     */
    @Test
    @Milestone("M8")
    fun everyShortcutKeyIsSafeAndDetectedWhenReadable() {
        val comp = "${g.app.packageName}/${BlockerAccessibilityService::class.java.name}"
        val readable = mutableListOf<String>()
        for (key in StrictActivator.SHORTCUT_KEYS) {
            val canRead = try {
                android.provider.Settings.Secure.getString(g.app.contentResolver, key)
                true
            } catch (_: SecurityException) {
                false
            }
            if (canRead) readable += key
            try {
                ServiceRebind.shell("settings put secure $key $comp")
                if (canRead) {
                    assertTrue(key, ServiceRebind.waitFor(3_000) { g.strict.inAccessibilityShortcut() })
                } else {
                    assertFalse(key, g.strict.inAccessibilityShortcut())
                }
            } finally {
                ServiceRebind.shell("settings delete secure $key")
            }
            assertTrue(key, ServiceRebind.waitFor(3_000) { !g.strict.inAccessibilityShortcut() })
        }
        android.util.Log.i("BRP", "shortcut keys readable=$readable")
    }
}
