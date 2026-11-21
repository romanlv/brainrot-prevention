package dev.brainrotprevention.ui.platform

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.engine.ActivationRefusal
import dev.brainrotprevention.engine.EmergencyResult
import dev.brainrotprevention.engine.StrictActivation
import dev.brainrotprevention.engine.UnlockRefusal
import dev.brainrotprevention.model.SensorState

/*
 * Narrow UI-side views of the platform entry points (platform/API.md). The UI only talks to these; the app wires
 * them to the platform graph in [PlatformPorts] and tests pass fakes. Keeping the seam here means the UI compiles
 * and is testable without the accessibility service, and a platform change touches one adapter file.
 */

data class UiApp(val packageName: String, val label: String)

/** Mirrors `platform.Grant`. */
enum class UiGrant {
    ACCESSIBILITY,
    USAGE_ACCESS,
    NOTIFICATION_LISTENER,
    POST_NOTIFICATIONS,
    EXACT_ALARMS,
    LOCATION_FINE,
    LOCATION_BACKGROUND,
    BATTERY_OPTIMIZATION,
    DEVICE_ADMIN,
}

data class UiGrantStatus(val grant: UiGrant, val granted: Boolean, val required: Boolean, val detail: String? = null)

data class UiHealth(
    val items: List<UiGrantStatus> = emptyList(),
    val restrictedInstall: Boolean = false,
    val advancedProtection: Boolean = false,
    val settingsLockedByStrict: Boolean = false,
    /** The accessibility service is enabled and within its bind grace (a cold start): not "off" yet. */
    val accessibilityStarting: Boolean = false,
) {
    val ok: Boolean get() = items.filter { it.required }.all { it.granted }

    /**
     * Nothing is enforced: the accessibility service (outside its bind grace) or usage access is missing. Other
     * required grants (location for Wi-Fi/location triggers) only stop those triggers (review round 3, minor 5).
     */
    val enforcementMissing: UiGrant?
        get() = ENFORCEMENT.firstOrNull { g ->
            val item = get(g) ?: return@firstOrNull false
            item.required && !item.granted && !(g == UiGrant.ACCESSIBILITY && accessibilityStarting)
        }

    /** Wi-Fi/location triggers exist but can't be checked (a location grant is missing). */
    val triggersUnchecked: Boolean
        get() = items.any { it.required && !it.granted && (it.grant == UiGrant.LOCATION_FINE || it.grant == UiGrant.LOCATION_BACKGROUND) }

    private companion object {
        val ENFORCEMENT = listOf(UiGrant.ACCESSIBILITY, UiGrant.USAGE_ACCESS)
    }

    operator fun get(grant: UiGrant): UiGrantStatus? = items.firstOrNull { it.grant == grant }
}

enum class FixResult { OPENED, LOCKED_BY_STRICT, UNAVAILABLE }

/** `EngineHost` + live sensors. */
interface EnginePort {
    fun requestEvaluate()

    /** Current sensor state (Wi-Fi SSID, location rows), for the UI's own `Engine.status` calls. */
    fun sensors(): SensorState
}

/** `PermissionHealth`. */
interface HealthPort {
    /** Cheap; call on resume. */
    fun check(): UiHealth

    /** Opens the Settings screen for [grant] unless Strict Mode's Settings guard is active (§9.3). */
    fun open(activity: Activity, grant: UiGrant): FixResult

    /** Runtime permission string for POST_NOTIFICATIONS / LOCATION_FINE, else null. */
    fun runtimePermission(grant: UiGrant): String?

    /** App info (restricted settings: ⋮ → Allow restricted settings), subject to the same Strict check. */
    fun openAppDetails(activity: Activity): FixResult
}

/** `InstalledApps` (+ sensor reads the editor needs). */
interface AppsPort {
    /** Launcher apps without the always-exempt packages, sorted by label. */
    suspend fun launchable(): List<UiApp>

    fun icon(pkg: String): Drawable?

    fun label(pkg: String): String

    suspend fun unsupportedBrowsers(): List<UiApp>

    suspend fun allowlistEssentials(): List<UiApp>

    /** Bundled `app_domains.json`: package → domains. */
    val appDomains: Map<String, List<String>>

    /** The connected Wi-Fi network (needs location permission), or null. */
    fun currentSsid(): String?

    /** The last known location, or null. */
    suspend fun currentLocation(): Pair<Double, Double>?
}

sealed interface UiActivation {
    data object Activated : UiActivation

    data class Refused(val reason: ActivationRefusal?, val message: String) : UiActivation

    data class NeedsDeviceAdmin(val intent: Intent) : UiActivation
}

sealed interface UiUnlock {
    data object Unlocked : UiUnlock

    data class WrongPin(val lockedUntil: Long?) : UiUnlock

    data class Refused(val reason: UnlockRefusal?, val remainingMs: Long?) : UiUnlock
}

/** `StrictActivator`. Countdowns are timed by the caller with policy elapsed time (`PolicyClock.elapsed`). */
interface StrictPort {
    suspend fun activate(req: StrictActivation): UiActivation

    suspend fun unlockWithCharger(): UiUnlock

    suspend fun unlockWithPin(pin: String): UiUnlock

    suspend fun finishCooldown(startedElapsed: Long): UiUnlock

    suspend fun finishStandbyTurnOff(startedElapsed: Long): UiUnlock

    suspend fun extendTimer(extraMs: Long): WriteResult<Unit>

    suspend fun finishEmergency(startedElapsed: Long): EmergencyResult

    /** The text to retype exactly (§9.7). */
    val pledge: String
}

class UiPlatform(val engine: EnginePort, val health: HealthPort, val apps: AppsPort, val strict: StrictPort)
