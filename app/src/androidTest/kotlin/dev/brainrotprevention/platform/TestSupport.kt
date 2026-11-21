package dev.brainrotprevention.platform

import android.app.UiAutomation
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry

/**
 * `am instrument` force-stops the app, and Android then lists the accessibility service under "Crashed services"
 * and doesn't rebind it. Re-writing the enabled-services setting (as the shell) rebinds it. UiAutomation must be
 * obtained with FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES, or it suppresses our service itself (§14.4).
 */
object ServiceRebind {
    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    fun ensure(g: PlatformGraph, timeoutMs: Long = 20_000): Boolean {
        if (waitFor(2_000) { g.service != null }) return true
        val comp = "${g.app.packageName}/${BlockerAccessibilityService::class.java.name}"
        repeat(3) {
            shell("settings delete secure enabled_accessibility_services")
            SystemClock.sleep(1_000)
            shell("settings put secure enabled_accessibility_services $comp")
            shell("settings put secure accessibility_enabled 1")
            if (waitFor(timeoutMs / 3) { g.service != null }) return true
        }
        return g.service != null
    }

    fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            if (cond()) return true
            SystemClock.sleep(100)
        }
        return cond()
    }
}
