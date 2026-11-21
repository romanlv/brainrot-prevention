package dev.brainrotprevention.platform.debug

import android.content.Context
import android.provider.Settings
import dev.brainrotprevention.model.OffsetPolicyClock
import dev.brainrotprevention.model.SystemTime

/**
 * Persists the debug clock offsets (§14.3 item 1): `wallOffset` survives restarts and reboots; `elapsedOffset`
 * survives process restarts but is reset to 0 on boot (detected through `Settings.Global.BOOT_COUNT`).
 */
class DebugClockStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("debug_clock", Context.MODE_PRIVATE)

    private fun bootCount(): Int = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    fun create(system: SystemTime): OffsetPolicyClock {
        val wall = prefs.getLong(WALL, 0)
        val sameBoot = prefs.getInt(BOOT, -2) == bootCount()
        val elapsed = if (sameBoot) prefs.getLong(ELAPSED, 0) else 0
        return OffsetPolicyClock(system, wallOffset = wall, elapsedOffset = elapsed).also { save(it) }
    }

    fun save(clock: OffsetPolicyClock) {
        prefs.edit()
            .putLong(WALL, clock.wallOffset)
            .putLong(ELAPSED, clock.elapsedOffset)
            .putInt(BOOT, bootCount())
            .commit()
    }

    private companion object {
        const val WALL = "wallOffset"
        const val ELAPSED = "elapsedOffset"
        const val BOOT = "bootCount"
    }
}
