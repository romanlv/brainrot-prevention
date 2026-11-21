package dev.brainrotprevention.model

/** Real time source (§3 rule 5). Never overridden. */
interface SystemTime {
    fun currentTimeMillis(): Long

    fun elapsedRealtime(): Long

    fun uptimeMillis(): Long
}

/** JVM implementation (tests, tools). Android uses `SystemClock` in a file named `SystemTime*.kt`. */
object JvmSystemTime : SystemTime {
    private val origin = System.nanoTime()

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun elapsedRealtime(): Long = (System.nanoTime() - origin) / 1_000_000

    override fun uptimeMillis(): Long = elapsedRealtime()
}
