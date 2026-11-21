package dev.brainrotprevention.data

import androidx.room.withTransaction
import dev.brainrotprevention.data.db.BrpDatabase
import dev.brainrotprevention.data.db.quickBlockOf
import dev.brainrotprevention.data.db.toModel
import dev.brainrotprevention.model.BrowserEntry
import dev.brainrotprevention.model.ConfigSnapshot
import dev.brainrotprevention.model.StrictGuards
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Facts the platform resolves (§6.3, §7.2, §7.7, §7.9, §9.3); passed in, never read here. */
data class PlatformFacts(
    val browsers: List<BrowserEntry> = emptyList(),
    val strictGuards: StrictGuards = StrictGuards(),
    val exemptPackages: Set<String> = emptySet(),
    val exemptComponents: Set<String> = emptySet(),
    val allowlistEssentials: Set<String> = emptySet(),
    val unsupportedBrowsers: Set<String> = emptySet(),
    val webApps: Map<String, String> = emptyMap(),
    val ownPackage: String = "",
    val redirectUriPrefix: String = "",
) {
    companion object {
        fun of(s: ConfigSnapshot) = PlatformFacts(
            s.browsers, s.strictGuards, s.exemptPackages, s.exemptComponents, s.allowlistEssentials,
            s.unsupportedBrowsers, s.webApps, s.ownPackage, s.redirectUriPrefix,
        )
    }
}

/**
 * The engine's configuration snapshot (§3 rule 4): built from DB + prefs + [PlatformFacts], held in memory
 * ([snapshot]) and cached to `files/snapshot.json`. If building fails, the cached file is used.
 * A new instance is published only when the content changed, so the engine's per-instance cache stays valid.
 */
class SnapshotStore internal constructor(
    private val db: BrpDatabase,
    private val prefs: PrefsStore,
    val cacheFile: File,
) {
    private val state = MutableStateFlow<ConfigSnapshot?>(null)
    private val mutex = Mutex()

    @Volatile private var facts: PlatformFacts? = null

    /** Last good snapshot; null until [current] / [rebuild] ran once. */
    val snapshot: StateFlow<ConfigSnapshot?> = state.asStateFlow()

    /** The in-memory snapshot, loading it (DB, else cache) on first use. */
    suspend fun current(): ConfigSnapshot = state.value ?: load()

    /** Replaces the platform facts and rebuilds. */
    suspend fun setPlatformFacts(f: PlatformFacts): ConfigSnapshot {
        facts = f
        return rebuild()
    }

    /** Rebuild from DB + prefs; on failure keep the last good snapshot, else fall back to the cache file. */
    suspend fun load(): ConfigSnapshot = try {
        rebuild()
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        mutex.withLock {
            state.value ?: (readCache() ?: ConfigSnapshot()).also { state.value = it }
        }
    }

    /** Called by every repository mutation. Throws if the DB can't be read (see [load]). */
    suspend fun rebuild(): ConfigSnapshot = mutex.withLock {
        val f = facts ?: readCache()?.let { PlatformFacts.of(it) }.also { facts = it } ?: PlatformFacts()
        val built = build(f)
        val old = state.value
        if (old != null && old == built) return@withLock old
        state.value = built
        writeCache(built)
        built
    }

    private suspend fun build(f: PlatformFacts): ConfigSnapshot {
        val p = prefs.get()
        return db.withTransaction {
            ConfigSnapshot(
                schedules = db.schedules().getAll().map { it.toModel() },
                quickBlock = db.quickBlock().let { q -> quickBlockOf(q.getRow(), q.getApps(), q.getWeb()) },
                strict = db.strict().get().toModel(),
                emergencyLog = db.strict().getEmergencyLog().map { it.toModel() },
                excludedBrowsers = db.excludedBrowsers().getAll().mapTo(LinkedHashSet()) { it.packageName },
                prefs = p,
                browsers = f.browsers,
                strictGuards = f.strictGuards,
                exemptPackages = f.exemptPackages,
                exemptComponents = f.exemptComponents,
                allowlistEssentials = f.allowlistEssentials,
                unsupportedBrowsers = f.unsupportedBrowsers,
                webApps = f.webApps,
                ownPackage = f.ownPackage,
                redirectUriPrefix = f.redirectUriPrefix,
            )
        }
    }

    /** The cached file, or null if missing/corrupt. */
    fun readCache(): ConfigSnapshot? = try {
        if (cacheFile.isFile) json.decodeFromString(ConfigSnapshot.serializer(), cacheFile.readText()) else null
    } catch (_: Exception) {
        null
    }

    private fun writeCache(s: ConfigSnapshot) {
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(json.encodeToString(ConfigSnapshot.serializer(), s))
            Files.move(tmp.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            // The in-memory snapshot is authoritative; a failed cache write only loses the fallback.
        }
    }

    /** Forget the in-memory snapshot (debug reset); the next [current] rebuilds. */
    internal suspend fun clearMemory() = mutex.withLock { state.value = null }

    companion object {
        const val FILE = "snapshot.json"
        internal val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}
