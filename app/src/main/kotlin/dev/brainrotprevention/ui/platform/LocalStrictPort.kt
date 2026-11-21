package dev.brainrotprevention.ui.platform

import dev.brainrotprevention.data.DataGraph
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.engine.ActivationResult
import dev.brainrotprevention.engine.EmergencyResult
import dev.brainrotprevention.engine.Engine
import dev.brainrotprevention.engine.StrictActivation
import dev.brainrotprevention.engine.StrictFacts
import dev.brainrotprevention.engine.StrictPolicy
import dev.brainrotprevention.engine.UnlockResult
import dev.brainrotprevention.model.StrictOptions

/**
 * [StrictPort] built only on `core:data` + `StrictPolicy`. Used by UI tests and as the fallback when no platform
 * graph is available. It cannot add or remove the device admin: BLOCK_UNINSTALL is refused unless [facts] says
 * the admin is active, and [onDeactivated] is the hook for removing it.
 */
class LocalStrictPort(
    private val graph: DataGraph,
    private val facts: suspend () -> StrictFacts,
    private val charger: () -> Boolean = { false },
    private val onDeactivated: suspend () -> Unit = {},
) : StrictPort {
    private val engine = Engine()

    override val pledge: String = PLEDGE

    override suspend fun activate(req: StrictActivation): UiActivation {
        val c = graph.clock.read()
        val r = StrictPolicy.activate(
            req,
            facts(),
            graph.schedules.getAll(),
            graph.strict.get(),
            c.now,
            c.elapsed,
            quickBlock = graph.quickBlock.get(),
        )
        return when (r) {
            is ActivationResult.Activated -> {
                graph.strict.writePolicyResult(r.row)
                UiActivation.Activated
            }
            is ActivationResult.Refused -> UiActivation.Refused(r.reason, "")
        }
    }

    private suspend fun apply(r: UnlockResult): UiUnlock = when (r) {
        is UnlockResult.Unlocked -> {
            graph.strict.writePolicyResult(r.row)
            onDeactivated()
            UiUnlock.Unlocked
        }
        is UnlockResult.WrongPin -> {
            graph.strict.writePolicyResult(r.row)
            UiUnlock.WrongPin(r.lockedUntil)
        }
        is UnlockResult.Refused -> UiUnlock.Refused(r.reason, r.remainingMs)
    }

    override suspend fun unlockWithCharger(): UiUnlock = apply(StrictPolicy.unlockWithCharger(graph.strict.get(), charger()))

    override suspend fun unlockWithPin(pin: String): UiUnlock {
        val c = graph.clock.read()
        return apply(StrictPolicy.unlockWithPin(graph.strict.get(), pin, c.now, c.elapsed))
    }

    override suspend fun finishCooldown(startedElapsed: Long): UiUnlock =
        apply(StrictPolicy.unlockAfterCooldown(graph.strict.get(), startedElapsed, graph.clock.elapsed()))

    override suspend fun finishStandbyTurnOff(startedElapsed: Long): UiUnlock {
        val snap = graph.snapshots.load()
        val display = engine.status(snap, graph.clock.read(), graph.counters.loadAll()).strict
        return apply(StrictPolicy.turnOffFromStandby(graph.strict.get(), display, startedElapsed, graph.clock.elapsed()))
    }

    override suspend fun extendTimer(extraMs: Long): WriteResult<Unit> = graph.strict.extendTimer(extraMs)

    override suspend fun finishEmergency(startedElapsed: Long): EmergencyResult {
        val c = graph.clock.read()
        val r = StrictPolicy.emergencyUnblock(graph.strict.get(), graph.strict.getEmergencyLog(), c.now, startedElapsed, c.elapsed)
        if (r is EmergencyResult.Done) {
            graph.strict.writeEmergency(r.row, r.entry)
            onDeactivated()
        }
        return r
    }

    companion object {
        const val PLEDGE = "I am ending Strict Mode because of a real emergency. I accept that I can do this only once in 30 days."

        /** Facts for tests: everything granted, admin active only if asked. */
        fun allGranted(admin: Boolean = false) = StrictFacts(
            accessibilityConnected = true,
            usageAccessGranted = true,
            inAccessibilityShortcut = false,
            adminActive = admin,
        )

        fun needsAdmin(options: Int) = StrictOptions.has(options, StrictOptions.BLOCK_UNINSTALL)
    }
}
