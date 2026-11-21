package dev.brainrotprevention.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.brainrotprevention.data.DataGraph
import dev.brainrotprevention.engine.Engine
import dev.brainrotprevention.engine.StatusView
import dev.brainrotprevention.model.PolicyClock
import dev.brainrotprevention.ui.platform.UiPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn

/** Manual DI for the UI: the data graph plus the platform ports. */
class UiDeps(val graph: DataGraph, val platform: UiPlatform, val tickMs: Long = 1_000) {
    val clock: PolicyClock get() = graph.clock

    private val engine = Engine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Ticks immediately, then every [periodMs]. */
    fun ticker(periodMs: Long = tickMs): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(periodMs)
        }
    }

    /**
     * Live statuses for the UI: `Engine.status` on the current snapshot and counters, re-run every tick (no ledger
     * tick, §engine API "UI, editor"). The same computation as the data layer's StrictGate, so the UI's displayed
     * strict state matches the guard's.
     */
    val status: Flow<StatusView> by lazy {
        val snapshots = flow {
            if (graph.snapshots.snapshot.value == null) graph.snapshots.current()
            emitAll(graph.snapshots.snapshot.filterNotNull())
        }
        combine(snapshots, graph.counters.counters, ticker()) { snap, counters, _ ->
            engine.status(snap, clock.read(), counters, platform.engine.sensors())
        }.flowOn(Dispatchers.Default)
            // One upstream: the Engine instance (and its per-snapshot cache) is used from one coroutine at a time.
            .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)
    }
}

val LocalUiDeps = staticCompositionLocalOf<UiDeps> { error("UiDeps not provided") }

/** A ViewModel built from [UiDeps] (manual DI). */
@Composable
inline fun <reified VM : ViewModel> depsViewModel(deps: UiDeps, key: String? = null, crossinline create: (UiDeps) -> VM): VM =
    viewModel(key = key, factory = viewModelFactory { initializer { create(deps) } })
