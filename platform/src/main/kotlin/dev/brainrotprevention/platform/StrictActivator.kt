package dev.brainrotprevention.platform

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.engine.ActivationRefusal
import dev.brainrotprevention.engine.ActivationResult
import dev.brainrotprevention.engine.EmergencyResult
import dev.brainrotprevention.engine.StrictActivation
import dev.brainrotprevention.engine.StrictFacts
import dev.brainrotprevention.engine.StrictPolicy
import dev.brainrotprevention.engine.UnlockRefusal
import dev.brainrotprevention.engine.UnlockResult
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.platform.log.Brp
import dev.brainrotprevention.platform.time.SystemTimeAndroid
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface ActivationOutcome {
    data object Activated : ActivationOutcome

    data class Refused(val reason: ActivationRefusal, val message: String) : ActivationOutcome

    /** BLOCK_UNINSTALL: launch [intent] (ACTION_ADD_DEVICE_ADMIN), then call `activate` again. */
    data class NeedsDeviceAdmin(val intent: Intent) : ActivationOutcome
}

sealed interface UnlockOutcome {
    data object Unlocked : UnlockOutcome

    data class WrongPin(val lockedUntil: Long?) : UnlockOutcome

    data class Refused(val reason: UnlockRefusal, val remainingMs: Long?) : UnlockOutcome
}

/** Strict Mode activation, unlock and emergency unblock (§9.2, §9.5, §9.7). See `platform/API.md`. */
class StrictActivator internal constructor(private val app: Context, private val g: PlatformGraph) {
    private val dpm: DevicePolicyManager? get() = app.getSystemService(DevicePolicyManager::class.java)
    private val admin: ComponentName get() = ComponentName(app, StrictAdminReceiver::class.java)

    @Volatile private var adminPendingUntilRt = 0L

    fun isAdminActive(): Boolean = dpm?.isAdminActive(admin) == true

    /** The 2-minute window in which `DeviceAdminAdd` is exempt from the Settings guard (§9.2). */
    fun adminActivationPending(): Boolean = SystemTimeAndroid.elapsedRealtime() < adminPendingUntilRt

