package dev.brainrotprevention.debug

import android.content.Context
import dev.brainrotprevention.BrpApp
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.debug.DebugCommands.Result
import dev.brainrotprevention.engine.StrictActivation
import dev.brainrotprevention.model.LocationState
import dev.brainrotprevention.model.OffsetPolicyClock
import dev.brainrotprevention.model.SensorState
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.UsageCounter
import dev.brainrotprevention.platform.ActivationOutcome
import dev.brainrotprevention.platform.PlatformGraph
import dev.brainrotprevention.platform.log.Brp
import dev.brainrotprevention.platform.platform
import java.io.File
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** M3–M8 debug commands (SPEC §14.3 item 2): clock, evaluate, usage, sensors, Quick Block, Strict, snooze chunk. */
internal object PlatformDebugCommands {
    private val json = Json { encodeDefaults = true }

    private val Context.app: BrpApp get() = applicationContext as BrpApp
    private val Context.clock: OffsetPolicyClock
        get() = app.debugClock ?: error("the debug clock is not installed")

    private suspend fun Context.clockChanged(reason: String): Result {
        app.debugClockStore?.save(clock)
        val g = platform
        g.alarms.invalidate()
        g.engineHost.onTimeChanged(reason)
        return Result.ok("$reason now=${clock.now()}")
    }

