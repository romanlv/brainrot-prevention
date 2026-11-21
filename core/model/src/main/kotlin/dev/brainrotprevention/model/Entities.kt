package dev.brainrotprevention.model

import kotlinx.serialization.Serializable

/** Minutes from local midnight; 0–1439 start, 1–1440 end; end < start runs past midnight (§4.1). */
@Serializable
data class TimeWindow(val id: Long = 0, val days: Int, val startMin: Int, val endMin: Int) {
    val overnight: Boolean get() = endMin < startMin
}

/** TIME allowance in ms (1 min – 23 h), LAUNCHES allowance 1–500. Unique per (schedule, kind). */
@Serializable
data class UsageLimit(val id: Long = 0, val kind: LimitKind, val period: Period, val allowance: Long)

/** SSID without surrounding quotes. */
@Serializable
data class WifiCondition(val id: Long = 0, val ssid: String)

@Serializable
data class LocationCondition(
    val id: Long = 0,
    val label: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val inverted: Boolean = false,
    val lastState: LocationState = LocationState.UNKNOWN,
)

/** DOMAIN values are ASCII (punycode); KEYWORD values are NFC, lower-cased (§4.1, §7.3). */
@Serializable
data class WebRule(val id: Long = 0, val kind: WebRuleKind, val value: String, val anywhereInUrl: Boolean = false)

/** A schedule with its child rows (§4.1). `apps` are list_app package names. */
@Serializable
data class Schedule(
    val id: Long = 0,
    val name: String,
    val icon: String = "",
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    val mode: Mode = Mode.BLOCKLIST,
    val combine: Combine = Combine.ANY,
    val allowlistWeb: AllowlistWeb = AllowlistWeb.OPEN,
    val blockNotifications: Boolean = false,
    val blockUnsupportedBrowsers: Boolean = true,
    val pausedUntil: Long? = null,
    val manualUntil: Long? = null,
    val strictFollowed: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val timeWindows: List<TimeWindow> = emptyList(),
    val limits: List<UsageLimit> = emptyList(),
    val wifi: List<WifiCondition> = emptyList(),
    val locations: List<LocationCondition> = emptyList(),
    val apps: List<String> = emptyList(),
    val webRules: List<WebRule> = emptyList(),
) {
    fun limit(kind: LimitKind): UsageLimit? = limits.firstOrNull { it.kind == kind }

    /** Trigger types present (§5.2). */
    val triggerTypes: Set<TriggerType>
        get() = buildSet {
            if (timeWindows.isNotEmpty()) add(TriggerType.TIME)
            if (wifi.isNotEmpty()) add(TriggerType.WIFI)
            if (locations.isNotEmpty()) add(TriggerType.LOCATION)
        }

    companion object {
        /** `manualUntil` value meaning "until I stop it". */
        const val UNTIL_STOPPED = Long.MAX_VALUE
    }
}

/** The single Quick Block row with its items (§4.1, §5.5). */
@Serializable
data class QuickBlock(
    val active: Boolean = false,
    val until: Long? = null,
    val blockNotifications: Boolean = false,
    val apps: List<String> = emptyList(),
    val webRules: List<WebRule> = emptyList(),
) {
    val isEmpty: Boolean get() = apps.isEmpty() && webRules.isEmpty()
}

/** The single strict_mode row (§4.1). Hash and salt are Base64. */
@Serializable
data class StrictModeRow(
    val state: StrictState = StrictState.INACTIVE,
    val options: Int = 0,
    val method: StrictMethod = StrictMethod.TIMER,
    val followAllSchedules: Boolean = false,
    val endsAt: Long? = null,
    val endsAtElapsed: Long? = null,
    val lastCheckWall: Long? = null,
    val lastCheckElapsed: Long? = null,
    val pinHash: String? = null,
    val pinSalt: String? = null,
    val pinFailures: Int = 0,
    val pinLockedUntilWall: Long? = null,
    val pinLockedUntilElapsed: Long? = null,
    val cooldownMin: Int = 5,
    val activatedAt: Long? = null,
    /** `Settings.Global.BOOT_COUNT` at the last tamper check (reboot detection, §9.6); null = unknown. */
    val bootCount: Int? = null,
) {
    val isOn: Boolean get() = state == StrictState.ON

    fun has(option: Int): Boolean = StrictOptions.has(options, option)
}

/** One emergency unblock use; `at` is skew-adjusted wall time (§9.6). */
@Serializable
data class EmergencyUnblock(val id: Long = 0, val at: Long)

/** Persisted counter for one usage_limit (§4.1). */
@Serializable
data class UsageCounter(
    val limitId: Long,
    val periodStart: Long,
    val used: Long = 0,
    val lastLaunchAt: Long? = null,
    val warnedPeriodStart: Long? = null,
    /** The limit's period when this counter was last read; a different period resets it (§5.4). Null = unknown (v1 rows). */
    val period: Period? = null,
    /**
     * Set when the clock went back: the end of the period `used` belongs to. Until policy time reaches it, a
     * later period change re-anchors instead of resetting, so back-then-forward can't refill a budget [PL-M3].
     */
    val holdUntil: Long? = null,
)

/** DataStore preferences (§4.1). */
@Serializable
data class Prefs(
    val dayStartHour: Int = 0,
    val pendingDayStartHour: Int? = null,
    val limitWarningMin: Int = 5,
    val notifyBeforeStartMin: Int = 0,
    val notifyBeforePauseEndMin: Int = 1,
    val hidePauseOption: Boolean = false,
    val onboardingDone: Boolean = false,
)
