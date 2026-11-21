package dev.brainrotprevention.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.brainrotprevention.engine.Refusal
import dev.brainrotprevention.engine.ScheduleEvaluator
import dev.brainrotprevention.engine.ScheduleStatus
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.ui.UiDeps
import dev.brainrotprevention.ui.common.ConfirmDialog
import dev.brainrotprevention.ui.common.NoticeHost
import dev.brainrotprevention.ui.common.Pill
import dev.brainrotprevention.ui.common.debugRoot
import dev.brainrotprevention.ui.depsViewModel
import dev.brainrotprevention.ui.format.Format
import dev.brainrotprevention.ui.format.StatePill
import dev.brainrotprevention.ui.theme.LocalBlockingColors

@Composable
fun HomeScreen(deps: UiDeps, onEdit: (Long) -> Unit, onNew: () -> Unit, onEditQuickBlock: () -> Unit, contentPadding: PaddingValues) {
    val vm = depsViewModel(deps) { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var pauseFor by remember { mutableStateOf<Schedule?>(null) }
    var startFor by remember { mutableStateOf<Schedule?>(null) }
    var deleteFor by remember { mutableStateOf<Schedule?>(null) }

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New schedule") },
                modifier = Modifier.testTag("fab_new_schedule"),
            )
        },
    ) { inner ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .testTag("home_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = inner.calculateTopPadding() + 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Brainrot Prevention",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (state.blockingOff) {
                item {
                    val activity = androidx.activity.compose.LocalActivity.current
                    BlockingOffBanner(state) { activity?.let(vm::fixBlocking) }
                }
            } else if (state.triggersUnchecked) {
                item {
                    val activity = androidx.activity.compose.LocalActivity.current
                    TriggersUncheckedNotice { activity?.let(vm::fixBlocking) }
                }
            }
            item { StatusCard(state) }
            item {
                QuickBlockCard(
                    state = state,
                    onStart = vm::startQuickBlock,
                    onStop = vm::stopQuickBlock,
                    onEdit = onEditQuickBlock,
                )
            }
            item {
                Text(
                    "Schedules",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .semantics { heading() },
                )
            }
            if (state.loaded && state.schedules.isEmpty()) {
                item {
                    Text(
                        "No schedules yet. Tap New schedule to pick a template or start from scratch.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("home_empty"),
                    )
                }
            }
            items(state.schedules, key = { it.id }) { s ->
                ScheduleCard(
                    s = s,
                    status = state.statuses[s.id],
                    state = state,
                    onOpen = { onEdit(s.id) },
                    onToggle = { vm.setEnabled(s.id, it) },
                    onResume = { vm.resume(s.id) },
                    onStop = { vm.stopNow(s.id) },
                    onStartNow = { startFor = s },
                    onPause = { if (vm.isLocked(s.id)) vm.showLocked(Refusal.PAUSED) else pauseFor = s },
                    onDuplicate = { vm.duplicate(s.id) },
                    onDelete = { if (vm.isLocked(s.id)) vm.showLocked(Refusal.DELETED) else deleteFor = s },
                )
            }
        }
    }

    pauseFor?.let { s ->
        PauseSheet(s.name, state.presets, state.now, state.zone, onDismiss = { pauseFor = null }, onChoice = { c ->
            pauseFor = null
            when (c) {
                is PauseChoice.Minutes -> vm.pauseFor(s.id, c.minutes, recordPreset = c.custom)
                PauseChoice.EndOfToday -> vm.pauseUntilEndOfToday(s.id)
                is PauseChoice.Until -> vm.pauseUntil(s.id, c.at)
                PauseChoice.TurnOff -> vm.turnOff(s.id)
            }
        })
    }
    startFor?.let { s ->
        StartNowSheet(s.name, alsoArms = startNowAlsoArms(s, state.now, vm.strictLocksEditing()), onDismiss = {
            startFor = null
        }, onPick = {
            startFor = null
            vm.startNow(s.id, it)
        })
    }
    deleteFor?.let { s ->
        ConfirmDialog(
            title = "Delete ${s.name}?",
            text = "Its apps, sites, times and budget counters are removed. This can't be undone.",
            confirm = "Delete",
            onConfirm = {
                deleteFor = null
                vm.delete(s.id)
            },
            onDismiss = { deleteFor = null },
            tag = "confirm_delete",
        )
    }
    NoticeHost(notice, vm::dismissNotice)
}

/** Review round 2, adversarial 6: say plainly that nothing is enforced, with the way to fix it. */
@Composable
private fun BlockingOffBanner(state: HomeState, onFix: () -> Unit) {
    val what = when (state.missingGrant) {
        dev.brainrotprevention.ui.platform.UiGrant.ACCESSIBILITY -> "The Brainrot Prevention accessibility service isn't running."
        dev.brainrotprevention.ui.platform.UiGrant.USAGE_ACCESS -> "Usage access is off."
        else -> "A required permission is missing."
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("blocking_off_banner"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Blocking is off", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("$what Nothing is blocked until you turn it back on.", style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = onFix,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("blocking_off_fix"),
            ) { Text("Fix") }
        }
    }
}

/** Review round 3, minor 5: a missing location grant only stops Wi-Fi/location triggers; say just that. */
@Composable
private fun TriggersUncheckedNotice(onFix: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("triggers_unchecked_notice"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Wi-Fi/location triggers can't be checked", style = MaterialTheme.typography.titleSmall)
            Text(
                "Location permission is missing, so schedules don't react to Wi-Fi or location. Everything else is blocked as usual.",
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.material3.TextButton(
                onClick = onFix,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("triggers_unchecked_fix"),
            ) { Text("Fix") }
        }
    }
}

