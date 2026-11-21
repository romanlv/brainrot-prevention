package dev.brainrotprevention.model

import java.time.ZoneId
import kotlinx.serialization.Serializable

/**
 * Everything the engine needs, resolved by the platform. Cached to `files/snapshot.json`.
 * Package sets are unioned with [PackageDefaults] by the engine.
 */
@Serializable
data class ConfigSnapshot(
    val schedules: List<Schedule> = emptyList(),
    val quickBlock: QuickBlock = QuickBlock(),
    val strict: StrictModeRow = StrictModeRow(),
    val emergencyLog: List<EmergencyUnblock> = emptyList(),
    val excludedBrowsers: Set<String> = emptySet(),
    val prefs: Prefs = Prefs(),
    val browsers: List<BrowserEntry> = emptyList(),
    val strictGuards: StrictGuards = StrictGuards(),
    /** Own app, IME, default launcher, dialer, InCallService UIs, emergency/cell-broadcast apps (§6.3). */
    val exemptPackages: Set<String> = emptySet(),
    val exemptComponents: Set<String> = emptySet(),
    /** Default clock app etc. (§6.3); DocumentsUI and Settings are added by default. */
    val allowlistEssentials: Set<String> = emptySet(),
    /** §7.7 */
    val unsupportedBrowsers: Set<String> = emptySet(),
    /** WebAPK package → start URL (§7.9). */
    val webApps: Map<String, String> = emptyMap(),
    val ownPackage: String = "",
    /** FileProvider redirect URI prefix, e.g. `content://dev.brainrotprevention.blank/` (§7.5). */
    val redirectUriPrefix: String = "",
)

/** Sensor state (§5.1). Location states fall back to the row's `lastState`. */
@Serializable
data class SensorState(
    val ssid: String? = null,
    val locations: Map<Long, LocationState> = emptyMap(),
    val chargerConnected: Boolean = false,
)

/** One member of the foreground set (§10.1). `component` is `pkg/cls` when known. */
@Serializable
data class ForegroundApp(
    val packageName: String,
    val component: String? = null,
    /** Floating or secondary windows never count as launches (§5.4). */
    val secondary: Boolean = false,
)

/** A web target: the current `lastUrl` of a browser window (§7.8). */
@Serializable
data class WebTarget(
    val browserPackage: String,
    val url: String,
    /** `reliable` of the URL-bar node that matched (§7.2). */
    val reliable: Boolean = true,
    val windowId: Int = 0,
)

/** One reading of [PolicyClock]: wall ms, zone, policy-elapsed ms. */
data class ClockReading(val now: Long, val zone: ZoneId, val elapsed: Long)
