package dev.brainrotprevention.model

import kotlinx.serialization.Serializable

/** Schedule list semantics (§6). */
@Serializable
enum class Mode { BLOCKLIST, ALLOWLIST }

/** How trigger types combine (§5.2). */
@Serializable
enum class Combine { ANY, ALL }

/** ALLOWLIST web behaviour (§7.5). */
@Serializable
enum class AllowlistWeb { OPEN, ONLY_LISTED }

@Serializable
enum class LimitKind { TIME, LAUNCHES }

@Serializable
enum class Period { DAILY, HOURLY }

@Serializable
enum class WebRuleKind { DOMAIN, KEYWORD }

@Serializable
enum class StrictMethod { TIMER, SCHEDULES, CHARGER, PIN, COOLDOWN }

/** Stored strict state. The displayed ACTIVE/STANDBY is derived (§9.5). */
@Serializable
enum class StrictState { INACTIVE, ON }

/** Displayed strict state (§9.5). */
@Serializable
enum class StrictDisplayState { INACTIVE, ACTIVE, STANDBY }

@Serializable
enum class LocationState { INSIDE, OUTSIDE, UNKNOWN }

/** Trigger types a schedule can have (§5.2). */
@Serializable
enum class TriggerType { TIME, WIFI, LOCATION }