    fun deviceAdminIntent(): Intent {
        adminPendingUntilRt = SystemTimeAndroid.elapsedRealtime() + 120_000
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "While Strict Mode is on, Brainrot Prevention can't be uninstalled.")
    }

    fun inAccessibilityShortcut(): Boolean {
        val me = ComponentName(app, BlockerAccessibilityService::class.java)
        return SHORTCUT_KEYS.any { k ->
            val v = Settings.Secure.getString(app.contentResolver, k) ?: return@any false
            v.split(':').any { s ->
                val c = ComponentName.unflattenFromString(s.trim())
                c != null && c.packageName == me.packageName && (c.className == me.className || c.shortClassName == me.shortClassName)
            }
        }
    }

    suspend fun facts(): StrictFacts = StrictFacts(
        accessibilityConnected = g.service != null,
        usageAccessGranted = g.health.usageAccessGranted(),
        inAccessibilityShortcut = inAccessibilityShortcut(),
        adminActive = isAdminActive(),
    )

    suspend fun preflight(req: StrictActivation): ActivationRefusal? =
        StrictPolicy.checkActivation(req, facts(), g.data.schedules.getAll(), g.data.strict.get(), g.data.quickBlock.get())

    /**
     * The normal activation path. [launchAdmin] false (debug `strict-start`) refuses instead of asking for the
     * device admin.
     */
    suspend fun activate(req: StrictActivation, launchAdmin: Boolean = true, allowShortTimer: Boolean = false): ActivationOutcome {
        // Debug `strict-start` checks the §9.2 preconditions; the 15 min TIMER minimum is the editor's range (§9.5),
        // and S11 uses a 10 min timer. Activate with the minimum, then set the real deadline.
        val shortMs = req.durationMs?.takeIf {
            allowShortTimer &&
                req.method == StrictMethod.TIMER &&
                it in 1 until StrictPolicy.TIMER_MIN_MS
        }
        if (shortMs != null) {
            val r = activate(req.copy(durationMs = StrictPolicy.TIMER_MIN_MS), launchAdmin, false)
            if (r == ActivationOutcome.Activated) {
                g.engineHost.confined {
                    val row = g.data.strict.get()
                    val c = g.clock.read()
                    g.data.strict.writePolicyResult(row.copy(endsAt = c.now + shortMs, endsAtElapsed = c.elapsed + shortMs))
                }
                g.engineHost.evaluateNow("strict-short-timer")
            }
            return r
        }
        val facts = facts()
        val schedules = g.data.schedules.getAll()
        val current = g.data.strict.get()
        val quickBlock = g.data.quickBlock.get()
        if (launchAdmin && StrictOptions.has(req.options, StrictOptions.BLOCK_UNINSTALL) && !facts.adminActive) {
            val pre = StrictPolicy.checkActivation(req, facts.copy(adminActive = true), schedules, current, quickBlock)
            if (pre != null) return ActivationOutcome.Refused(pre, message(pre))
            return ActivationOutcome.NeedsDeviceAdmin(deviceAdminIntent())
        }
        val outcome = g.engineHost.confined {
            val clock = g.clock.read()
            when (
                val r = StrictPolicy.activate(
                    req,
                    facts,
                    schedules,
                    g.data.strict.get(),
                    clock.now,
                    clock.elapsed,
                    quickBlock = quickBlock
                )
            ) {
                is ActivationResult.Refused -> ActivationOutcome.Refused(r.reason, message(r.reason))
                is ActivationResult.Activated -> {
                    g.data.strict.writePolicyResult(r.row)
                    Brp.event("strict_activated", "method" to req.method.name, "options" to req.options)
                    ActivationOutcome.Activated
                }
            }
        }
        adminPendingUntilRt = 0
        g.engineHost.evaluateNow("strict-activate")
        return outcome
    }

    suspend fun pausedOrOff(): List<Schedule> {
        val all = g.data.schedules.getAll()
        val ids = StrictPolicy.pausedOrOff(all, g.clock.now()).toSet()
        return all.filter { it.id in ids }
    }

    suspend fun resumeAll() {
        for (s in pausedOrOff()) {
            if (!s.enabled) g.data.schedules.setEnabled(s.id, true) else g.data.schedules.resume(s.id)
        }
        g.engineHost.requestEvaluate("resume-all")
    }

    suspend fun notCovered(): List<Schedule> {
        val all = g.data.schedules.getAll()
        val ids = StrictPolicy.notCovered(all).toSet()
        return all.filter { it.id in ids }
    }

    fun elapsedNow(): Long = g.clock.elapsed()

    private suspend fun finishUnlock(r: UnlockResult): UnlockOutcome = when (r) {
        is UnlockResult.Unlocked -> {
            g.data.strict.writePolicyResult(r.row)
            removeAdmin()
            g.engineHost.requestEvaluate("unlock")
            UnlockOutcome.Unlocked
        }
        is UnlockResult.WrongPin -> {
            g.data.strict.writePolicyResult(r.row)
            UnlockOutcome.WrongPin(r.lockedUntil)
        }
        is UnlockResult.Refused -> UnlockOutcome.Refused(r.reason, r.remainingMs)
    }

    suspend fun unlockWithCharger(): UnlockOutcome {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        return g.engineHost.confined { finishUnlock(StrictPolicy.unlockWithCharger(g.data.strict.get(), plugged)) }
    }

    suspend fun unlockWithPin(pin: String): UnlockOutcome = g.engineHost.confined {
        val c = g.clock.read()
        finishUnlock(StrictPolicy.unlockWithPin(g.data.strict.get(), pin, c.now, c.elapsed))
    }

    fun startCooldown(): Long = g.clock.elapsed()

    fun cooldownRemaining(started: Long): Long {
        val row = g.data.snapshots.snapshot.value?.strict ?: return 0
        return StrictPolicy.waitRemaining(started, g.clock.elapsed(), row.cooldownMin * 60_000L)
    }

    suspend fun finishCooldown(started: Long): UnlockOutcome = g.engineHost.confined {
        finishUnlock(StrictPolicy.unlockAfterCooldown(g.data.strict.get(), started, g.clock.elapsed()))
    }

    fun startStandbyTurnOff(): Long = g.clock.elapsed()

    fun standbyTurnOffRemaining(started: Long): Long =
        StrictPolicy.waitRemaining(started, g.clock.elapsed(), StrictPolicy.STANDBY_TURN_OFF_COOLDOWN_MS)

    suspend fun finishStandbyTurnOff(started: Long): UnlockOutcome = g.engineHost.confined {
        val display = g.engineHost.status.value.strict
        finishUnlock(StrictPolicy.turnOffFromStandby(g.data.strict.get(), display, started, g.clock.elapsed()))
    }

    suspend fun extendTimer(extraMs: Long): WriteResult<Unit> {
        val r = g.engineHost.confined { g.data.strict.extendTimer(extraMs) }
        g.engineHost.requestEvaluate("extend")
        return r
    }

    suspend fun emergencyAvailableAt(): Long? = StrictPolicy.emergencyAvailableAt(g.data.strict.getEmergencyLog(), g.clock.now())

    fun startEmergencyCountdown(): Long = g.clock.elapsed()

    fun emergencyRemaining(started: Long): Long =
        StrictPolicy.waitRemaining(started, g.clock.elapsed(), StrictPolicy.EMERGENCY_COUNTDOWN_MS)

    suspend fun finishEmergency(started: Long): EmergencyResult = g.engineHost.confined {
        val c = g.clock.read()
        val r = StrictPolicy.emergencyUnblock(g.data.strict.get(), g.data.strict.getEmergencyLog(), c.now, started, c.elapsed)
        if (r is EmergencyResult.Done) {
            g.data.strict.writeEmergency(r.row, r.entry)
            Brp.event("emergency", "at" to r.entry.at)
            removeAdmin()
            g.engineHost.requestEvaluate("emergency")
        }
        r
    }

    /** Removes the device admin (asynchronous); `admin removed` is logged once it is gone. */
    fun removeAdmin() {
        if (!isAdminActive()) return
        try {
            dpm?.removeActiveAdmin(admin)
        } catch (e: SecurityException) {
            Brp.event("error", "where" to "removeActiveAdmin", "msg" to e.toString())
            return
        }
        g.scope.launch {
            repeat(50) {
                if (!isAdminActive()) {
                    StrictAdminReceiver.logRemoved()
                    return@launch
                }
                delay(100)
            }
        }
    }

    fun message(r: ActivationRefusal): String = when (r) {
        ActivationRefusal.ALREADY_ON -> "Strict Mode is already on."
        ActivationRefusal.ACCESSIBILITY_NOT_CONNECTED -> "Turn on the Brainrot Prevention accessibility service first."
        ActivationRefusal.NO_USAGE_ACCESS -> "Allow usage access first."
        ActivationRefusal.IN_ACCESSIBILITY_SHORTCUT -> "Remove Brainrot Prevention from the accessibility shortcut first."
        ActivationRefusal.INVALID_OPTIONS -> "Blocking uninstall also needs blocking Settings."
        ActivationRefusal.ADMIN_NOT_ACTIVE -> "Uninstall protection needs the device admin to be activated."
        ActivationRefusal.TIMER_OUT_OF_RANGE -> "Choose a timer between 15 minutes and 30 days."
        ActivationRefusal.INVALID_PIN -> "The PIN must be 4–8 digits."
        ActivationRefusal.INVALID_COOLDOWN -> "Choose a cooldown of 5, 10, 15 or 30 minutes."
        ActivationRefusal.FOLLOWED_SET_EMPTY -> "Choose at least one schedule to follow."
        ActivationRefusal.UNBOUNDED_FOLLOWED -> UNBOUNDED_MESSAGE
    }

    companion object {
        /**
         * Every `Settings.Secure` key that assigns an accessibility service to a shortcut (§9.2). Checked on the
         * API 37 image (framework.jar strings): volume keys, the a11y button, QS tile, the 2/3-finger swipe-up
         * gesture, the keyboard shortcut, the keyboard's top-row a11y key and the quick-access menu.
         */
        val SHORTCUT_KEYS = listOf(
            "accessibility_shortcut_target_service",
            "accessibility_button_targets",
            "accessibility_qs_targets",
            "accessibility_gesture_targets",
            "accessibility_key_gesture_targets",
            "accessibility_top_row_key_targets",
            "accessibility_quick_access_targets",
        )

        const val UNBOUNDED_MESSAGE = "The followed schedules and Quick Block block with no break (no timer, \"until I " +
            "stop it\", always on, or schedules covering the whole week together), so Strict Mode could never be turned " +
            "off. Leave at least 10 minutes a week unscheduled so Strict Mode can be turned off, or stop the item first."

        const val PLEDGE = "I am choosing to end Strict Mode early because of a real emergency. " +
            "I understand I can do this only once every 30 days."
    }
}

/** Uninstall protection (§9.3); `<uses-policies/>` is empty. */
class StrictAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Brp.event("admin", "state" to "active")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        logRemoved()
    }

    companion object {
        @Volatile private var lastRemovedLogRt = 0L

        internal fun logRemoved() {
            val now = SystemTimeAndroid.elapsedRealtime()
            if (now - lastRemovedLogRt < 2_000) return
            lastRemovedLogRt = now
            Brp.event("admin", "state" to "removed")
        }
    }
}
