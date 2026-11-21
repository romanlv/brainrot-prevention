package dev.brainrotprevention.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.brainrotprevention.engine.EditGuard
import dev.brainrotprevention.model.AllowlistWeb
import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.Mode
import dev.brainrotprevention.model.Period
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.ui.UiDeps
import dev.brainrotprevention.ui.common.ConfirmDialog
import dev.brainrotprevention.ui.common.NoticeHost
import dev.brainrotprevention.ui.common.Section
import dev.brainrotprevention.ui.common.Stepper
import dev.brainrotprevention.ui.common.SwitchRow
import dev.brainrotprevention.ui.depsViewModel
import dev.brainrotprevention.ui.format.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(deps: UiDeps, scheduleId: Long, template: Template?, onDone: () -> Unit, startBlank: Boolean = false) {
    val vm =
        depsViewModel(deps, key = "editor_${scheduleId}_${template?.name}_$startBlank") {
            EditorViewModel(it, scheduleId, template, startBlank)
        }
    val draft by vm.draft.collectAsStateWithLifecycle()
    val choosing by vm.choosingTemplate.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val always by vm.alwaysPrompt.collectAsStateWithLifecycle()
    val allowlistPrompt by vm.allowlistPrompt.collectAsStateWithLifecycle()
    val offer by vm.domainOffer.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(saved) { if (saved) onDone() }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(if (scheduleId == 0L) "New schedule" else "Edit schedule") },
                navigationIcon = {
                    IconButton(onClick = onDone, modifier = Modifier.testTag("editor_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back without saving")
                    }
                },
                actions = {
                    if (scheduleId != 0L) {
                        IconButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("editor_delete")) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete schedule")
                        }
                    }
                    if (!choosing) {
                        TextButton(onClick = {
                            focus.clearFocus()
                            vm.save()
                        }, modifier = Modifier.testTag("editor_save_top")) { Text("Save") }
                    }
                },
            )
        },
    ) { inner ->
        val s = draft
        when {
            choosing -> TemplateChooser(Modifier.padding(inner), onPick = vm::chooseTemplate)
            s == null -> Box(
                Modifier
                    .padding(inner)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            else -> EditorBody(deps, vm, s, Modifier.padding(inner))
        }
    }

    if (always) {
        ConfirmDialog(
            title = "Block these all the time?",
            text = "This schedule has no times, places or budget, so it blocks around the clock until you turn it off.",
            confirm = "Yes",
            dismiss = "Add a time",
            onConfirm = { vm.save(confirmedAlways = true) },
            onDismiss = vm::dismissAlwaysPrompt,
            onDismissButton = {
                vm.dismissAlwaysPrompt()
                vm.update { it.copy(timeWindows = it.timeWindows + EditorOps.defaultWindow(it.timeWindows)) }
            },
            tag = "always_prompt",
        )
    }
    if (allowlistPrompt) {
        ConfirmDialog(
            title = "Remove the budget?",
            text = "“Allow only selected” blocks everything else whenever it runs, so it can't have a time or open budget. " +
                "Switching removes the budget.",
            confirm = "Switch and remove",
            onConfirm = { vm.confirmAllowlist(true) },
            onDismiss = { vm.confirmAllowlist(false) },
            tag = "allowlist_limits_prompt",
        )
    }
    offer?.let { o ->
        ConfirmDialog(
            title = "Also block ${o.domains.joinToString(", ")}?",
            text = "${o.label} also works in the browser. Add its website so it's blocked there too.",
            confirm = "Add",
            dismiss = "No thanks",
            onConfirm = { vm.answerDomainOffer(true) },
            onDismiss = { vm.answerDomainOffer(false) },
            tag = "domain_offer",
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete this schedule?",
            text = "This can't be undone.",
            confirm = "Delete",
            onConfirm = {
                confirmDelete = false
                vm.delete()
            },
            onDismiss = { confirmDelete = false },
            tag = "confirm_delete",
        )
    }
    NoticeHost(notice, vm::dismissNotice)
}

