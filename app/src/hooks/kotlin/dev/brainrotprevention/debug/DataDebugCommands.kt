package dev.brainrotprevention.debug

import android.content.Context
import android.util.Base64
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.dataGraph
import dev.brainrotprevention.debug.DebugCommands.Result
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.UsageCounter
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject

/**
 * Extra work for `reset` (SPEC §14.3: remove the device admin, clock-real, debug sensors, snooze chunk).
 * The platform appends its steps here; they run after the DB and prefs are wiped, in order.
 */
object ResetHooks {
    private val hooks = CopyOnWriteArrayList<suspend Context.() -> Unit>()

    fun add(hook: suspend Context.() -> Unit) {
        hooks += hook
    }

    internal suspend fun runAll(context: Context) = hooks.forEach { context.it() }
}

/** Extra top-level fields for `state` (e.g. statuses, sensors, clock). Each returns (key, value). */
object StateContributors {
    private val contributors = CopyOnWriteArrayList<suspend Context.() -> Pair<String, JsonElement>>()

    fun add(contributor: suspend Context.() -> Pair<String, JsonElement>) {
        contributors += contributor
    }

    internal suspend fun collect(context: Context): List<Pair<String, JsonElement>> = contributors.map { context.it() }
}

/** M2 commands: import, export, reset, state (SPEC §14.3 item 2). Files go to `files/debug/`. */
internal object DataDebugCommands {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = false
    }

    fun registerAll() {
        // import --es b64 <base64> | --es path files/debug/seed.json
        DebugCommands.register("import") { intent ->
            val b64 = intent.getStringExtra("b64")
            val path = intent.getStringExtra("path")
            val text = when {
                b64 != null -> try {
                    String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    return@register Result.fail("import: bad base64: ${e.message}")
                }
                path != null -> appFile(path)?.takeIf { it.isFile }?.readText()
                    ?: return@register Result.fail("import: no file $path")
                else -> return@register Result.fail("import needs --es b64 <base64> or --es path files/debug/<f>")
            }
            when (val r = dataGraph.backup.import(text)) {
                is WriteResult.Ok -> Result.ok("imported ${r.value.schedules} schedules, quick block ${r.value.quickBlockApps} apps")
                else -> Result.fail("import refused: ${r.describe()}")
            }
        }

        // export: writes files/debug/export.json
        DebugCommands.register("export") {
            debugFile("export.json").writeText(dataGraph.backup.exportText())
            Result.ok("files/debug/export.json")
        }

        // reset: wipes DB + prefs (ends Strict Mode, clears the emergency log), then the platform's ResetHooks
        DebugCommands.register("reset") {
            dataGraph.wipe()
            ResetHooks.runAll(this)
            Result.ok("reset")
        }

        // state: writes files/debug/state.json = {config, counters, strict, …contributors}
        DebugCommands.register("state") {
            val g = dataGraph
            val snapshot = g.snapshots.load()
            val counters = g.counters.loadAll().values.sortedBy { it.limitId }
            val strict = g.strict.get()
            val obj = buildJsonObject {
                put("config", json.encodeToJsonElement(ConfigSnapshot.serializer(), snapshot))
                put("counters", json.encodeToJsonElement(ListSerializer(UsageCounter.serializer()), counters))
                put("strict", json.encodeToJsonElement(StrictModeRow.serializer(), strict))
                StateContributors.collect(this@register).forEach { (k, v) -> put(k, v) }
            }
            debugFile("state.json").writeText(obj.toString())
            Result.ok("files/debug/state.json")
        }
    }

    private fun Context.debugFile(name: String): File = File(filesDir, "debug").apply { mkdirs() }.resolve(name)

    /** Resolves `files/…` against the app data dir; refuses anything outside it. */
    private fun Context.appFile(path: String): File? {
        val base = dataDir.canonicalFile
        val f = File(base, path).canonicalFile
        return f.takeIf { it.path.startsWith(base.path + File.separator) }
    }
}
