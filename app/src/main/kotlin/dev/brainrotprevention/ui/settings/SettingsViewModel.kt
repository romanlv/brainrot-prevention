package dev.brainrotprevention.ui.settings

import androidx.lifecycle.ViewModel
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.model.Prefs
import dev.brainrotprevention.ui.UiDeps
import dev.brainrotprevention.ui.common.Notice
import dev.brainrotprevention.ui.common.notice
import dev.brainrotprevention.ui.platform.UiApp
import dev.brainrotprevention.ui.platform.UiHealth
import dev.brainrotprevention.ui.safeScope
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val deps: UiDeps) : ViewModel() {
    private companion object {
        const val SAVE_DEBOUNCE_MS = 400L
    }

    private val g = deps.graph

    /** Edits not saved yet (display shape: `dayStartHour` = in force, `pendingDayStartHour` = wanted). */
    private val local = MutableStateFlow<Prefs?>(null)
    private var saveJob: Job? = null
    private var unsaved: Prefs? = null

    /** The stored prefs, overlaid with edits still waiting for their (debounced) save. */
    val prefs: StateFlow<Prefs?> = combine(g.settings.prefs, local) { db, l -> l ?: db }
        .stateIn(safeScope, SharingStarted.WhileSubscribed(5_000), null)
    val excluded: StateFlow<Set<String>> = g.settings.excludedBrowsers.stateIn(
        safeScope,
        SharingStarted.WhileSubscribed(5_000),
        emptySet()
    )

    private val _health = MutableStateFlow(UiHealth())
    val health: StateFlow<UiHealth> = _health.asStateFlow()

    private val _unsupported = MutableStateFlow<List<UiApp>>(emptyList())
    val unsupported: StateFlow<List<UiApp>> = _unsupported.asStateFlow()

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    /** Text of a picked backup file waiting for the "Replace everything?" confirmation. */
    private val _pendingImport = MutableStateFlow<String?>(null)
    val pendingImport: StateFlow<String?> = _pendingImport.asStateFlow()

    init {
        refreshHealth()
        safeScope.launch { _unsupported.value = runCatching { deps.platform.apps.unsupportedBrowsers() }.getOrDefault(emptyList()) }
    }

    fun refreshHealth() {
        _health.value = runCatching { deps.platform.health.check() }.getOrDefault(UiHealth())
    }

    fun label(pkg: String) = deps.appLabel(pkg)

    fun show(n: Notice?) {
        _notice.value = n
    }

    /**
     * Applies [f] to the shown prefs (including edits not saved yet, so rapid stepper taps all count), shows the
     * result at once and saves it after a short pause in the tapping.
     */
    fun editPrefs(f: (Prefs) -> Prefs) {
        val shown = prefs.value ?: return
        // savePrefs takes the wanted day start in dayStartHour; pass the effective one when it isn't changing.
        val wanted = f(shown.copy(dayStartHour = shown.pendingDayStartHour ?: shown.dayStartHour))
        val display = wanted.copy(
            dayStartHour = shown.dayStartHour,
            pendingDayStartHour = wanted.dayStartHour.takeIf { it != shown.dayStartHour },
        )
        local.value = display
        unsaved = wanted
        saveJob?.cancel()
        saveJob = safeScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            unsaved = null
            // Only the wait is cancellable: a save that has started always finishes.
            withContext(NonCancellable) {
                val r = g.settings.savePrefs(wanted)
                _notice.value = r.notice()
                if (r.isOk) deps.platform.engine.requestEvaluate()
                // Back to the stored value (also undoes a refused edit), unless a newer edit is waiting.
                if (local.value === display) local.value = null
            }
        }
    }

    /** An edit still in its debounce wait when the screen goes away is saved anyway. */
    override fun onCleared() {
        val pending = unsaved ?: return
        unsaved = null
        deps.launchDetached {
            if (g.settings.savePrefs(pending).isOk) deps.platform.engine.requestEvaluate()
        }
    }

    fun setExcluded(pkg: String, excluded: Boolean) {
        safeScope.launch {
            val cur = g.settings.getExcludedBrowsers()
            _notice.value = g.settings.setExcludedBrowsers(if (excluded) cur + pkg else cur - pkg).notice()
        }
    }

    fun suggestedBackupName(): String {
        val c = deps.clock.read()
        return "brainrot-prevention-" + DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(c.now).atZone(c.zone)) + ".json"
    }

    suspend fun exportText(): String = g.backup.exportText()

    fun exported(ok: Boolean) {
        _notice.value = if (ok) {
            Notice.Message(
                "Backup saved",
                "Your schedules, Quick Block items and settings were saved. Strict Mode and usage aren't included."
            )
        } else {
            Notice.Message("Couldn't save the backup", "The file couldn't be written. Try another place.")
        }
    }

    fun picked(text: String?) {
        if (text == null) {
            _notice.value = Notice.Message("Couldn't read the file", "Pick a backup file made by Brainrot Prevention (at most 1 MB).")
        } else {
            _pendingImport.value = text
        }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    fun confirmImport() {
        val text = _pendingImport.value ?: return
        _pendingImport.value = null
        safeScope.launch {
            val r = g.backup.import(text)
            _notice.value = when (r) {
                is WriteResult.Ok -> Notice.Message(
                    "Backup restored",
                    "${r.value.schedules} schedules and ${r.value.quickBlockApps + r.value.quickBlockWebRules} Quick Block items were restored.",
                )
                is WriteResult.Invalid -> Notice.Message(
                    "This backup can't be used",
                    "${r.error.message} (at ${r.error.path}). Nothing was changed."
                )
                else -> r.notice()
            }
            if (r.isOk) deps.platform.engine.requestEvaluate()
        }
    }
}
