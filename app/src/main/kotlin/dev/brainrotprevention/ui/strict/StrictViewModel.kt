package dev.brainrotprevention.ui.strict

import android.content.Intent
import androidx.lifecycle.ViewModel
import dev.brainrotprevention.engine.ActivationRefusal
import dev.brainrotprevention.engine.EmergencyResult
import dev.brainrotprevention.engine.Periods
import dev.brainrotprevention.engine.ScheduleStatus
import dev.brainrotprevention.engine.StrictActivation
import dev.brainrotprevention.engine.StrictPolicy
import dev.brainrotprevention.model.EmergencyUnblock
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.ui.UiDeps
import dev.brainrotprevention.ui.common.Notice
import dev.brainrotprevention.ui.common.notice
import dev.brainrotprevention.ui.format.Format
import dev.brainrotprevention.ui.platform.UiActivation
import dev.brainrotprevention.ui.platform.UiApp
import dev.brainrotprevention.ui.platform.UiGrant
import dev.brainrotprevention.ui.platform.UiUnlock
import dev.brainrotprevention.ui.retryReporting
import dev.brainrotprevention.ui.safeScope
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The activation form (Strict tab while inactive). */
data class StrictForm(
    val options: Int = StrictOptions.LOCK_EDITING or StrictOptions.BLOCK_SETTINGS,
    val method: StrictMethod = StrictMethod.TIMER,
    val timerMs: Long = Periods.HOUR,
    val pin: String = "",
    val pinConfirm: String = "",
    val cooldownMin: Int = 15,
    val followAll: Boolean = true,
) {
    fun toggle(option: Int, on: Boolean): StrictForm {
        var o = if (on) options or option else options and option.inv()
        // §9.2: BLOCK_UNINSTALL requires BLOCK_SETTINGS — turning uninstall on turns Settings on; Settings off turns uninstall off.
        if (on && option == StrictOptions.BLOCK_UNINSTALL) o = o or StrictOptions.BLOCK_SETTINGS
        if (!on && option == StrictOptions.BLOCK_SETTINGS) o = o and StrictOptions.BLOCK_UNINSTALL.inv()
        return copy(options = o)
    }

    val pinProblem: String?
        get() = when {
            method != StrictMethod.PIN -> null
            !Regex("^\\d{4,8}$").matches(pin) -> "Use 4 to 8 digits."
            pin != pinConfirm -> "The two PINs don't match."
            else -> null
        }

    fun request() = StrictActivation(
        method = method,
        options = options,
        durationMs = timerMs.takeIf { method == StrictMethod.TIMER },
        pin = pin.takeIf { method == StrictMethod.PIN },
        cooldownMin = cooldownMin.takeIf { method == StrictMethod.COOLDOWN },
        followAllSchedules = method == StrictMethod.SCHEDULES && followAll,
    )
}

/** What the activation confirmation shows (§9.2): the method and how it ends, what gets locked, what is followed. */
data class StrictSummary(val method: String, val ends: String, val locks: List<String>, val applies: String) {
    companion object {
        fun of(f: StrictForm, schedules: List<Schedule>, now: Long, zone: ZoneId): StrictSummary {
            val (method, ends) = when (f.method) {
                StrictMethod.TIMER ->
                    "Timer" to
                        "Ends in ${Format.duration(f.timerMs)}, at ${Format.at(now + f.timerMs, now, zone)}."
                StrictMethod.SCHEDULES ->
                    "Schedules" to
                        "Active while a followed schedule blocks. When none is blocking, it can be turned off after a 5-minute wait."
                StrictMethod.CHARGER -> "Charger" to "Can only be unlocked while the phone is plugged in."
                StrictMethod.PIN -> "PIN" to "Can only be unlocked with the PIN that was just set."
                StrictMethod.COOLDOWN -> "Cooldown" to "Unlocks after you wait ${f.cooldownMin} minutes on the Strict screen."
            }
            val locks = listOfNotNull(
                "Editing: no pausing, deleting or loosening schedules".takeIf { StrictOptions.has(f.options, StrictOptions.LOCK_EDITING) },
                "The Settings app".takeIf { StrictOptions.has(f.options, StrictOptions.BLOCK_SETTINGS) },
                "Uninstalling this app (device admin)".takeIf { StrictOptions.has(f.options, StrictOptions.BLOCK_UNINSTALL) },
            )
            val blocklist = schedules.filter { it.mode == Mode.BLOCKLIST }
            val applies = when {
                f.method != StrictMethod.SCHEDULES && schedules.any { it.mode == Mode.ALLOWLIST } ->
                    "Covers every schedule and Quick Block, including “Allow only selected” schedules" +
                        (if (StrictOptions.has(f.options, StrictOptions.BLOCK_SETTINGS)) " (Settings is blocked while they run)." else ".")
                f.method != StrictMethod.SCHEDULES -> "Covers every schedule and Quick Block."
                f.followAll ->
                    "Follows all schedules and Quick Block, including ones you add later" +
                        (blocklist.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = ": ") { it.name } ?: "") + "."
                else -> blocklist.filter { it.strictFollowed }.map { it.name }.let { names ->
                    if (names.isEmpty()) "Follows no schedules yet." else "Follows: ${names.joinToString(", ")}."
                }
            }
            return StrictSummary(method, ends, locks, applies)
        }
    }
}

