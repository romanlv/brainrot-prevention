package dev.brainrotprevention.data

import dev.brainrotprevention.data.db.QuickBlockAppEntity
import dev.brainrotprevention.data.db.QuickBlockEntity
import dev.brainrotprevention.data.db.QuickBlockWebEntity
import dev.brainrotprevention.data.db.quickBlockOf
import dev.brainrotprevention.engine.EditGuard
import dev.brainrotprevention.engine.ScheduleValidator
import dev.brainrotprevention.engine.ValidationError
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.model.WebRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Quick Block (§5.5): the single row and its items. */
class QuickBlockRepository internal constructor(private val c: DataCore) {
    private val dao get() = c.db.quickBlock()

    val quickBlock: Flow<QuickBlock> = c.db.quickBlock().let { d ->
        combine(d.observeRow(), d.observeApps(), d.observeWeb()) { r, a, w -> quickBlockOf(r, a, w) }.distinctUntilChanged()
    }

    suspend fun get(): QuickBlock = quickBlockOf(dao.getRow(), dao.getApps(), dao.getWeb())

    /** Saves items, flags and active/until together (EditGuard.checkQuickBlock). Rules are normalised. */
    suspend fun save(new: QuickBlock): WriteResult<Unit> = c.write { g ->
        val rules = new.webRules.mapIndexed { i, r ->
            ScheduleValidator.normalizeRule(r)
                ?: return@write WriteResult.Invalid(
                    ValidationError("quickBlock.webRules[$i]", "invalid ${r.kind.name.lowercase()} \"${r.value}\"")
                )
        }
        val n = new.copy(webRules = rules)
        ScheduleValidator.validateApps(n.apps, "quickBlock.apps")?.let { return@write WriteResult.Invalid(it) }
        ScheduleValidator.validateRules(n.webRules, "quickBlock.webRules")?.let { return@write WriteResult.Invalid(it) }
        WriteResult.locked(EditGuard.checkQuickBlock(g.strict, g.display, g.schedules, g.snapshot.quickBlock, n, g.now))
            ?.let { return@write it }
        c.tx { write(n) }
        WriteResult.ok()
    }

    /** Start Quick Block; [until] null = no timer (V2-N5). An empty Quick Block is refused (Invalid). */
    suspend fun start(until: Long?): WriteResult<Unit> {
        val cur = get()
        if (cur.isEmpty) return WriteResult.Invalid(ValidationError("quickBlock", "add at least one app or website first"))
        return save(cur.copy(active = true, until = until))
    }

    /** Stop; refused (QUICK_BLOCK_STOPPED) while locked. */
    suspend fun stop(): WriteResult<Unit> = save(get().copy(active = false, until = null))

    /** Engine cleanup: the timer expired, so `active = false` (not a weakening). */
    suspend fun markExpired() = c.plain {
        val row = dao.getRow() ?: return@plain
        dao.upsertRow(row.copy(active = false))
    }

    /** Replaces row + items in the caller's transaction (also used by import). */
    internal suspend fun write(q: QuickBlock) {
        dao.upsertRow(QuickBlockEntity(1, q.active, q.until, q.blockNotifications))
        writeItems(q.apps, q.webRules)
    }

    internal suspend fun writeItems(apps: List<String>, rules: List<WebRule>) {
        val oldApps = dao.getApps().map { it.packageName }.toSet()
        (oldApps - apps.toSet()).takeIf { it.isNotEmpty() }?.let { dao.deleteApps(it.toList()) }
        dao.insertApps(apps.filter { it !in oldApps }.map { QuickBlockAppEntity(it) })
        val keep = rules.map { it.kind to it.value }.toSet()
        dao.getWeb().filter { (it.kind to it.value) !in keep }.takeIf { it.isNotEmpty() }?.let { dao.deleteWeb(it) }
        if (rules.isNotEmpty()) dao.upsertWeb(rules.map { QuickBlockWebEntity(it.kind, it.value, it.anywhereInUrl) })
    }
}
