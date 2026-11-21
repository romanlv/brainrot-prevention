package dev.brainrotprevention.platform

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import dev.brainrotprevention.engine.EvaluationResult
import dev.brainrotprevention.model.BrowserEntry
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.ForegroundApp
import dev.brainrotprevention.model.WebTarget
import dev.brainrotprevention.platform.enforce.BrowserUrlReader
import dev.brainrotprevention.platform.enforce.Enforcer
import dev.brainrotprevention.platform.enforce.ForegroundSource
import dev.brainrotprevention.platform.log.Brp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Hosts the engine inputs and enforcement (§3 rule 2, §10): the foreground set, URL reading, the enforcer with
 * the block overlay, the status notification and the runtime receivers (§11.1). No foreground service.
 */
class BlockerAccessibilityService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private lateinit var g: PlatformGraph
    internal lateinit var foreground: ForegroundSource
    internal lateinit var urlReader: BrowserUrlReader
    private lateinit var enforcer: Enforcer
    private var pollJob: Job? = null
    private var receiversRegistered = false
    private var browserMap: Map<String, BrowserEntry> = emptyMap()
    private var browserSnapshot: ConfigSnapshot? = null

    override fun onCreate() {
        super.onCreate()
        savedState.performAttach()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        g = platform
        foreground = ForegroundSource(this)
        urlReader = BrowserUrlReader(this) { browsers() }
        enforcer = Enforcer(this, g)
    }

    private fun browsers(): Map<String, BrowserEntry> {
        val s = g.data.snapshots.snapshot.value
        if (s !== browserSnapshot && s != null) {
            browserSnapshot = s
            browserMap = s.browsers.associateBy { it.packageName }
        }
        if (browserMap.isEmpty()) browserMap = g.packages.browsers.associateBy { it.packageName }
        return browserMap
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        g.service = this
        g.health.onServiceConnected(true)
        Brp.event("service", "name" to "a11y", "state" to "connected")
        registerReceivers()
        g.sensors.start()
        g.alarms.invalidate()
        g.scope.launch {
            g.packages.refresh("service")
            g.health.refresh()
            g.engineHost.requestEvaluate("service-connected")
        }
        startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = g.scope.launch {
            val pm = getSystemService(PowerManager::class.java)
            while (isActive) {
                delay(POLL_MS)
                if (pm?.isInteractive != false && g.engineHost.needsPolling()) {
                    g.engineHost.requestEvaluate("poll")
                } else {
                    // Screen off or nothing armed: sleep until SCREEN_ON or the next evaluation changes the
                    // status (an alarm, a config change, a sensor), instead of waking every 300 ms.
                    g.engineHost.awaitPollingNeeded { pm?.isInteractive != false }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return
        val type = e.eventType
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg == "com.android.systemui" || pkg in g.packages.exempt && pkg != "com.android.settings") {
                // IME / System UI hints are ignored; the launcher only needs the URL-reader path below.
            } else {
                foreground.hint(pkg, e.className?.toString())
                g.engineHost.requestEvaluate("hint")
            }
        }
        if (pkg in browsers()) {
            if (urlReader.onEvent(e)) g.engineHost.requestEvaluate("url")
        }
    }

    override fun onInterrupt() {}

    // ---- engine host hooks (engine dispatcher) -----------------------------------------------

    internal fun foregroundSet(): List<ForegroundApp> = foreground.current()

    internal fun webTargets(fg: List<ForegroundApp>): List<WebTarget> {
        // Poll-time read for browsers in front, so a URL bar that changed without an event is still seen.
        val map = browsers()
        for (a in fg) map[a.packageName]?.let { urlReader.extract(a.packageName, it) }
        return urlReader.targets(fg)
    }

    internal fun urlBarMissing(): Set<String> = urlReader.urlBarMissing()

    internal suspend fun enforce(r: EvaluationResult, fg: List<ForegroundApp>) {
        val snapshot = g.data.snapshots.current()
        enforcer.apply(r, fg, snapshot)
    }

    internal fun onEvaluated(@Suppress("UNUSED_PARAMETER") r: EvaluationResult, @Suppress("UNUSED_PARAMETER") s: ConfigSnapshot) {}

    internal fun hideOverlay(reason: String) = enforcer.hideAll(reason)

    // ---- runtime receivers (§11.1) -----------------------------------------------------------

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart
            Brp.event("package", "action" to intent.action, "pkg" to pkg)
            g.scope.launch {
                g.packages.refresh("package")
                g.engineHost.requestEvaluate("package")
            }
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    urlReader.clear()
                    g.engineHost.requestEvaluate("screen-off")
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    g.engineHost.wakePolling()
                    g.engineHost.requestEvaluate("screen-on")
                }
                // start() re-registers the Wi-Fi callback and the geofences (once).
                LocationManager.PROVIDERS_CHANGED_ACTION -> g.sensors.start()
                Intent.ACTION_SHUTDOWN -> {
                    val pending = goAsync()
                    g.scope.launch {
                        try {
                            g.engineHost.confined { g.engineHost.tamperCheckLocked("shutdown") }
                        } finally {
                            pending.finish()
                        }
                    }
                }
            }
        }
    }

    private fun registerReceivers() {
        if (receiversRegistered) return
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, pkgFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val sysFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        ContextCompat.registerReceiver(this, systemReceiver, sysFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiversRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiversRegistered) return
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { unregisterReceiver(systemReceiver) }
        receiversRegistered = false
    }

    private fun disconnect() {
        if (g.service !== this) return
        pollJob?.cancel()
        unregisterReceivers()
        enforcer.release("service-stop")
        g.service = null
        g.health.onServiceConnected(false)
        g.notifier.cancelStatus()
        Brp.event("service", "name" to "a11y", "state" to "disconnected")
        g.engineHost.requestEvaluate("service-disconnected")
        runCatching { g.health.refresh() }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    companion object {
        const val POLL_MS = 300L
    }
}
