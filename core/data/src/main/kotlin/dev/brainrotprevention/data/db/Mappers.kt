package dev.brainrotprevention.data.db

import dev.brainrotprevention.model.EmergencyUnblock
import dev.brainrotprevention.model.LocationCondition
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.TimeWindow
import dev.brainrotprevention.model.UsageCounter
import dev.brainrotprevention.model.UsageLimit
import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WifiCondition

internal fun ScheduleWithChildren.toModel(): Schedule = with(schedule) {
    Schedule(
        id = id,
        name = name,
        icon = icon,
        sortOrder = sortOrder,
        enabled = enabled,
        mode = mode,
        combine = combine,
        allowlistWeb = allowlistWeb,
        blockNotifications = blockNotifications,
        blockUnsupportedBrowsers = blockUnsupportedBrowsers,
        pausedUntil = pausedUntil,
        manualUntil = manualUntil,
        strictFollowed = strictFollowed,
        createdAt = createdAt,
        updatedAt = updatedAt,
        timeWindows = timeWindows.sortedBy { it.id }.map { TimeWindow(it.id, it.days, it.startMin, it.endMin) },
        limits = limits.sortedBy { it.kind }.map { UsageLimit(it.id, it.kind, it.period, it.allowance) },
        wifi = wifi.sortedBy { it.id }.map { WifiCondition(it.id, it.ssid) },
        locations = locations.sortedBy { it.id }.map {
            LocationCondition(it.id, it.label, it.lat, it.lon, it.radiusM, it.inverted, it.lastState)
        },
        apps = this@toModel.apps.sortedBy { it.id }.map { it.packageName },
        webRules = webRules.sortedBy { it.id }.map { WebRule(it.id, it.kind, it.value, it.anywhereInUrl) },
    )
}

internal fun Schedule.toEntity(): ScheduleEntity = ScheduleEntity(
    id = id,
    name = name,
    icon = icon,
    sortOrder = sortOrder,
    enabled = enabled,
    mode = mode,
    combine = combine,
    allowlistWeb = allowlistWeb,
    blockNotifications = blockNotifications,
    blockUnsupportedBrowsers = blockUnsupportedBrowsers,
    pausedUntil = pausedUntil,
    manualUntil = manualUntil,
    strictFollowed = strictFollowed,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun quickBlockOf(row: QuickBlockEntity?, apps: List<QuickBlockAppEntity>, web: List<QuickBlockWebEntity>) = QuickBlock(
    active = row?.active ?: false,
    until = row?.until,
    blockNotifications = row?.blockNotifications ?: false,
    apps = apps.map { it.packageName },
    webRules = web.map { WebRule(0, it.kind, it.value, it.anywhereInUrl) },
)

internal fun StrictModeEntity?.toModel(): StrictModeRow = this?.let {
    StrictModeRow(
        state = it.state,
        options = it.options,
        method = it.method,
        followAllSchedules = it.followAllSchedules,
        endsAt = it.endsAt,
        endsAtElapsed = it.endsAtElapsed,
        lastCheckWall = it.lastCheckWall,
        lastCheckElapsed = it.lastCheckElapsed,
        pinHash = it.pinHash,
        pinSalt = it.pinSalt,
        pinFailures = it.pinFailures,
        pinLockedUntilWall = it.pinLockedUntilWall,
        pinLockedUntilElapsed = it.pinLockedUntilElapsed,
        cooldownMin = it.cooldownMin,
        activatedAt = it.activatedAt,
        bootCount = it.bootCount,
    )
} ?: StrictModeRow()

internal fun StrictModeRow.toEntity() = StrictModeEntity(
    id = 1,
    state = state,
    options = options,
    method = method,
    followAllSchedules = followAllSchedules,
    endsAt = endsAt,
    endsAtElapsed = endsAtElapsed,
    lastCheckWall = lastCheckWall,
    lastCheckElapsed = lastCheckElapsed,
    pinHash = pinHash,
    pinSalt = pinSalt,
    pinFailures = pinFailures,
    pinLockedUntilWall = pinLockedUntilWall,
    pinLockedUntilElapsed = pinLockedUntilElapsed,
    cooldownMin = cooldownMin,
    activatedAt = activatedAt,
    bootCount = bootCount,
)

internal fun EmergencyUnblockEntity.toModel() = EmergencyUnblock(id, at)

internal fun UsageCounterEntity.toModel() = UsageCounter(limitId, periodStart, used, lastLaunchAt, warnedPeriodStart, period, holdUntil)

internal fun UsageCounter.toEntity() = UsageCounterEntity(limitId, periodStart, used, lastLaunchAt, warnedPeriodStart, period, holdUntil)

/**
 * Upsert-in-place of child rows (SPEC §4 conventions, PL-M2): a desired row matches an existing one by id
 * first (only if [matchById]), then by natural [key]. Matched rows keep their id and are updated only if they changed; unmatched
 * existing rows are deleted; the rest are inserted. Deletes run first so unique keys can move between rows.
 */
internal suspend fun <E : Any, K> syncChildren(
    existing: List<E>,
    desired: List<E>,
    id: (E) -> Long,
    key: (E) -> K,
    withId: (E, Long) -> E,
    delete: suspend (List<Long>) -> Unit,
    update: suspend (List<E>) -> Unit,
    insert: suspend (List<E>) -> Unit,
    matchById: Boolean = true,
) {
    val unmatched = LinkedHashMap<Long, E>().apply { existing.forEach { put(id(it), it) } }
    val resolved = ArrayList<Pair<E, E?>>(desired.size) // desired (with final id) to the existing row it replaces
    val pending = ArrayList<E>()
    for (d in desired) {
        val byId = id(d).takeIf { matchById && it != 0L }?.let { unmatched.remove(it) }
        if (byId != null) resolved += d to byId else pending += d
    }
    for (d in pending) {
        val k = key(withId(d, 0))
        val match = unmatched.values.firstOrNull { key(withId(it, 0)) == k }
        if (match != null) {
            unmatched.remove(id(match))
            resolved += withId(d, id(match)) to match
        } else {
            resolved += withId(d, 0) to null
        }
    }
    if (unmatched.isNotEmpty()) delete(unmatched.keys.toList())
    val updates = resolved.filter { (d, old) -> old != null && old != d }.map { it.first }
    if (updates.isNotEmpty()) update(updates)
    val inserts = resolved.filter { it.second == null }.map { it.first }
    if (inserts.isNotEmpty()) insert(inserts)
}