/** A wait timed on screen by policy elapsed time (cooldown, standby turn-off, emergency countdown). */
enum class WaitKind { COOLDOWN, STANDBY_OFF, EMERGENCY }

data class Wait(val kind: WaitKind, val startedElapsed: Long, val durationMs: Long)

enum class EmergencyStep { EXPLAIN, PLEDGE, COUNTDOWN }

data class StrictUi(
    val loaded: Boolean = false,
    val row: StrictModeRow = StrictModeRow(),
    val display: StrictDisplayState = StrictDisplayState.INACTIVE,
    val schedules: List<Schedule> = emptyList(),
    val emergencyLog: List<EmergencyUnblock> = emptyList(),
    val excludedBrowsers: Set<String> = emptySet(),
    val now: Long = 0,
    val elapsed: Long = 0,
    val zone: ZoneId = ZoneId.systemDefault(),
    val statuses: Map<Long, ScheduleStatus> = emptyMap(),
) {
    /** SCHEDULES: the followed schedules blocking now, with their until lines (for the unlock condition). */
    val blockingNow: String get() = Format.followedBlocking(row, schedules, statuses, now, zone)

    val pausedOrOff: List<Schedule> get() = StrictPolicy.pausedOrOff(schedules, now).let { ids -> schedules.filter { it.id in ids } }
    val notCovered: List<Schedule> get() = schedules.filter { it.mode == Mode.ALLOWLIST }
    val followable: List<Schedule> get() = schedules.filter { it.mode == Mode.BLOCKLIST }
    val emergencyAvailableAt: Long? get() = StrictPolicy.emergencyAvailableAt(emergencyLog, now)
    val followedNames: List<String>
        get() = StrictPolicy.followed(row, schedules).let { f ->
            schedules.filter { it.id in f.scheduleIds }.map { it.name } + if (f.quickBlock) listOf("Quick Block") else emptyList()
        }
}

class StrictViewModel(private val deps: UiDeps) : ViewModel() {
    private val g = deps.graph
    private val port = deps.platform.strict

    private val _form = MutableStateFlow(StrictForm())
    val form: StateFlow<StrictForm> = _form.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val _adminIntent = MutableStateFlow<Intent?>(null)

    /** Non-null: the UI launches it (ACTION_ADD_DEVICE_ADMIN) and calls [onAdminResult]. */
    val adminIntent: StateFlow<Intent?> = _adminIntent.asStateFlow()

    private val _wait = MutableStateFlow<Wait?>(null)
    val wait: StateFlow<Wait?> = _wait.asStateFlow()

    private val _emergency = MutableStateFlow<EmergencyStep?>(null)
    val emergency: StateFlow<EmergencyStep?> = _emergency.asStateFlow()

    private val _pinMessage = MutableStateFlow<String?>(null)
    val pinMessage: StateFlow<String?> = _pinMessage.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _unsupported = MutableStateFlow<List<UiApp>>(emptyList())
    val unsupported: StateFlow<List<UiApp>> = _unsupported.asStateFlow()

