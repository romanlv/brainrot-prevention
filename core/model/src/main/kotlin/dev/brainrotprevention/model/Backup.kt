package dev.brainrotprevention.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Backup file, format 1 (§4.3). Field names are the on-disk JSON names. */
@Serializable
data class Backup(
    val format: Int = BackupCodec.FORMAT,
    val exportedAt: Long = 0,
    val schedules: List<BackupSchedule> = emptyList(),
    val quickBlock: BackupQuickBlock = BackupQuickBlock(),
    val excludedBrowsers: List<String> = emptyList(),
    val prefs: BackupPrefs = BackupPrefs(),
)

@Serializable
data class BackupSchedule(
    val name: String,
    val icon: String = "",
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    val mode: Mode = Mode.BLOCKLIST,
    val combine: Combine = Combine.ANY,
    val allowlistWeb: AllowlistWeb = AllowlistWeb.OPEN,
    val blockNotifications: Boolean = false,
    val blockUnsupportedBrowsers: Boolean = true,
    val strictFollowed: Boolean = false,
    val timeWindows: List<BackupTimeWindow> = emptyList(),
    val limits: List<BackupLimit> = emptyList(),
    val wifi: List<String> = emptyList(),
    val locations: List<BackupLocation> = emptyList(),
    val apps: List<String> = emptyList(),
    val webRules: List<BackupWebRule> = emptyList(),
)

@Serializable
data class BackupTimeWindow(val days: Int, val startMin: Int, val endMin: Int)

@Serializable
data class BackupLimit(val kind: LimitKind, val period: Period, val allowance: Long)

@Serializable
data class BackupLocation(val label: String, val lat: Double, val lon: Double, val radiusM: Int, val inverted: Boolean = false)

@Serializable
data class BackupWebRule(val kind: WebRuleKind, val value: String, val anywhereInUrl: Boolean = false)

@Serializable
data class BackupQuickBlock(
    val apps: List<String> = emptyList(),
    val webRules: List<BackupWebRule> = emptyList(),
    val blockNotifications: Boolean = false,
)

@Serializable
data class BackupPrefs(
    val dayStartHour: Int = 0,
    val limitWarningMin: Int = 5,
    val notifyBeforeStartMin: Int = 0,
    val notifyBeforePauseEndMin: Int = 1,
    val hidePauseOption: Boolean = false,
)

/** Thrown by [BackupCodec.decode] with a human-readable message. */
class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What an import writes (§4.3): replaces schedules and Quick Block items; not paused, not started. */
data class ImportPlan(
    val schedules: List<Schedule>,
    val quickBlockApps: List<String>,
    val quickBlockWebRules: List<WebRule>,
    val quickBlockNotifications: Boolean,
    val excludedBrowsers: Set<String>,
    val prefs: BackupPrefs,
)

object BackupCodec {
    const val FORMAT = 1

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encode(backup: Backup): String = json.encodeToString(Backup.serializer(), backup)

