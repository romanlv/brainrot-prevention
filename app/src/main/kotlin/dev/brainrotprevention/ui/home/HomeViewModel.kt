package dev.brainrotprevention.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brainrotprevention.data.PausePreset
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.engine.Periods
import dev.brainrotprevention.engine.QUICK_BLOCK_ID
import dev.brainrotprevention.engine.ScheduleEvaluator
import dev.brainrotprevention.engine.ScheduleStatus
import dev.brainrotprevention.model.Prefs
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.ui.UiDeps
import dev.brainrotprevention.ui.common.Notice
import dev.brainrotprevention.ui.common.notice
import dev.brainrotprevention.ui.format.Format
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeState(
    val loaded: Boolean = false,
    val schedules: List<Schedule> = emptyList(),
    val statuses: Map<Long, ScheduleStatus> = emptyMap(),
    val quickBlock: QuickBlock = QuickBlock(),
    val prefs: Prefs = Prefs(),
    val presets: List<PausePreset> = emptyList(),
    val strict: StrictDisplayState = StrictDisplayState.INACTIVE,
    val now: Long = 0,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val qbStatus: ScheduleStatus? get() = statuses[QUICK_BLOCK_ID]

    /** "Blocking now: Focus, Social cap" or "Nothing blocking · next: Focus at 09:00" (§12). */
    val headline: String
        get() {
            val names = buildList {
                if (qbStatus?.blocking == true) add("Quick Block")
                schedules.forEach { s ->
                    if (statuses[s.id]?.let { it.blocking || (it.armed && it.launchesExhausted) } ==
                        true
                    ) {
                        add(s.name)
                    }
                }
            }
            if (names.isNotEmpty()) return "Blocking now: " + names.joinToString(", ")
            val next = schedules
                .filter { it.enabled && !ScheduleEvaluator.isPaused(it, now) && it.timeWindows.isNotEmpty() }
                .mapNotNull { s -> ScheduleEvaluator.nextWindowStart(s, now, zone)?.let { s to it } }
                .minByOrNull { it.second }
            return if (next ==
                null
            ) {
                "Nothing blocking"
            } else {
                "Nothing blocking · next: ${next.first.name} at ${Format.at(next.second, now, zone)}"
            }
        }

    val anyBlocking: Boolean get() = headline.startsWith("Blocking")
}

class HomeViewModel(private val deps: UiDeps) : ViewModel() {
    private val g = deps.graph

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    val state: StateFlow<HomeState> = combine(
        g.schedules.schedules,
        g.quickBlock.quickBlock,
        g.settings.prefs,
        g.pausePresets.presets,
        deps.status,
    ) { schedules, qb, prefs, presets, status ->
        val c = deps.clock.read()
        HomeState(
            loaded = true,
            schedules = schedules,
            statuses = status.statuses.associateBy { it.scheduleId },
            quickBlock = qb,
            prefs = prefs,
            presets = presets,
            strict = status.strict,
            now = c.now,
            zone = c.zone,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    init {
        viewModelScope.launch { g.snapshots.current() }
    }

    /** Whether Strict Mode locks weakening edits of [id] right now (§9.4): Delete/Pause are refused without a prompt. */
    fun isLocked(id: Long): Boolean {
        val row = g.snapshots.snapshot.value?.strict ?: return false
        val st = state.value
        return dev.brainrotprevention.engine.EditGuard.isLocked(row, st.strict, st.schedules, id)
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private fun run(block: suspend () -> WriteResult<*>) {
        viewModelScope.launch {
            val r = block()
            _notice.value = r.notice()
            if (r.isOk) deps.platform.engine.requestEvaluate()
        }
    }

    private fun now() = deps.clock.now()

    fun pauseFor(id: Long, minutes: Int, recordPreset: Boolean = false) = run {
        val at = now()
        val r = g.schedules.pause(id, at + minutes * Periods.MINUTE)
        if (r.isOk && recordPreset) g.pausePresets.recordUse(minutes, at)
        r
    }

    fun pauseUntilEndOfToday(id: Long) = run {
        val c = deps.clock.read()
        g.schedules.pause(id, Periods.endOfToday(c.now, c.zone, g.settings.getPrefs().dayStartHour))
    }

    fun pauseUntil(id: Long, at: Long) = run {
        if (at <=
            now()
        ) {
            WriteResult.Invalid(dev.brainrotprevention.engine.ValidationError("pause", "pick a time in the future"))
        } else {
            g.schedules.pause(id, at)
        }
    }

    fun turnOff(id: Long) = run { g.schedules.setEnabled(id, false) }

    fun setEnabled(id: Long, enabled: Boolean) = run { g.schedules.setEnabled(id, enabled) }

    fun resume(id: Long) = run { g.schedules.resume(id) }

    fun startNow(id: Long, minutes: Int?) = run {
        g.schedules.startNow(id, if (minutes == null) Schedule.UNTIL_STOPPED else now() + minutes * Periods.MINUTE)
    }

    fun stopNow(id: Long) = run { g.schedules.stopNow(id) }

    fun delete(id: Long) = run { g.schedules.delete(id) }

    fun duplicate(id: Long) = run {
        val s = g.schedules.get(id) ?: return@run WriteResult.NotFound("schedule $id")
        val all = g.schedules.getAll()
        g.schedules.save(duplicateOf(s, all))
    }

    fun startQuickBlock(durationMin: Int?) = run {
        val q = g.quickBlock.get()
        if (q.isEmpty) {
            WriteResult.Invalid(dev.brainrotprevention.engine.ValidationError("quickBlock", "add apps or sites to Quick Block first"))
        } else {
            g.quickBlock.start(durationMin?.let { now() + it * Periods.MINUTE })
        }
    }

    fun stopQuickBlock() = run { g.quickBlock.stop() }

    companion object {
        /** A copy with fresh ids, not paused/started, at the end of the list, named "‹name› copy". */
        fun duplicateOf(s: Schedule, all: List<Schedule>): Schedule {
            val base = "${s.name} copy".take(40)
            var name = base
            var n = 2
            while (all.any { it.name == name }) name = "${s.name.take(34)} copy $n".take(40).also { n++ }
            return s.copy(
                id = 0,
                name = name,
                sortOrder = (all.maxOfOrNull { it.sortOrder } ?: 0) + 1,
                pausedUntil = null,
                manualUntil = null,
                createdAt = 0,
                updatedAt = 0,
                timeWindows = s.timeWindows.map { it.copy(id = 0) },
                limits = s.limits.map { it.copy(id = 0) },
                wifi = s.wifi.map { it.copy(id = 0) },
                locations = s.locations.map { it.copy(id = 0) },
                webRules = s.webRules.map { it.copy(id = 0) },
            )
        }
    }
}
