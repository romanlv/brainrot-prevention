package dev.brainrotprevention.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.ui.UiDeps
import dev.brainrotprevention.ui.common.ConfirmDialog
import dev.brainrotprevention.ui.common.Notice
import dev.brainrotprevention.ui.common.NoticeHost
import dev.brainrotprevention.ui.common.Section
import dev.brainrotprevention.ui.common.SwitchRow
import dev.brainrotprevention.ui.common.notice
import dev.brainrotprevention.ui.depsViewModelWithState
import dev.brainrotprevention.ui.platform.UiApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class QuickBlockEditorViewModel(private val deps: UiDeps, private val savedState: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val _draft = MutableStateFlow<QuickBlock?>(savedState.getJson(KEY_DRAFT))
    val draft: StateFlow<QuickBlock?> = _draft.asStateFlow()
    private var initial: QuickBlock? = savedState.getJson<QuickBlock>(KEY_INITIAL)
    private val _webInput = MutableStateFlow(savedState.getJson<WebInput>(KEY_WEB) ?: WebInput())
    val webInput: StateFlow<WebInput> = _webInput.asStateFlow()
    private val _apps = MutableStateFlow<List<UiApp>?>(null)
    val apps: StateFlow<List<UiApp>?> = _apps.asStateFlow()
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    init {
        viewModelScope.launch {
            if (_draft.value == null) {
                val q = deps.graph.quickBlock.get()
                initial = q
                savedState.setJson(KEY_INITIAL, q)
                _draft.value = q
            }
        }
        viewModelScope.launch { _draft.collect { d -> if (d != null) savedState.setJson(KEY_DRAFT, d) } }
        viewModelScope.launch { _webInput.collect { savedState.setJson(KEY_WEB, it) } }
        viewModelScope.launch { _apps.value = runCatching { deps.platform.apps.launchable() }.getOrDefault(emptyList()) }
    }

    fun update(f: (QuickBlock) -> QuickBlock) = _draft.update { it?.let(f) }

    fun setWebInput(input: WebInput) {
        _webInput.value = input
    }

    fun label(pkg: String) = _apps.value?.firstOrNull { it.packageName == pkg }?.label ?: deps.appLabel(pkg)

    /** Unsaved item edits (active/until are not edited here), or text left in the website field. */
    fun hasChanges(): Boolean {
        if (_saved.value) return false
        val d = _draft.value ?: return false
        val i = initial ?: return false
        return d.apps != i.apps ||
            d.webRules != i.webRules ||
            d.blockNotifications != i.blockNotifications ||
            _webInput.value.text.isNotBlank()
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /** Saves the items and flag; keeps the current active/until (it may have changed meanwhile). */
    fun save() {
        if (_saving.value) return
        var d = _draft.value ?: return
        when (val p = EditorOps.commitPending(d.webRules, _webInput.value)) {
            is PendingWeb.Unfinished -> {
                _notice.value = Notice.Message("Finish the website first", p.message)
                return
            }
            is PendingWeb.Ready -> if (p.consumedInput) {
                d = d.copy(webRules = p.rules)
                _draft.value = d
                _webInput.update { it.copy(text = "") }
            }
        }
        _saving.value = true
        viewModelScope.launch {
            try {
                val cur = deps.graph.quickBlock.get()
                val r = deps.graph.quickBlock.save(
                    cur.copy(apps = d.apps, webRules = d.webRules, blockNotifications = d.blockNotifications)
                )
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
        const val KEY_DRAFT = "qb_draft"
        const val KEY_INITIAL = "qb_initial"
        const val KEY_WEB = "qb_web_input"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickBlockEditorScreen(deps: UiDeps, onDone: () -> Unit) {
    val vm = depsViewModelWithState(deps) { d, state -> QuickBlockEditorViewModel(d, state) }
    val d by vm.draft.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val webInput by vm.webInput.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved) onDone() }
    val back: () -> Unit = {
        if (vm.hasChanges()) {
            confirmDiscard = true
        } else {
            onDone()
        }
    }
    BackHandler(enabled = !saved, onBack = back)
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Quick Block items") },
                navigationIcon = {
                    IconButton(onClick = back, modifier = Modifier.testTag("qb_editor_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = vm::save, enabled = !saving && d != null, modifier = Modifier.testTag("qb_editor_save_top")) {
                        Text("Save")
                    }
                },
            )
        },
    ) { inner ->
        val q = d ?: return@Scaffold
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("qb_editor"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Apps", subtitle = "Blocked whenever Quick Block is on.") {
                AppsList(q.apps, vm::label, apps, "Add apps", onChange = { l -> vm.update { it.copy(apps = l) } })
            }
            Section("Websites and keywords") {
                WebRulesEditor(
                    q.webRules,
                    onChange = { r -> vm.update { it.copy(webRules = r) } },
                    input = webInput,
                    onInput = vm::setWebInput,
                )
            }
            Section("Options") {
                SwitchRow("Hide notifications", q.blockNotifications, { v ->
                    vm.update { it.copy(blockNotifications = v) }
                }, tag = "qb_block_notifications")
            }
            PrimaryWideButton("Save", onClick = vm::save, tag = "qb_editor_save", enabled = !saving)
            Spacer(Modifier.size(32.dp))
        }
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            text = "Your changes to the Quick Block items haven't been saved.",
            confirm = "Discard",
            dismiss = "Keep editing",
            onConfirm = {
                confirmDiscard = false
                onDone()
            },
            onDismiss = { confirmDiscard = false },
            tag = "discard_changes",
        )
    }
    NoticeHost(notice, vm::dismissNotice)
}
