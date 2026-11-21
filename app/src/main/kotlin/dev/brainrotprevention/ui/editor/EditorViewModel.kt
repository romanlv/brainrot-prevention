package dev.brainrotprevention.ui.editor

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dev.brainrotprevention.engine.StatusCalculator
import dev.brainrotprevention.model.AllowlistWeb
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.UsageCounter
import dev.brainrotprevention.ui.UiDeps
import dev.brainrotprevention.ui.common.Notice
import dev.brainrotprevention.ui.common.notice
import dev.brainrotprevention.ui.format.Format
import dev.brainrotprevention.ui.platform.UiApp
import dev.brainrotprevention.ui.retryReporting
import dev.brainrotprevention.ui.safeScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pending "Also block instagram.com?" offer for a newly added app (§12, PL-m14). */
data class DomainOffer(val pkg: String, val label: String, val domains: List<String>)

@OptIn(FlowPreview::class)
class EditorViewModel(
    private val deps: UiDeps,
    private val scheduleId: Long,
    template: Template?,
    startBlank: Boolean = false,
    private val savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    private val g = deps.graph

    /** A draft restored after process death (or null). */
    private val restored: Schedule? = savedState.getJson(KEY_DRAFT)

    /** Null while loading an existing schedule. */
    private val _draft = MutableStateFlow<Schedule?>(
        restored ?: if (scheduleId == 0L) template?.schedule() ?: EditorOps.blank().takeIf { startBlank } else null,
    )
    val draft: StateFlow<Schedule?> = _draft.asStateFlow()

    /** The draft as it was when editing began; Back asks before discarding anything that differs from it. */
    private var initial: Schedule? = savedState.getJson<Schedule>(KEY_INITIAL) ?: _draft.value

    /** New schedule without a template yet: show the template chooser. */
    private val _choosingTemplate = MutableStateFlow(restored == null && scheduleId == 0L && template == null && !startBlank)
    val choosingTemplate: StateFlow<Boolean> = _choosingTemplate.asStateFlow()

    /**
     * The website/keyword field (hoisted so Save can add what was typed, §12). Compose snapshot state, written
     * synchronously from `onValueChange`: a flow collected one frame later drops characters under fast typing or IME
     * composition (review round 2, diff minor 5).
     */
    private val _webInput = mutableStateOf(savedState.getJson<WebInput>(KEY_WEB) ?: WebInput())
    val webInput: State<WebInput> get() = _webInput

    var original: Schedule? = null
        private set

    private val _apps = MutableStateFlow<List<UiApp>?>(null)
    val apps: StateFlow<List<UiApp>?> = _apps.asStateFlow()

    private val _essentials = MutableStateFlow<List<UiApp>>(emptyList())
    val essentials: StateFlow<List<UiApp>> = _essentials.asStateFlow()

    val counters: StateFlow<Map<Long, UsageCounter>> = g.counters.counters.retryReporting().stateIn(
        safeScope,
        SharingStarted.Eagerly,
        emptyMap()
    )

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val _alwaysPrompt = MutableStateFlow(false)
    val alwaysPrompt: StateFlow<Boolean> = _alwaysPrompt.asStateFlow()

    private val _allowlistPrompt = MutableStateFlow(false)
    val allowlistPrompt: StateFlow<Boolean> = _allowlistPrompt.asStateFlow()

    private val _domainOffer = MutableStateFlow<DomainOffer?>(null)
    val domainOffer: StateFlow<DomainOffer?> = _domainOffer.asStateFlow()

    /** True when the editor should close (saved, deleted, or the schedule no longer exists). */
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /** A save or delete is in flight: both Save buttons are disabled, a second tap does nothing. */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private var notFound = false

    init {
        // A new schedule keeps its starting point (the template) in the saved state; an existing one re-reads it from
        // the database instead, so only one copy of a large schedule is stored (review round 2, diff minor 4).
        if (scheduleId == 0L) initial?.let { if (!savedState.contains(KEY_INITIAL)) savedState.setJson(KEY_INITIAL, it) }
        safeScope.launch {
            if (scheduleId != 0L) {
                val s = g.schedules.get(scheduleId)
                original = s
                if (s == null) {
                    notFound = true
                    _notice.value = Notice.Message("Not found", "This schedule was deleted.")
                } else {
                    initial = s
                    if (restored == null) _draft.value = s
                }
            }
        }
        // Debounced: each keystroke in the name field must not re-encode the whole draft on the main thread.
        safeScope.launch { _draft.debounce(SAVE_DEBOUNCE_MS).collect { d -> if (d != null) savedState.setJson(KEY_DRAFT, d) } }
        safeScope.launch { _apps.value = runCatching { deps.platform.apps.launchable() }.getOrDefault(emptyList()) }
        safeScope.launch { _essentials.value = runCatching { deps.platform.apps.allowlistEssentials() }.getOrDefault(emptyList()) }
    }

    fun chooseTemplate(t: Template?) {
        val d = t?.schedule() ?: EditorOps.blank()
        initial = d
        savedState.setJson(KEY_INITIAL, d)
        _draft.value = d
        _choosingTemplate.value = false
    }

    fun setWebInput(input: WebInput) {
        _webInput.value = input
        savedState.setJson(KEY_WEB, input)
    }

    /** Whether the website field is shown for [s] (blocklist, or allowlist with "Only these sites"). */
    private fun webFieldShown(s: Schedule) = s.mode == Mode.BLOCKLIST || s.allowlistWeb == AllowlistWeb.ONLY_LISTED

    /** Unsaved edits that Back would discard: a changed draft, or text left in the website field. */
    fun hasChanges(): Boolean {
        if (_choosingTemplate.value || _saved.value) return false
        val d = _draft.value ?: return false
        return d != initial || (webFieldShown(d) && _webInput.value.text.isNotBlank())
    }

    fun update(f: (Schedule) -> Schedule) {
        _draft.update { it?.let(f) }
    }

    fun label(pkg: String): String = _apps.value?.firstOrNull { it.packageName == pkg }?.label ?: deps.appLabel(pkg)

    /** Switching to ALLOWLIST with a budget asks first: allowlist schedules can't have budgets (§5.4). */
    fun requestMode(mode: Mode) {
        val s = _draft.value ?: return
        if (mode == s.mode) return
        if (mode == Mode.ALLOWLIST && s.limits.isNotEmpty()) {
            _allowlistPrompt.value = true
        } else {
            update { EditorOps.setMode(it, mode) }
        }
    }

    fun confirmAllowlist(confirm: Boolean) {
        _allowlistPrompt.value = false
        if (confirm) update { EditorOps.setMode(it, Mode.ALLOWLIST) }
    }

    /** Replaces the app list; offers the bundled domains of the first newly added app that has some. */
    fun setApps(apps: List<String>) {
        val s = _draft.value ?: return
        val added = apps.filter { it !in s.apps }
        update { it.copy(apps = apps) }
        if (s.mode != Mode.BLOCKLIST) return
        val domains = deps.platform.apps.appDomains
        added.firstNotNullOfOrNull { pkg ->
            EditorOps.domainSuggestions(pkg, domains, s.webRules).takeIf { it.isNotEmpty() }?.let { DomainOffer(pkg, label(pkg), it) }
        }?.let { _domainOffer.value = it }
    }

    fun answerDomainOffer(accept: Boolean) {
        val offer = _domainOffer.value ?: return
        _domainOffer.value = null
        if (!accept) return
        update { s ->
            var rules = s.webRules
            offer.domains.forEach { d ->
                val p = EditorOps.preview(dev.brainrotprevention.model.WebRuleKind.DOMAIN, d, false)
                if (p is RulePreview.Adds) rules = EditorOps.addRule(rules, p.rule) ?: rules
            }
            s.copy(webRules = rules)
        }
    }

    /** "You've used 40 min today; this blocks now" when the new allowance is ≤ the current use (§5.4). */
    fun usedWarning(kind: LimitKind): String? {
        val s = _draft.value ?: return null
        val limit = s.limit(kind) ?: return null
        val old = original?.limit(kind) ?: return null
        if (old.period != limit.period) return null
        val counter = counters.value[old.id] ?: return null
        val c = deps.clock.read()
        val st = StatusCalculator.limitStatus(old, counter, c.now, c.zone, deps.graph.snapshots.snapshot.value?.prefs?.dayStartHour ?: 0)
        val used = st.counter.used
        if (used <= 0 || limit.allowance > used) return null
        val period = if (limit.period == dev.brainrotprevention.model.Period.DAILY) "today" else "this hour"
        val usedText = if (kind == LimitKind.TIME) Format.duration(used) else "$used opens"
        return "You've used $usedText $period; this blocks now."
    }

    fun dismissNotice() {
        _notice.value = null
        // "Not found": nothing to edit, so close instead of leaving an endless spinner.
        if (notFound) _saved.value = true
    }

    fun dismissAlwaysPrompt() {
        _alwaysPrompt.value = false
    }

    fun save(confirmedAlways: Boolean = false) {
        if (_saving.value) return
        var s = _draft.value ?: return
        _alwaysPrompt.value = false
        if (webFieldShown(s)) {
            when (val p = EditorOps.commitPending(s.webRules, _webInput.value, if (s.mode == Mode.ALLOWLIST) "Allows" else "Blocks")) {
                is PendingWeb.Unfinished -> {
                    _notice.value = Notice.Message("Finish the website first", p.message)
                    return
                }
                is PendingWeb.Ready -> if (p.consumedInput) {
                    s = s.copy(webRules = p.rules)
                    _draft.value = s
                    setWebInput(_webInput.value.copy(text = ""))
                }
            }
        }
        if (s.name.isBlank()) {
            _notice.value = Notice.Message("Add a name", "Give the schedule a short name first.")
            return
        }
        // Before the "all the time?" prompt: there is no point confirming a schedule that can't be saved.
        if (s.apps.isEmpty() && s.webRules.isEmpty() && s.mode == Mode.BLOCKLIST) {
            _notice.value = Notice.Message("Can't save this", "Add at least one app or site to block.")
            return
        }
        if (!confirmedAlways && EditorOps.needsAlwaysPrompt(s)) {
            _alwaysPrompt.value = true
            return
        }
        _saving.value = true
        safeScope.launch {
            try {
                val toSave = if (s.id == 0L) s.copy(sortOrder = (g.schedules.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1) else s
                // Runtime fields (on/off, pause, Start now, location state) come from the current row (diff minor 3).
                val r = g.schedules.save(toSave, keepRuntime = true)
                _notice.value = r.notice()
                if (r.isOk) {
                    deps.platform.engine.requestEvaluate()
                    _saved.value = true
                }
            } finally {
                // Stays "saving" once saved, so nothing can be submitted again while the screen closes.
                if (!_saved.value) _saving.value = false
            }
        }
    }

    fun delete() {
        val id = _draft.value?.id ?: return
        if (id == 0L) {
            _saved.value = true
            return
        }
        if (_saving.value) return
        _saving.value = true
        safeScope.launch {
            try {
                val r = g.schedules.delete(id)
                _notice.value = r.notice()
                if (r.isOk) {
                    deps.platform.engine.requestEvaluate()
                    _saved.value = true
                }
            } finally {
                if (!_saved.value) _saving.value = false
            }
        }
    }

    private companion object {
        const val KEY_DRAFT = "editor_draft"
        const val KEY_INITIAL = "editor_initial"
        const val KEY_WEB = "editor_web_input"
        const val SAVE_DEBOUNCE_MS = 250L
    }
}