@Composable
private fun StatusCard(state: HomeState) {
    val blocking = LocalBlockingColors.current
    val active = state.anyBlocking
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_card"),
        colors = CardDefaults.cardColors(
            containerColor = if (active) blocking.container else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (active) blocking.onContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (active) Icons.Default.Block else Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.size(16.dp))
            Text(
                if (state.loaded) state.headline else "",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("status_headline"),
            )
        }
    }
}

private val QB_TIMERS = listOf<Pair<String, Int?>>("No timer" to null, "25 min" to 25, "1 h" to 60, "2 h" to 120)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun QuickBlockCard(state: HomeState, onStart: (Int?) -> Unit, onStop: () -> Unit, onEdit: () -> Unit) {
    val qb = state.quickBlock
    val st = state.qbStatus
    val on = st?.blocking == true
    val blocking = LocalBlockingColors.current
    var timer by rememberSaveable { mutableStateOf<Int?>(25) }
    var customMin by rememberSaveable { mutableStateOf<Int?>(null) }
    var customOpen by remember { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .testTag("quick_block_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Quick Block", style = MaterialTheme.typography.titleMedium)
                    Text(
                        Format.listCounts(qb.apps.size, qb.webRules.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("qb_count"),
                    )
                }
                TextButton(onClick = onEdit, modifier = Modifier.testTag("qb_edit")) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("Edit")
                }
            }
            if (on) {
                Pill(
                    "Blocking " + Format.until(st.blockUntil, state.now, state.zone),
                    blocking.container,
                    blocking.onContainer,
                    Modifier.testTag("qb_state"),
                )
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("qb_stop")
                ) { Text("Stop") }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QB_TIMERS.forEach { (label, min) ->
                        FilterChip(
                            selected = customMin == null && timer == min,
                            onClick = {
                                timer = min
                                customMin = null
                            },
                            label = { Text(label) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("qb_timer_" + (min?.let { if (it < 60) "${it}m" else "${it / 60}h" } ?: "none")),
                        )
                    }
                    FilterChip(
                        selected = customMin != null,
                        onClick = { customOpen = true },
                        label = { Text(customMin?.let { Format.duration(it * 60_000L) } ?: "Custom") },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("qb_timer_custom"),
                    )
                }
                Button(
                    onClick = { onStart(customMin ?: timer) },
                    enabled = !qb.isEmpty,
                    colors = ButtonDefaults.buttonColors(containerColor = blocking.accent, contentColor = blocking.onAccent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("qb_start"),
                ) { Text("Start Quick Block", style = MaterialTheme.typography.titleMedium) }
                if (qb.isEmpty) {
                    Text(
                        "Add apps or sites with Edit first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (customOpen) {
        DurationDialog("Quick Block for", initialMin = customMin ?: 45, onDismiss = { customOpen = false }, onConfirm = {
            customOpen = false
            customMin = it
        })
    }
}

@Composable
private fun ScheduleCard(
    s: Schedule,
    status: ScheduleStatus?,
    state: HomeState,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onStartNow: () -> Unit,
    onPause: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val pill = StatePill.of(s, status, state.now, state.zone)
    val blocking = LocalBlockingColors.current
    val manual = ScheduleEvaluator.manualActive(s, state.now)
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .testTag("schedule_card_${s.name}")
            .clickable(onClickLabel = "Edit ${s.name}", onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.icon.ifEmpty { "🛡️" },
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics {
                        contentDescription =
                            ""
                    }
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        Format.summary(s),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("schedule_summary_${s.name}"),
                    )
                }
                Switch(
                    checked = s.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier
                        .testTag("schedule_switch_${s.name}")
                        // The switch announces its own on/off state; the label is just the schedule.
                        .semantics { contentDescription = "${s.name} schedule" },
                )
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag("schedule_menu_${s.name}")) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More for ${s.name}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.debugRoot()) {
                        DropdownMenuItem(
                            text = { Text("Start now…") },
                            leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                            onClick = {
                                menu = false
                                onStartNow()
                            },
                            modifier = Modifier.testTag("menu_start_now"),
                        )
                        if (!state.prefs.hidePauseOption) {
                            DropdownMenuItem(
                                text = { Text("Pause…") },
                                leadingIcon = { Icon(Icons.Default.Pause, null) },
                                onClick = {
                                    menu = false
                                    onPause()
                                },
                                modifier = Modifier.testTag("menu_pause"),
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = {
                                menu = false
                                onDuplicate()
                            },
                            modifier = Modifier.testTag("menu_duplicate"),
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                            modifier = Modifier.testTag("menu_delete"),
                        )
                    }
                }
            }
            Row(
                Modifier.padding(top = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val (bg, fg) = when (pill) {
                    is StatePill.Blocking -> blocking.container to blocking.onContainer
                    is StatePill.Counting -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Pill(
                    pill.label,
                    bg,
                    fg,
                    Modifier
                        .testTag("schedule_state_${s.name}")
                        .semantics { stateDescription = pill.label },
                )
                val detail = when (pill) {
                    is StatePill.Blocking -> pill.untilText
                    is StatePill.Paused -> pill.untilText
                    is StatePill.Scheduled -> pill.next?.let { "next $it" }
                    else -> null
                }
                if (!detail.isNullOrEmpty()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (pill is StatePill.Paused) {
                    TextButton(onClick = onResume, modifier = Modifier.testTag("schedule_resume_${s.name}")) { Text("Resume now") }
                } else if (manual) {
                    TextButton(onClick = onStop, modifier = Modifier.testTag("schedule_stop_${s.name}")) { Text("Stop") }
                }
            }
        }
    }
}