    fun registerAll() {
        // clock-set --es at <ISO-8601 with zone>: wall-only jump
        DebugCommands.register("clock-set") { intent ->
            val at = intent.getStringExtra("at") ?: return@register Result.fail("clock-set needs --es at <ISO-8601 with zone>")
            val instant = try {
                OffsetDateTime.parse(at).toInstant()
            } catch (_: DateTimeParseException) {
                try {
                    ZonedDateTime.parse(at).toInstant()
                } catch (e: DateTimeParseException) {
                    return@register Result.fail("clock-set: bad time '$at': ${e.message}")
                }
            }
            clock.setWall(instant)
            Brp.event("clock", "cmd" to "set", "at" to instant.toEpochMilli())
            clockChanged("clock-set")
        }
        // clock-advance --el ms N (N > 0): wall and elapsed both move
        DebugCommands.register("clock-advance") { intent ->
            val ms = intent.getLongExtra("ms", 0)
            if (ms <= 0) return@register Result.fail("clock-advance needs --el ms N with N > 0")
            clock.advance(ms)
            Brp.event("clock", "cmd" to "advance", "ms" to ms)
            clockChanged("clock-advance")
        }
        // clock-jump --el ms ±N: wall only (the user changes the clock; the TIME_SET path runs)
        DebugCommands.register("clock-jump") { intent ->
            if (!intent.hasExtra("ms")) return@register Result.fail("clock-jump needs --el ms ±N")
            val ms = intent.getLongExtra("ms", 0)
            clock.jump(ms)
            Brp.event("clock", "cmd" to "jump", "ms" to ms)
            clockChanged("clock-jump")
        }
        DebugCommands.register("clock-real") {
            clock.real()
            Brp.event("clock", "cmd" to "real")
            clockChanged("clock-real")
        }

        DebugCommands.register("evaluate") {
            val s = platform.engineHost.evaluateNow("debug")
            Result.ok("evaluated blocking=${s.blocking} strict=${s.strict}")
        }

        // usage-get: counters → files/debug/usage.json, short summary in the result
        DebugCommands.register("usage-get") {
            val counters = dataGraph().counters.loadAll().values.sortedBy { it.limitId }
            File(filesDir, "debug").apply { mkdirs() }.resolve("usage.json")
                .writeText(json.encodeToString(ListSerializer(UsageCounter.serializer()), counters))
            Result.ok(counters.joinToString(";") { "${it.limitId}:${it.used}" }.ifEmpty { "none" })
        }

        DebugCommands.register("sensor-ssid") { intent ->
            val ssid = intent.getStringExtra("ssid")
            platform.sensors.overrideSsid(ssid?.takeIf { it.isNotEmpty() && it != "null" })
            platform.engineHost.evaluateNow("sensor")
            Result.ok("ssid=$ssid")
        }
        DebugCommands.register("sensor-location") { intent ->
            val row = intent.getStringExtra("row")?.toLongOrNull() ?: intent.getLongExtra("row", -1).takeIf { it >= 0 }
                ?: return@register Result.fail("sensor-location needs --es row <id>")
            val st = intent.getStringExtra("state")?.let { runCatching { LocationState.valueOf(it) }.getOrNull() }
                ?: return@register Result.fail("sensor-location needs --es state INSIDE|OUTSIDE|UNKNOWN")
            platform.sensors.overrideLocation(row, st)
            platform.engineHost.evaluateNow("sensor")
            Result.ok("row $row=$st")
        }
        DebugCommands.register("sensor-real") {
            platform.sensors.real()
            platform.engineHost.evaluateNow("sensor")
            Result.ok("sensors real")
        }

        // qb-start [--el ms N]: the normal Quick Block start path (no ms = no timer)
        DebugCommands.register("qb-start") { intent ->
            val g = platform
            val until = if (intent.hasExtra("ms")) g.clock.now() + intent.getLongExtra("ms", 0) else null
            when (val r = g.data.quickBlock.start(until)) {
                is WriteResult.Ok -> {
                    g.engineHost.evaluateNow("qb-start")
                    Result.ok("quick block started until=$until")
                }
                else -> Result.fail("qb-start refused: ${r.describe()}")
            }
        }

        // strict-start --es method M --el ms N --ei options O [--es pin X] [--ei cooldownMin N] [--ez followAll true]
        DebugCommands.register("strict-start") { intent ->
            val method = intent.getStringExtra("method")?.let { runCatching { StrictMethod.valueOf(it) }.getOrNull() }
                ?: return@register Result.fail("strict-start needs --es method TIMER|CHARGER|PIN|COOLDOWN|SCHEDULES")
            val req = StrictActivation(
                method = method,
                options = intent.getIntExtra("options", 0),
                durationMs = if (intent.hasExtra("ms")) intent.getLongExtra("ms", 0) else null,
                pin = intent.getStringExtra("pin"),
                cooldownMin = if (intent.hasExtra("cooldownMin")) intent.getIntExtra("cooldownMin", 0) else null,
                followAllSchedules = intent.getBooleanExtra("followAll", false),
            )
            when (val r = platform.strict.activate(req, launchAdmin = false, allowShortTimer = true)) {
                ActivationOutcome.Activated -> Result.ok("strict on ($method)")
                is ActivationOutcome.Refused -> Result.fail("${r.reason}: ${r.message}")
                is ActivationOutcome.NeedsDeviceAdmin -> Result.fail("ADMIN_NOT_ACTIVE")
            }
        }

        DebugCommands.register("snooze-chunk") { intent ->
            val ms = intent.getLongExtra("ms", 0)
            if (ms <= 0) return@register Result.fail("snooze-chunk needs --el ms N with N > 0")
            platform.knobs.snoozeChunkMs = ms
            Result.ok("snooze chunk $ms ms")
        }

        // reset (after graph.wipe()): clock-real, sensors, snooze chunk, device admin, engine memory, overlay
        ResetHooks.add {
            val g: PlatformGraph = platform
            app.debugClock?.let {
                it.real()
                app.debugClockStore?.save(it)
            }
            g.knobs.snoozeChunkMs = null
            g.sensors.real()
            g.strict.removeAdmin()
            g.resetForDebug()
        }

        StateContributors.add {
            val g = platform
            val st = g.engineHost.status.value
            "statuses" to buildJsonArray {
                st.statuses.forEach { s ->
                    add(
                        buildJsonObject {
                            put("scheduleId", s.scheduleId)
                            put("armed", s.armed)
                            put("blocking", s.blocking)
                            put("paused", s.paused)
                            put("timeUsed", s.time?.counter?.used?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as Long?))
                            put("launchesUsed", s.launches?.counter?.used?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as Long?))
                        },
                    )
                }
            }
        }
        StateContributors.add {
            val g = platform
            "platform" to buildJsonObject {
                put("strictDisplay", g.engineHost.status.value.strict.name)
                put("serviceConnected", g.service != null)
                put("listenerConnected", g.listener != null)
                put("clockNow", g.clock.now())
                put("wallOffset", app.debugClock?.wallOffset ?: 0)
                put("elapsedOffset", app.debugClock?.elapsedOffset ?: 0)
                put("sensors", json.encodeToJsonElement(SensorState.serializer(), g.sensors.state()))
                put("adminActive", g.strict.isAdminActive())
            }
        }
    }

    private fun Context.dataGraph() = app.graph
}
