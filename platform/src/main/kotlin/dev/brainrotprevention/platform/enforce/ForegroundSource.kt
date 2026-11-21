package dev.brainrotprevention.platform.enforce

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Build
import android.view.accessibility.AccessibilityWindowInfo
import dev.brainrotprevention.model.ForegroundApp
import dev.brainrotprevention.platform.time.SystemTimeAndroid
import java.util.concurrent.ConcurrentHashMap

/**
 * The foreground set (§10.1): (a) resumed activities from UsageStats over the last 60 s of SystemTime (6 h on a
 * cold start), (b) the packages of every application window from `getWindows()`, plus window-state hints.
 */
class ForegroundSource(private val service: AccessibilityService) {
    private var coldStart = true

    /** pkg → (component class, SystemTime when seen) from TYPE_WINDOW_STATE_CHANGED events. */
    private val hints = ConcurrentHashMap<String, Pair<String, Long>>()

    /** Packages seen in the last computed set (for debounce bookkeeping). */
    @Volatile var lastPackages: Set<String> = emptySet()
        private set

    fun hint(pkg: String, cls: String?) {
        if (cls == null) return
        // Only activity-like classes are useful components; views (FrameLayout…) carry no component.
        if (cls.startsWith("android.widget.") || cls.startsWith("android.view.") || cls.startsWith("android.app.Dialog")) return
        hints[pkg] = cls to SystemTimeAndroid.elapsedRealtime()
    }

    fun current(): List<ForegroundApp> {
        val out = LinkedHashMap<String, ForegroundApp>()
        val usage = resumedFromUsage()
        for ((pkg, comp) in usage) out[pkg] = ForegroundApp(pkg, comp)
        for (w in windowsSafe()) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = try {
                w.root
            } catch (_: RuntimeException) {
                null
            } ?: continue
            val pkg = root.packageName?.toString()
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                root.recycle()
            }
            if (pkg == null) continue
            val pip = w.isInPictureInPictureMode
            val existing = out[pkg]
            val comp = existing?.component ?: hints[pkg]?.first?.let { "$pkg/$it" }
            out[pkg] = ForegroundApp(pkg, comp, secondary = pip && existing == null)
        }
        lastPackages = out.keys.toSet()
        return out.values.toList()
    }

    fun windowsSafe(): List<AccessibilityWindowInfo> = try {
        service.windows ?: emptyList()
    } catch (_: RuntimeException) {
        emptyList()
    }

    /** pkg → "pkg/cls" of the resumed activities. */
    private fun resumedFromUsage(): Map<String, String> {
        val usm = service.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val now = SystemTimeAndroid.currentTimeMillis()
        val span = if (coldStart) 6 * 3_600_000L else 60_000L
        coldStart = false
        val events = try {
            usm.queryEvents(now - span, now + 1_000)
        } catch (_: RuntimeException) {
            return emptyMap()
        } ?: return emptyMap()
        val resumed = LinkedHashMap<String, String>() // component → pkg
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            val comp = "$pkg/${e.className}"
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    resumed.remove(comp)
                    resumed[comp] = pkg
                }
                UsageEvents.Event.ACTIVITY_PAUSED, ACTIVITY_STOPPED -> resumed.remove(comp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> resumed.clear()
            }
        }
        val out = LinkedHashMap<String, String>()
        for ((comp, pkg) in resumed) out[pkg] = comp // later resumes win
        return out
    }

    companion object {
        private const val ACTIVITY_STOPPED = 23
    }
}
