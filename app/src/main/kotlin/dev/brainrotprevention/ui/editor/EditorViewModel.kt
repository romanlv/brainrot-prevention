package dev.brainrotprevention.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.engine.StatusCalculator
import dev.brainrotprevention.engine.ValidationError
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.UsageCounter
import dev.brainrotprevention.ui.UiDeps
import dev.brainrotprevention.ui.common.Notice
import dev.brainrotprevention.ui.common.notice
import dev.brainrotprevention.ui.format.Format
import dev.brainrotprevention.ui.platform.UiApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Pending "Also block instagram.com?" offer for a newly added app (§12, PL-m14). */
data class DomainOffer(val pkg: String, val label: String, val domains: List<String>)

class EditorViewModel(
    private val deps: UiDeps,
    private val scheduleId: Long,
    template: Template?,
    startBlank: Boolean = false,
) : ViewModel() {
    private val g = deps.graph

    /** Null while loading an existing schedule. */
    private val _draft = MutableStateFlow<Schedule?>(
        if (scheduleId ==
            0L
        ) {
            template?.schedule() ?: EditorOps.blank().takeIf { startBlank }
        } else {
            null
        }
    )
    val draft: StateFlow<Schedule?> = _draft.asStateFlow()

    /** New schedule without a template yet: show the template chooser. */
    private val _choosingTemplate = MutableStateFlow(scheduleId == 0L && template == null && !startBlank)
    val choosingTemplate: StateFlow<Boolean> = _choosingTemplate.asStateFlow()

    var original: Schedule? = null
        private set

    private val _apps = MutableStateFlow<List<UiApp>?>(null)
    val apps: StateFlow<List<UiApp>?> = _apps.asStateFlow()

    private val _essentials = MutableStateFlow<List<UiApp>>(emptyList())
    val essentials: StateFlow<List<UiApp>> = _essentials.asStateFlow()

    val counters: StateFlow<Map<Long, UsageCounter>> = g.counters.counters.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private val _alwaysPrompt = MutableStateFlow(false)
    val alwaysPrompt: StateFlow<Boolean> = _alwaysPrompt.asStateFlow()

    private val _allowlistPrompt = MutableStateFlow(false)
    val allowlistPrompt: StateFlow<Boolean> = _allowlistPrompt.asStateFlow()

    private val _domainOffer = MutableStateFlow<DomainOffer?>(null)
    val domainOffer: StateFlow<DomainOffer?> = _domainOffer.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        viewModelScope.launch {
            if (scheduleId != 0L) {
                val s = g.schedules.get(scheduleId)
                original = s
                if (s == null) _notice.value = Notice.Message("Not found", "This schedule was deleted.")
                _draft.value = s
            }
        }
        viewModelScope.launch { _apps.value = runCatching { deps.platform.apps.launchable() }.getOrDefault(emptyList()) }
        viewModelScope.launch { _essentials.value = runCatching { deps.platform.apps.allowlistEssentials() }.getOrDefault(emptyList()) }
    }

    fun chooseTemplate(t: Template?) {
        _draft.value = t?.schedule() ?: EditorOps.blank()
        _choosingTemplate.value = false
    }

    fun update(f: (Schedule) -> Schedule) {
        _draft.update { it?.let(f) }
    }

    fun label(pkg: String): String = _apps.value?.firstOrNull { it.packageName == pkg }?.label ?: deps.platform.apps.label(pkg)

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
    }

    fun dismissAlwaysPrompt() {
        _alwaysPrompt.value = false
    }

    fun save(confirmedAlways: Boolean = false) {
        val s = _draft.value ?: return
        _alwaysPrompt.value = false
        if (s.name.isBlank()) {
            _notice.value = Notice.Message("Add a name", "Give the schedule a short name first.")
            return
        }
        if (!confirmedAlways && EditorOps.needsAlwaysPrompt(s)) {
            _alwaysPrompt.value = true
            return
        }
        viewModelScope.launch {
            val toSave = if (s.id == 0L) s.copy(sortOrder = (g.schedules.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1) else s
            val r = if (s.apps.isEmpty() && s.webRules.isEmpty() && s.mode == Mode.BLOCKLIST) {
                WriteResult.Invalid(ValidationError("schedule.apps", "add at least one app or site to block"))
            } else {
                g.schedules.save(toSave)
            }
            _notice.value = r.notice()
            if (r.isOk) {
                deps.platform.engine.requestEvaluate()
                _saved.value = true
            }
        }
    }

    fun delete() {
        val id = _draft.value?.id ?: return
        if (id == 0L) {
            _saved.value = true
            return
        }
        viewModelScope.launch {
            val r = g.schedules.delete(id)
            _notice.value = r.notice()
            if (r.isOk) {
                deps.platform.engine.requestEvaluate()
                _saved.value = true
            }
        }
    }
}