    val state: StateFlow<StrictUi> = combine(
        g.strict.strict,
        g.strict.emergencyLog,
        g.schedules.schedules,
        g.settings.excludedBrowsers,
        deps.status,
    ) { row, log, schedules, excluded, status ->
        val c = deps.clock.read()
        StrictUi(
            true, row, status.strict, schedules, log, excluded, c.now, c.elapsed, c.zone,
            status.statuses.associateBy {
                it.scheduleId
            }
        )
    }.retryReporting().stateIn(safeScope, SharingStarted.WhileSubscribed(5_000), StrictUi())

    init {
        safeScope.launch { g.snapshots.current() }
        refreshUnsupported()
    }

    fun refreshUnsupported() {
        safeScope.launch { _unsupported.value = runCatching { deps.platform.apps.unsupportedBrowsers() }.getOrDefault(emptyList()) }
    }

    fun editForm(f: (StrictForm) -> StrictForm) = _form.update(f)

    fun dismissNotice() {
        _notice.value = null
    }

    private fun launchBusy(block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        safeScope.launch {
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }

    private val _confirm = MutableStateFlow<StrictSummary?>(null)

    /** Non-null: the "Turn on Strict Mode?" confirmation is showing this summary. */
    val confirm: StateFlow<StrictSummary?> = _confirm.asStateFlow()

    /** "Turn on Strict Mode": checks the PIN, then asks for confirmation with a summary (it is hard to undo). */
    fun requestActivate() {
        if (_busy.value) return
        val f = _form.value
        f.pinProblem?.let {
            _notice.value = Notice.Message("Check the PIN", it)
            return
        }
        val c = deps.clock.read()
        _confirm.value = StrictSummary.of(f, state.value.schedules, c.now, c.zone)
    }

    fun cancelActivate() {
        _confirm.value = null
    }

    /** Confirmed in the summary dialog. */
    fun activate() {
        _confirm.value = null
        launchBusy {
            val f = _form.value
            f.pinProblem?.let {
                _notice.value = Notice.Message("Check the PIN", it)
                return@launchBusy
            }
            handle(port.activate(f.request()))
        }
    }

    private fun handle(o: UiActivation) {
        when (o) {
            UiActivation.Activated -> {
                _form.update { it.copy(pin = "", pinConfirm = "") }
                deps.platform.engine.requestEvaluate()
            }
            is UiActivation.Refused -> _notice.value = Notice.Message(
                "Can't turn on Strict Mode",
                o.reason?.let(Format::activationRefusal) ?: o.message,
                fix = when (o.reason) {
                    ActivationRefusal.ACCESSIBILITY_NOT_CONNECTED, ActivationRefusal.IN_ACCESSIBILITY_SHORTCUT -> UiGrant.ACCESSIBILITY
                    ActivationRefusal.NO_USAGE_ACCESS -> UiGrant.USAGE_ACCESS
                    else -> null
                },
            )
            is UiActivation.NeedsDeviceAdmin -> _adminIntent.value = o.intent
        }
    }

    /** After ACTION_ADD_DEVICE_ADMIN returns: retry; a cancelled admin prompt refuses activation (§9.2). */
    fun onAdminResult() {
        _adminIntent.value = null
        launchBusy {
            when (val o = port.activate(_form.value.request())) {
                is UiActivation.NeedsDeviceAdmin -> handle(UiActivation.Refused(ActivationRefusal.ADMIN_NOT_ACTIVE, ""))
                else -> handle(o)
            }
        }
    }

    fun setFollowed(s: Schedule, on: Boolean) {
        safeScope.launch { _notice.value = g.schedules.save(s.copy(strictFollowed = on)).notice() }
    }

    fun resumeAll() {
        safeScope.launch {
            val now = deps.clock.now()
            state.value.schedules.forEach { s ->
                if (!s.enabled) g.schedules.setEnabled(s.id, true)
                if (s.pausedUntil != null && s.pausedUntil!! > now) g.schedules.resume(s.id)
            }
            deps.platform.engine.requestEvaluate()
        }
    }

    fun setExcluded(pkg: String, excluded: Boolean) {
        safeScope.launch {
            val cur = g.settings.getExcludedBrowsers()
            _notice.value = g.settings.setExcludedBrowsers(if (excluded) cur + pkg else cur - pkg).notice()
        }
    }

    private fun afterUnlock(r: UiUnlock, onWrongPin: ((Long?) -> Unit)? = null) {
        when (r) {
            UiUnlock.Unlocked -> {
                _wait.value = null
                _pinMessage.value = null
                deps.platform.engine.requestEvaluate()
            }
            is UiUnlock.WrongPin -> onWrongPin?.invoke(r.lockedUntil)
            is UiUnlock.Refused -> {
                val text = Format.unlockRefusal(r.reason, r.remainingMs)
                if (onWrongPin != null) _pinMessage.value = text else _notice.value = Notice.Message("Not yet", text)
            }
        }
    }

    fun unlockWithCharger() = launchBusy { afterUnlock(port.unlockWithCharger()) }

    fun unlockWithPin(pin: String) = launchBusy {
        afterUnlock(port.unlockWithPin(pin)) { lockedUntil ->
            _pinMessage.value = if (lockedUntil != null) {
                "Wrong PIN. Too many tries: wait until ${Format.at(lockedUntil, deps.clock.now(), deps.clock.zone())}."
            } else {
                "Wrong PIN."
            }
        }
    }

    fun startWait(kind: WaitKind) {
        val d = when (kind) {
            WaitKind.COOLDOWN -> state.value.row.cooldownMin * Periods.MINUTE
            WaitKind.STANDBY_OFF -> StrictPolicy.STANDBY_TURN_OFF_COOLDOWN_MS
            WaitKind.EMERGENCY -> StrictPolicy.EMERGENCY_COUNTDOWN_MS
        }
        _wait.value = Wait(kind, deps.clock.elapsed(), d)
    }

    /** ON_STOP or the user leaving: the on-screen wait starts over (§9.5). */
    fun cancelWait() {
        val w = _wait.value ?: return
        _wait.value = null
        if (w.kind == WaitKind.EMERGENCY && _emergency.value == EmergencyStep.COUNTDOWN) _emergency.value = EmergencyStep.PLEDGE
    }

    fun remaining(w: Wait): Long = StrictPolicy.waitRemaining(w.startedElapsed, deps.clock.elapsed(), w.durationMs)

    /** Called by the countdown UI when [remaining] reaches 0. */
    fun finishWait() {
        val w = _wait.value ?: return
        launchBusy {
            when (w.kind) {
                WaitKind.COOLDOWN -> afterUnlock(port.finishCooldown(w.startedElapsed))
                WaitKind.STANDBY_OFF -> afterUnlock(port.finishStandbyTurnOff(w.startedElapsed))
                WaitKind.EMERGENCY -> finishEmergency(w.startedElapsed)
            }
        }
    }

    fun extendTimer(extraMs: Long) = launchBusy {
        _notice.value = port.extendTimer(extraMs).notice()
        deps.platform.engine.requestEvaluate()
    }

    // ---- emergency (§9.7) ----
    val pledge: String get() = port.pledge

    fun openEmergency() {
        _emergency.value = EmergencyStep.EXPLAIN
    }

    fun closeEmergency() {
        if (_wait.value?.kind == WaitKind.EMERGENCY) _wait.value = null
        _emergency.value = null
    }

    fun emergencyConfirmed() {
        _emergency.value = EmergencyStep.PLEDGE
    }

    fun pledgeTyped(text: String): Boolean {
        if (text.trim() != pledge) return false
        _emergency.value = EmergencyStep.COUNTDOWN
        startWait(WaitKind.EMERGENCY)
        return true
    }

    private suspend fun finishEmergency(started: Long) {
        when (val r = port.finishEmergency(started)) {
            is EmergencyResult.Done -> {
                _wait.value = null
                _emergency.value = null
                deps.platform.engine.requestEvaluate()
                _notice.value =
                    Notice.Message("Strict Mode is off", "Your schedules are unchanged. Emergency unblock is available again in 30 days.")
            }
            is EmergencyResult.CountdownRunning -> Unit
            is EmergencyResult.NotAvailableUntil -> {
                closeEmergency()
                _notice.value =
                    Notice.Message("Not available", "Emergency unblock is available again on ${Format.dateLong(r.at, deps.clock.zone())}.")
            }
            EmergencyResult.NotOn -> closeEmergency()
        }
    }
}
