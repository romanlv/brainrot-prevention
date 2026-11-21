package dev.brainrotprevention.ui.editor

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.brainrotprevention.model.QuickBlock
import dev.brainrotprevention.ui.UiDeps
import dev.brainrotprevention.ui.common.Notice
import dev.brainrotprevention.ui.common.NoticeHost
import dev.brainrotprevention.ui.common.Section
import dev.brainrotprevention.ui.common.SwitchRow
import dev.brainrotprevention.ui.common.notice
import dev.brainrotprevention.ui.depsViewModel
import dev.brainrotprevention.ui.platform.UiApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class QuickBlockEditorViewModel(private val deps: UiDeps) : ViewModel() {
    private val _draft = MutableStateFlow<QuickBlock?>(null)
    val draft: StateFlow<QuickBlock?> = _draft.asStateFlow()
    private val _apps = MutableStateFlow<List<UiApp>?>(null)
    val apps: StateFlow<List<UiApp>?> = _apps.asStateFlow()
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()
    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    init {
        viewModelScope.launch { _draft.value = deps.graph.quickBlock.get() }
        viewModelScope.launch { _apps.value = runCatching { deps.platform.apps.launchable() }.getOrDefault(emptyList()) }
    }

    fun update(f: (QuickBlock) -> QuickBlock) = _draft.update { it?.let(f) }

    fun label(pkg: String) = _apps.value?.firstOrNull { it.packageName == pkg }?.label ?: deps.platform.apps.label(pkg)

    fun dismissNotice() {
        _notice.value = null
    }

    /** Saves the items and flag; keeps the current active/until (it may have changed meanwhile). */
    fun save() {
        val d = _draft.value ?: return
        viewModelScope.launch {
            val cur = deps.graph.quickBlock.get()
            val r = deps.graph.quickBlock.save(cur.copy(apps = d.apps, webRules = d.webRules, blockNotifications = d.blockNotifications))
            _notice.value = r.notice()
            if (r.isOk) {
                deps.platform.engine.requestEvaluate()
                _saved.value = true
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickBlockEditorScreen(deps: UiDeps, onDone: () -> Unit) {
    val vm = depsViewModel(deps) { QuickBlockEditorViewModel(it) }
    val d by vm.draft.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    LaunchedEffect(saved) { if (saved) onDone() }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("Quick Block items") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back without saving") }
                },
                actions = { TextButton(onClick = vm::save, modifier = Modifier.testTag("qb_editor_save_top")) { Text("Save") } },
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
                WebRulesEditor(q.webRules, onChange = { r -> vm.update { it.copy(webRules = r) } })
            }
            Section("Options") {
                SwitchRow("Hide notifications", q.blockNotifications, { v ->
                    vm.update { it.copy(blockNotifications = v) }
                }, tag = "qb_block_notifications")
            }
            PrimaryWideButton("Save", onClick = vm::save, tag = "qb_editor_save")
            Spacer(Modifier.size(32.dp))
        }
    }
    NoticeHost(notice, vm::dismissNotice)
}