    /** Parses JSON; throws [BackupFormatException] (unknown key, wrong type, bad enum, wrong format). */
    fun decode(text: String): Backup {
        val backup = try {
            json.decodeFromString(Backup.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupFormatException("Not a valid backup file: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("Not a valid backup file: ${e.message}", e)
        }
        if (backup.format != FORMAT) throw BackupFormatException("Unsupported backup format ${backup.format} (expected $FORMAT)")
        return backup
    }

    /** Builds a backup from the current configuration. Child lists are sorted so exports are stable. */
    fun fromConfig(
        schedules: List<Schedule>,
        quickBlock: QuickBlock,
        excludedBrowsers: Collection<String>,
        prefs: Prefs,
        exportedAt: Long,
    ): Backup = Backup(
        format = FORMAT,
        exportedAt = exportedAt,
        schedules = schedules.sortedWith(compareBy({ it.sortOrder }, { it.id })).map { it.toBackup() },
        quickBlock = BackupQuickBlock(
            apps = quickBlock.apps.sorted(),
            webRules = quickBlock.webRules.toBackupRules(),
            blockNotifications = quickBlock.blockNotifications,
        ),
        excludedBrowsers = excludedBrowsers.sorted(),
        prefs = BackupPrefs(
            dayStartHour = prefs.pendingDayStartHour ?: prefs.dayStartHour,
            limitWarningMin = prefs.limitWarningMin,
            notifyBeforeStartMin = prefs.notifyBeforeStartMin,
            notifyBeforePauseEndMin = prefs.notifyBeforePauseEndMin,
            hidePauseOption = prefs.hidePauseOption,
        ),
    )

    /** Converts a (validated) backup to rows to write. IDs are 0; `now` fills createdAt/updatedAt. */
    fun toImportPlan(backup: Backup, now: Long): ImportPlan = ImportPlan(
        schedules = backup.schedules.map { it.toSchedule(now) },
        quickBlockApps = backup.quickBlock.apps,
        quickBlockWebRules = backup.quickBlock.webRules.map { it.toRule() },
        quickBlockNotifications = backup.quickBlock.blockNotifications,
        excludedBrowsers = backup.excludedBrowsers.toSet(),
        prefs = backup.prefs,
    )

    private fun Schedule.toBackup() = BackupSchedule(
        name = name,
        icon = icon,
        sortOrder = sortOrder,
        enabled = enabled,
        mode = mode,
        combine = combine,
        allowlistWeb = allowlistWeb,
        blockNotifications = blockNotifications,
        blockUnsupportedBrowsers = blockUnsupportedBrowsers,
        strictFollowed = strictFollowed,
        timeWindows = timeWindows.map { BackupTimeWindow(it.days, it.startMin, it.endMin) }
            .sortedWith(compareBy({ it.startMin }, { it.endMin }, { it.days })),
        limits = limits.map { BackupLimit(it.kind, it.period, it.allowance) }.sortedBy { it.kind },
        wifi = wifi.map { it.ssid }.sorted(),
        locations = locations.map { BackupLocation(it.label, it.lat, it.lon, it.radiusM, it.inverted) }
            .sortedWith(compareBy({ it.label }, { it.lat }, { it.lon }, { it.radiusM }, { it.inverted })),
        apps = apps.sorted(),
        webRules = webRules.toBackupRules(),
    )

    private fun List<WebRule>.toBackupRules() =
        map { BackupWebRule(it.kind, it.value, it.anywhereInUrl) }.sortedWith(compareBy({ it.kind }, { it.value }, { it.anywhereInUrl }))

    /** Converts one backup schedule to a domain schedule (ids 0, not paused, not started). */
    fun BackupSchedule.toSchedule(now: Long = 0): Schedule = Schedule(
        name = name,
        icon = icon,
        sortOrder = sortOrder,
        enabled = enabled,
        mode = mode,
        combine = combine,
        allowlistWeb = allowlistWeb,
        blockNotifications = blockNotifications,
        blockUnsupportedBrowsers = blockUnsupportedBrowsers,
        pausedUntil = null,
        manualUntil = null,
        strictFollowed = strictFollowed,
        createdAt = now,
        updatedAt = now,
        timeWindows = timeWindows.map { TimeWindow(days = it.days, startMin = it.startMin, endMin = it.endMin) },
        limits = limits.map { UsageLimit(kind = it.kind, period = it.period, allowance = it.allowance) },
        wifi = wifi.map { WifiCondition(ssid = it) },
        locations = locations.map {
            LocationCondition(label = it.label, lat = it.lat, lon = it.lon, radiusM = it.radiusM, inverted = it.inverted)
        },
        apps = apps,
        webRules = webRules.map { it.toRule() },
    )

    fun BackupWebRule.toRule() = WebRule(kind = kind, value = value, anywhereInUrl = anywhereInUrl)
}