@Composable
private fun TemplateChooser(modifier: Modifier, onPick: (Template?) -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("template_chooser"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Start from a template", style = MaterialTheme.typography.titleLarge)
        Text("You can change everything afterwards.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TemplateList(onPick)
    }
}

/** The template cards plus "Start from scratch" (also used by onboarding). */
@Composable
fun TemplateList(onPick: (Template?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Template.entries.forEach { t ->
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(t) }
                    .testTag("template_${t.name.lowercase()}"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.icon, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { contentDescription = "" })
                    Spacer(Modifier.size(16.dp))
                    Column {
                        Text(t.title, style = MaterialTheme.typography.titleMedium)
                        Text(t.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        TextButton(
            onClick = { onPick(null) },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("template_scratch")
        ) { Text("Start from scratch") }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun EditorBody(deps: UiDeps, vm: EditorViewModel, s: Schedule, modifier: Modifier) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val essentials by vm.essentials.collectAsStateWithLifecycle()
    val strictRow by deps.graph.strict.strict.collectAsStateWithLifecycle(StrictModeRow())
    val status by deps.status.collectAsStateWithLifecycle(null)
    val schedules by deps.graph.schedules.schedules.collectAsStateWithLifecycle(emptyList())
    vm.counters.collectAsStateWithLifecycle()
    val allow = s.mode == Mode.ALLOWLIST
    val focus = LocalFocusManager.current
    val locked = s.id != 0L && status?.let { EditGuard.isLocked(strictRow, it.strict, schedules, s.id) } == true

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("editor"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (locked) {
            Card(
                Modifier
                    .fillMaxWidth()
                    .testTag("editor_strict_banner"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(Modifier.size(12.dp))
                    Text("Strict Mode is on. You can make this schedule stricter, but not weaker.")
                }
            }
        }
        Section("Name") {
            OutlinedTextField(
                value = s.name,
                onValueChange = { v -> vm.update { it.copy(name = v.take(40)) } },
                singleLine = true,
                label = { Text("Name") },
                supportingText = { Text("${s.name.length}/40") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("editor_name"),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                EditorOps.ICONS.forEachIndexed { i, icon ->
                    FilterChip(
                        selected = s.icon == icon,
                        onClick = { vm.update { it.copy(icon = icon) } },
                        label = { Text(icon) },
                        modifier = Modifier
                            .testTag("editor_icon_$i")
                            .semantics { contentDescription = "Icon $icon" },
                    )
                }
            }
        }
        Section("Mode") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !allow,
                    onClick = { vm.requestMode(Mode.BLOCKLIST) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    modifier = Modifier.testTag("editor_mode_blocklist"),
                ) { Text("Block selected") }
                SegmentedButton(
                    selected = allow,
                    onClick = { vm.requestMode(Mode.ALLOWLIST) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    modifier = Modifier.testTag("editor_mode_allowlist"),
                ) { Text("Allow only selected") }
            }
            Text(
                if (allow) {
                    "Every app is blocked except the ones you pick and a few essentials."
                } else {
                    "Only the apps and sites you pick are blocked."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (allow) {
                Text("Always allowed", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "Phone and emergency calls, the keyboard, your home screen, permission prompts, and the lock screen camera.",
                    style = MaterialTheme.typography.bodySmall,
                )
                AppsReadOnly(essentials)
            }
        }
        Section(if (allow) "Apps to allow" else "Apps to block") {
            AppsList(
                selected = s.apps,
                labelOf = vm::label,
                allApps = apps,
                addLabel = if (allow) "Add apps to allow" else "Add apps to block",
                onChange = vm::setApps,
            )
        }
        Section(if (allow) "Websites" else "Websites and keywords", subtitle = "Works in supported browsers.") {
            if (allow) {
                AllowlistWebChoice(s.allowlistWeb) { v -> vm.update { it.copy(allowlistWeb = v) } }
            }
            if (!allow || s.allowlistWeb == AllowlistWeb.ONLY_LISTED) {
                WebRulesEditor(s.webRules, onChange = { r ->
                    vm.update { it.copy(webRules = r) }
                }, verb = if (allow) "Allows" else "Blocks")
            }
        }
        Section("When", subtitle = "Times and places when this schedule runs.") {
            WhenEditor(s) { f -> vm.update(f) }
        }
        if (!allow) {
            Section("Budget", subtitle = "Allow some use first, then block. Shared by everything in this schedule.") {
                BudgetRow(vm, s, LimitKind.TIME)
                BudgetRow(vm, s, LimitKind.LAUNCHES)
            }
        }
        Section("Options") {
            SwitchRow(
                "Hide notifications",
                s.blockNotifications,
                { v -> vm.update { it.copy(blockNotifications = v) } },
                tag = "editor_block_notifications",
                subtitle = "Notifications from blocked apps wait until the block ends.",
            )
            SwitchRow(
                "Block browsers that can't be checked",
                s.blockUnsupportedBrowsers,
                { v -> vm.update { it.copy(blockUnsupportedBrowsers = v) } },
                tag = "editor_block_unsupported",
                subtitle = "When this schedule has websites, browsers whose address bar can't be read are blocked as apps.",
            )
            if (!allow) {
                SwitchRow(
                    "Followed by Strict Mode",
                    s.strictFollowed,
                    { v -> vm.update { it.copy(strictFollowed = v) } },
                    tag = "editor_strict_followed",
                    subtitle = "With the “Schedules” unlock method, Strict Mode is active while this schedule blocks.",
                )
            }
        }
        PrimaryWideButton("Save", onClick = {
            focus.clearFocus()
            vm.save()
        }, tag = "editor_save")
        Spacer(Modifier.size(32.dp))
    }
}

@Composable
private fun AllowlistWebChoice(value: AllowlistWeb, onChange: (AllowlistWeb) -> Unit) {
    Column {
        listOf(
            AllowlistWeb.OPEN to "Browse freely in allowed browsers",
            AllowlistWeb.ONLY_LISTED to "Only these sites",
        ).forEach { (v, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(value == v, role = Role.RadioButton) { onChange(v) }
                    .testTag(if (v == AllowlistWeb.OPEN) "editor_allowweb_open" else "editor_allowweb_only"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = value == v, onClick = null)
                Spacer(Modifier.size(12.dp))
                Text(label)
            }
        }
    }
}

@Composable
private fun BudgetRow(vm: EditorViewModel, s: Schedule, kind: LimitKind) {
    val limit = s.limit(kind)
    val time = kind == LimitKind.TIME
    val key = if (time) "time" else "launches"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SwitchRow(
            if (time) "Time budget" else "Open budget",
            limit != null,
            { on -> vm.update { EditorOps.setLimit(it, kind, if (on) EditorOps.defaultAllowance(kind) else null) } },
            tag = "budget_${key}_toggle",
            subtitle = if (time) "Minutes of use before blocking" else "Number of opens before new opens are blocked",
        )
        if (limit != null) {
            Stepper(
                value = limit.allowance,
                onChange = { v -> vm.update { EditorOps.setLimit(it, kind, v) } },
                range = if (time) EditorOps.TIME_RANGE else EditorOps.LAUNCH_RANGE,
                step = if (time) EditorOps::stepTime else EditorOps::stepLaunches,
                format = {
                    if (time) {
                        Format.duration(it)
                    } else if (it == 1L) {
                        "1 open"
                    } else {
                        "$it opens"
                    }
                },
                tag = "budget_$key",
                label = if (time) "time budget" else "open budget",
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(Period.DAILY to "Per day", Period.HOURLY to "Per hour").forEachIndexed { i, (p, label) ->
                    SegmentedButton(
                        selected = limit.period == p,
                        onClick = { vm.update { EditorOps.setLimit(it, kind, limit.allowance, p) } },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        modifier = Modifier.testTag("budget_${key}_${p.name.lowercase()}"),
                    ) { Text(label) }
                }
            }
            vm.usedWarning(kind)?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("budget_${key}_warning")
                )
            }
        }
    }
}
