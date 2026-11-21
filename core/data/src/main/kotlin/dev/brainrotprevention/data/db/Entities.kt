package dev.brainrotprevention.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import dev.brainrotprevention.model.AllowlistWeb
import dev.brainrotprevention.model.Combine
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.LocationState
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictState
import dev.brainrotprevention.model.WebRuleKind

// Room schema v1 (SPEC §4.1). Enums are stored by name.

@Entity(tableName = "schedule")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val sortOrder: Int,
    val enabled: Boolean,
    val mode: Mode,
    val combine: Combine,
    val allowlistWeb: AllowlistWeb,
    val blockNotifications: Boolean,
    val blockUnsupportedBrowsers: Boolean,
    val pausedUntil: Long?,
    val manualUntil: Long?,
    val strictFollowed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

private const val SCHEDULE_FK = "scheduleId"

@Entity(
    tableName = "time_window",
    foreignKeys = [ForeignKey(ScheduleEntity::class, ["id"], [SCHEDULE_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index(SCHEDULE_FK)],
)
data class TimeWindowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long,
    val days: Int,
    val startMin: Int,
    val endMin: Int,
)

@Entity(
    tableName = "usage_limit",
    foreignKeys = [ForeignKey(ScheduleEntity::class, ["id"], [SCHEDULE_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = [SCHEDULE_FK, "kind"], unique = true)],
)
data class UsageLimitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long,
    val kind: LimitKind,
    val period: Period,
    val allowance: Long,
)

@Entity(
    tableName = "usage_counter",
    foreignKeys = [ForeignKey(UsageLimitEntity::class, ["id"], ["limitId"], onDelete = ForeignKey.CASCADE)],
)
data class UsageCounterEntity(
    @PrimaryKey val limitId: Long,
    val periodStart: Long,
    val used: Long,
    val lastLaunchAt: Long?,
    val warnedPeriodStart: Long?,
)

@Entity(
    tableName = "wifi_condition",
    foreignKeys = [ForeignKey(ScheduleEntity::class, ["id"], [SCHEDULE_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = [SCHEDULE_FK, "ssid"], unique = true)],
)
data class WifiConditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long,
    val ssid: String,
)

@Entity(
    tableName = "location_condition",
    foreignKeys = [ForeignKey(ScheduleEntity::class, ["id"], [SCHEDULE_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index(SCHEDULE_FK)],
)
data class LocationConditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long,
    val label: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Int,
    val inverted: Boolean,
    val lastState: LocationState,
)

@Entity(
    tableName = "list_app",
    foreignKeys = [ForeignKey(ScheduleEntity::class, ["id"], [SCHEDULE_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = [SCHEDULE_FK, "packageName"], unique = true)],
)
data class ListAppEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long,
    val packageName: String,
)

@Entity(
    tableName = "web_rule",
    foreignKeys = [ForeignKey(ScheduleEntity::class, ["id"], [SCHEDULE_FK], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = [SCHEDULE_FK, "kind", "value"], unique = true)],
)
data class WebRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheduleId: Long,
    val kind: WebRuleKind,
    val value: String,
    val anywhereInUrl: Boolean,
)

/** The single Quick Block row, id = 1. */
@Entity(tableName = "quick_block")
data class QuickBlockEntity(
    @PrimaryKey val id: Int = 1,
    val active: Boolean,
    val until: Long?,
    val blockNotifications: Boolean,
)

@Entity(tableName = "quick_block_app")
data class QuickBlockAppEntity(@PrimaryKey val packageName: String)

@Entity(tableName = "quick_block_web", primaryKeys = ["kind", "value"])
data class QuickBlockWebEntity(
    val kind: WebRuleKind,
    val value: String,
    val anywhereInUrl: Boolean,
)

@Entity(tableName = "pause_preset", indices = [Index(value = ["durationMin"], unique = true)])
data class PausePresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val durationMin: Int,
    val lastUsedAt: Long,
)

/** The single strict_mode row, id = 1. Hash and salt are Base64. */
@Entity(tableName = "strict_mode")
data class StrictModeEntity(
    @PrimaryKey val id: Int = 1,
    val state: StrictState,
    val options: Int,
    val method: StrictMethod,
    val followAllSchedules: Boolean,
    val endsAt: Long?,
    val endsAtElapsed: Long?,
    val lastCheckWall: Long?,
    val lastCheckElapsed: Long?,
    val pinHash: String?,
    val pinSalt: String?,
    val pinFailures: Int,
    val pinLockedUntilWall: Long?,
    val pinLockedUntilElapsed: Long?,
    val cooldownMin: Int,
    val activatedAt: Long?,
)

@Entity(tableName = "emergency_unblock")
data class EmergencyUnblockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
)

@Entity(tableName = "excluded_browser")
data class ExcludedBrowserEntity(@PrimaryKey val packageName: String)

/** Counts only, no notification content. `day` is `yyyy-MM-dd`. */
@Entity(tableName = "notification_count", primaryKeys = ["packageName", "day"])
data class NotificationCountEntity(
    val packageName: String,
    val day: String,
    val count: Int,
)

/** A schedule with all child rows, read in one transaction. */
data class ScheduleWithChildren(
    @Embedded val schedule: ScheduleEntity,
    @Relation(parentColumn = "id", entityColumn = SCHEDULE_FK) val timeWindows: List<TimeWindowEntity>,
    @Relation(parentColumn = "id", entityColumn = SCHEDULE_FK) val limits: List<UsageLimitEntity>,
    @Relation(parentColumn = "id", entityColumn = SCHEDULE_FK) val wifi: List<WifiConditionEntity>,
    @Relation(parentColumn = "id", entityColumn = SCHEDULE_FK) val locations: List<LocationConditionEntity>,
    @Relation(parentColumn = "id", entityColumn = SCHEDULE_FK) val apps: List<ListAppEntity>,
    @Relation(parentColumn = "id", entityColumn = SCHEDULE_FK) val webRules: List<WebRuleEntity>,
)
