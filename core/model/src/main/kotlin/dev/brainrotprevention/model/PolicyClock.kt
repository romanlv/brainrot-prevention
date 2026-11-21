package dev.brainrotprevention.model

import java.time.Instant
import java.time.ZoneId

/** Policy time source (§3 rule 5). Drives every policy rule. */
interface PolicyClock {
    /** Wall clock, epoch ms. */
    fun now(): Long

    fun zone(): ZoneId

    /** Policy-elapsed (monotonic) ms. */
    fun elapsed(): Long

    /** Converts a policy instant to a real `System.currentTimeMillis` instant (for alarms, snooze). */
    fun toSystemMillis(t: Long): Long = t

    fun read(): ClockReading = ClockReading(now(), zone(), elapsed())
}

/** Release clock: policy time == system time. */
class SystemPolicyClock(
    private val system: SystemTime,
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
) : PolicyClock {
    override fun now(): Long = system.currentTimeMillis()

    override fun zone(): ZoneId = zoneProvider()

    override fun elapsed(): Long = system.elapsedRealtime()
}

/**
 * Debug clock (§14.3 item 1): `wallOffset` (persisted by the platform) and `elapsedOffset` (reset on boot).
 */
class OffsetPolicyClock(
    private val system: SystemTime,
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    @Volatile var wallOffset: Long = 0,
    @Volatile var elapsedOffset: Long = 0,
) : PolicyClock {
    override fun now(): Long = system.currentTimeMillis() + wallOffset

    override fun zone(): ZoneId = zoneProvider()

    override fun elapsed(): Long = system.elapsedRealtime() + elapsedOffset

    override fun toSystemMillis(t: Long): Long = t - wallOffset

    /** `clock-advance`: time passes; wall and elapsed both move. */
    fun advance(ms: Long) {
        require(ms > 0) { "advance must be > 0" }
        wallOffset += ms
        elapsedOffset += ms
    }

    /** `clock-jump`: only wall time moves. */
    fun jump(ms: Long) {
        wallOffset += ms
    }

    /** `clock-set`: wall-only jump to [at]. */
    fun setWall(at: Instant) {
        wallOffset = at.toEpochMilli() - system.currentTimeMillis()
    }

    /** `clock-real`. */
    fun real() {
        wallOffset = 0
        elapsedOffset = 0
    }
}
