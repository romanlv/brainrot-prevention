package dev.brainrotprevention.platform.time

import android.os.SystemClock

/**
 * The real, never-overridden Android clocks (SPEC §3 rule 5, "SystemTime"). This file is one of the
 * two places allowed to read the wall clock directly (G0 forbidden-time-call rule).
 */
object SystemTimeAndroid {
    fun currentTimeMillis(): Long = System.currentTimeMillis()

    fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    fun uptimeMillis(): Long = SystemClock.uptimeMillis()
}
