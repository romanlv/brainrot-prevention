package dev.brainrotprevention.platform

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review round 1, blocker 2: an exception in `g.scope` must not reach the thread's uncaught-exception handler. */
class ScopeTest {
    @Test
    fun uncaughtExceptionsGoToTheHandlerAndTheScopeKeepsWorking() {
        val uncaught = AtomicReference<Throwable?>(null)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> uncaught.set(t) }
        try {
            val seen = AtomicReference<Throwable?>(null)
            val errors = CountDownLatch(1)
            val scope = PlatformGraph.newScope { t ->
                seen.set(t)
                errors.countDown()
            }
            scope.launch { throw java.io.IOException("disk full") }
            assertTrue(errors.await(5, TimeUnit.SECONDS))
            assertEquals("disk full", seen.get()?.message)
            // The supervisor scope still runs new work afterwards.
            val ok = CountDownLatch(1)
            scope.launch { ok.countDown() }
            assertTrue(ok.await(5, TimeUnit.SECONDS))
            Thread.sleep(200)
            assertNull(uncaught.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
