package dev.brainrotprevention.platform

import dev.brainrotprevention.engine.QUICK_BLOCK_ID
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.QuickBlock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review round 2, diff major 1: an app added to a Quick Block that already hides notifications sweeps again. */
class NotificationSweepTest {
    private val key = NotificationBlockerService.SweepKey

    @Test
    fun configEditWhileHidingSweepsAgain() {
        val before = ConfigSnapshot(quickBlock = QuickBlock(active = true, blockNotifications = true, apps = listOf("com.a")))
        val after = before.copy(quickBlock = before.quickBlock.copy(apps = listOf("com.a", "com.whatsapp")))
        val hiding = setOf(QUICK_BLOCK_ID)
        // First evaluation.
        assertTrue(key.changed(null, null, hiding, before))
        // Same snapshot instance, same set (the 300 ms poll): no binder fetch.
        assertFalse(key.changed(hiding, before, hiding, before))
        // The set is unchanged but the config was edited: sweep.
        assertTrue(key.changed(hiding, before, hiding, after))
        // Nothing hides: a config edit doesn't fetch anything.
        assertFalse(key.changed(emptySet(), before, emptySet(), after))
        // The set changed.
        assertTrue(key.changed(hiding, before, emptySet(), before))
    }
}
