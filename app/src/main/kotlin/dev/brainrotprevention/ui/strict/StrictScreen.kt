package dev.brainrotprevention.ui.strict

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.brainrotprevention.engine.Periods
import dev.brainrotprevention.engine.StrictPolicy
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.StrictMethod
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.ui.UiDeps
import dev.brainrotprevention.ui.common.NoticeHost
import dev.brainrotprevention.ui.common.OnScreenWait
import dev.brainrotprevention.ui.common.Pill
import dev.brainrotprevention.ui.common.Section
import dev.brainrotprevention.ui.common.Stepper
import dev.brainrotprevention.ui.common.SwitchRow
import dev.brainrotprevention.ui.depsViewModel
import dev.brainrotprevention.ui.format.Format
import dev.brainrotprevention.ui.theme.LocalBlockingColors
import kotlinx.coroutines.delay

@Composable
fun StrictScreen(deps: UiDeps, contentPadding: PaddingValues) {
    val vm = depsViewModel(deps) { StrictViewModel(it) }
    val st by vm.state.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val adminIntent by vm.adminIntent.collectAsStateWithLifecycle()
    val emergency by vm.emergency.collectAsStateWithLifecycle()
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.onAdminResult() }
    LaunchedEffect(adminIntent) { adminIntent?.let { runCatching { adminLauncher.launch(it) }.onFailure { vm.onAdminResult() } } }

    Column(
        Modifier
            .padding(contentPadding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("strict_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!st.loaded) return@Column
        if (st.row.isOn) StrictOn(vm, st) else StrictInactive(vm, st)
    }
    emergency?.let { EmergencyFlow(vm, it, st) }
    NoticeHost(notice, vm::dismissNotice)
}

// ---------------------------------------------------------------- inactive

private val TIMER_CHOICES = listOf(
    "15 min" to 15 * Periods.MINUTE,
    "1 h" to Periods.HOUR,
    "4 h" to 4 * Periods.HOUR,
    "1 day" to Periods.DAY,
    "7 days" to 7 * Periods.DAY,
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun StrictInactive(vm: StrictViewModel, st: StrictUi) {
    val f by vm.form.collectAsStateWithLifecycle()
    val unsupported by vm.unsupported.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    Text("Strict Mode", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
    Text(
        "Makes your blocking hard to undo. While it's on, schedules can only get stricter, and Settings and uninstalling " +
            "can be blocked, until the unlock condition you choose now is met.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Section("What it locks") {
        SwitchRow(
            "Lock editing",
            StrictOptions.has(f.options, StrictOptions.LOCK_EDITING),
            { on -> vm.editForm { it.toggle(StrictOptions.LOCK_EDITING, on) } },
            tag = "strict_opt_lock",
            subtitle = "No pausing, deleting or loosening schedules",
        )
        SwitchRow(
            "Block Settings",
            StrictOptions.has(f.options, StrictOptions.BLOCK_SETTINGS),
            { on -> vm.editForm { it.toggle(StrictOptions.BLOCK_SETTINGS, on) } },
            tag = "strict_opt_settings",
            subtitle = "The Settings app can't be opened",
        )
        SwitchRow(
            "Block uninstalling",
            StrictOptions.has(f.options, StrictOptions.BLOCK_UNINSTALL),
            { on -> vm.editForm { it.toggle(StrictOptions.BLOCK_UNINSTALL, on) } },
            tag = "strict_opt_uninstall",
            subtitle = "Uses device admin. Also turns on Block Settings.",
        )
        if (!StrictOptions.has(f.options, StrictOptions.BLOCK_SETTINGS)) {
            Row(Modifier.testTag("strict_settings_warning"), verticalAlignment = Alignment.Top) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.size(8.dp))
                Text(
                    "Without blocking Settings, Strict Mode can be undone by turning off accessibility, clearing app data or changing the clock.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    Section("How it ends") {
        MethodRow(f.method, StrictMethod.TIMER, "Timer", "Ends after a set time", vm)
        MethodRow(f.method, StrictMethod.SCHEDULES, "Schedules", "Active while followed schedules block; can be turned off in between", vm)
        MethodRow(f.method, StrictMethod.CHARGER, "Charger", "Unlock only while the phone is plugged in", vm)
        MethodRow(f.method, StrictMethod.PIN, "PIN", "Someone else sets a PIN; you need it to unlock", vm)
        MethodRow(f.method, StrictMethod.COOLDOWN, "Cooldown", "Unlock after waiting on this screen", vm)
        when (f.method) {
            StrictMethod.TIMER -> {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TIMER_CHOICES.forEach { (label, ms) ->
                        FilterChip(
                            f.timerMs == ms,
                            onClick = { vm.editForm { it.copy(timerMs = ms) } },
                            label = { Text(label) },
                            modifier = Modifier.testTag("strict_timer_${label.replace(" ", "")}")
                        )
                    }
                }
                Stepper(
                    value = f.timerMs,
                    onChange = { v -> vm.editForm { it.copy(timerMs = v) } },
                    range = StrictPolicy.TIMER_MIN_MS..StrictPolicy.TIMER_MAX_MS,
                    step = { v, up ->
                        val s = if (v < Periods.DAY || (v == Periods.DAY && !up)) 15 * Periods.MINUTE else 6 * Periods.HOUR
                        if (up) (v / s + 1) * s else ((v - 1) / s) * s
                    },
                    format = { Format.duration(it) },
                    tag = "strict_timer",
                    label = "duration",
                )
            }
            StrictMethod.SCHEDULES -> {
                SwitchRow(
                    "Follow all schedules",
                    f.followAll,
                    { on -> vm.editForm { it.copy(followAll = on) } },
                    tag = "strict_follow_all",
                    subtitle = "Including Quick Block and schedules you add later",
                )
                if (!f.followAll) {
                    if (st.followable.isEmpty()) Text("No blocklist schedules yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    st.followable.forEach { s ->
                        SwitchRow("${s.icon} ${s.name}", s.strictFollowed, { vm.setFollowed(s, it) }, tag = "strict_follow_${s.name}")
                    }
                }
            }
            StrictMethod.CHARGER -> Text("To unlock, plug in the charger and tap Unlock here.", style = MaterialTheme.typography.bodyMedium)
            StrictMethod.PIN -> {
                Text("Ask someone you trust to type a PIN of 4–8 digits and keep it.", style = MaterialTheme.typography.bodyMedium)
                PinField(f.pin, { v -> vm.editForm { it.copy(pin = v) } }, "PIN", "strict_pin")
                PinField(f.pinConfirm, { v -> vm.editForm { it.copy(pinConfirm = v) } }, "Repeat PIN", "strict_pin_confirm")
                if (f.pin.isNotEmpty()) {
                    f.pinProblem?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            StrictMethod.COOLDOWN -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StrictPolicy.COOLDOWN_CHOICES.sorted().forEach { m ->
                    FilterChip(
                        f.cooldownMin == m,
                        onClick = { vm.editForm { it.copy(cooldownMin = m) } },
                        label = { Text("$m min") },
                        modifier = Modifier.testTag("strict_cooldown_$m")
                    )
                }
            }
        }
    }
    if (st.pausedOrOff.isNotEmpty()) {
        Section("Paused or off", subtitle = "These won't block while paused or off. Resume them before you lock in?") {
            st.pausedOrOff.forEach { Text("${it.icon} ${it.name} · ${if (!it.enabled) "off" else "paused"}") }
            FilledTonalButton(
                onClick = vm::resumeAll,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("strict_resume_all")
            ) { Text("Resume all") }
        }
    }
    if (unsupported.isNotEmpty()) {
        Section(
            "Browsers that can't be checked",
            subtitle = "While a schedule with websites runs, these are blocked as apps. Exclude any that aren't really browsers " +
                "now: this can't be changed while Strict Mode is on.",
        ) {
            unsupported.forEach { a ->
                SwitchRow(
                    "Exclude ${a.label}",
                    a.packageName in st.excludedBrowsers,
                    { vm.setExcluded(a.packageName, it) },
                    tag = "strict_exclude_${a.packageName}",
                )
            }
        }
    }
    if (st.notCovered.isNotEmpty()) {
        Section(
            "Not covered by Strict Mode",
            subtitle = "“Allow only selected” schedules can't be followed or locked by the Schedules method."
        ) {
            st.notCovered.forEach { Text("${it.icon} ${it.name}", modifier = Modifier.testTag("strict_not_covered_${it.name}")) }
        }
    }
    val blocking = LocalBlockingColors.current
    Button(
        onClick = vm::activate,
        enabled = !busy,
        colors = ButtonDefaults.buttonColors(containerColor = blocking.accent, contentColor = blocking.onAccent),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag("strict_activate"),
    ) {
        Icon(Icons.Default.Lock, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text("Turn on Strict Mode", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun MethodRow(current: StrictMethod, m: StrictMethod, title: String, subtitle: String, vm: StrictViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(current == m, role = Role.RadioButton) { vm.editForm { it.copy(method = m) } }
            .testTag("strict_method_${m.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = current == m, onClick = null)
        Spacer(Modifier.size(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String, tag: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(8)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag),
    )
}

// ---------------------------------------------------------------- on

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.StrictOn(vm: StrictViewModel, st: StrictUi) {
    val row = st.row
    val wait by vm.wait.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val blocking = LocalBlockingColors.current
    val active = st.display == StrictDisplayState.ACTIVE
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = if (active) blocking.accent else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(96.dp),
        )
        Text("Strict Mode is on", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Pill(
            if (active) "Active" else "Standby",
            if (active) blocking.container else MaterialTheme.colorScheme.secondaryContainer,
            if (active) blocking.onContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            Modifier.testTag("strict_state"),
        )
        row.activatedAt?.let { Text("On since ${Format.at(it, st.now, st.zone)}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val locks = listOfNotNull(
            "editing".takeIf { row.has(StrictOptions.LOCK_EDITING) },
            "Settings".takeIf { row.has(StrictOptions.BLOCK_SETTINGS) },
            "uninstall".takeIf { row.has(StrictOptions.BLOCK_UNINSTALL) },
        )
        if (locks.isNotEmpty()) Text("Locks " + locks.joinToString(", "), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!active) {
            Text(
                "Nothing followed is blocking, so Settings and uninstall aren't blocked right now. Followed schedules stay locked.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                Format.unlockCondition(row, st.display, st.now, st.elapsed, st.zone),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("strict_unlock_condition"),
            )
            val w = wait
            if (w != null && w.kind != WaitKind.EMERGENCY) {
                WaitPanel(vm, w)
            } else {
                when (row.method) {
                    StrictMethod.TIMER -> {
                        val left = StrictPolicy.timerRemaining(row, st.now, st.elapsed)
                        Text(
                            Format.countdown(left),
                            style = MaterialTheme.typography.displaySmall,
                            modifier = Modifier.testTag("strict_timer_remaining")
                        )
                        val start = row.activatedAt
                        val end = row.endsAt
                        if (start != null && end != null && end > start) {
                            LinearProgressIndicator(
                                progress = { ((st.now - start).toFloat() / (end - start)).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text("Need longer?", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "+15 min" to 15 * Periods.MINUTE,
                                "+1 h" to Periods.HOUR,
                                "+1 day" to Periods.DAY
                            ).forEach { (label, ms) ->
                                OutlinedButton(
                                    onClick = { vm.extendTimer(ms) },
                                    enabled = !busy,
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("strict_extend_${label.removePrefix("+").replace(" ", "")}")
                                ) { Text(label) }
                            }
                        }
                    }
                    StrictMethod.SCHEDULES -> {
                        Text(
                            "Following: " + st.followedNames.joinToString(", ").ifEmpty {
                                "nothing"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (st.display == StrictDisplayState.STANDBY) {
                            FilledTonalButton(
                                onClick = { vm.startWait(WaitKind.STANDBY_OFF) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("strict_standby_off")
                            ) { Text("Turn off Strict Mode (5 min wait)") }
                        }
                    }
                    StrictMethod.CHARGER -> FilledTonalButton(
                        onClick = vm::unlockWithCharger,
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("strict_unlock_charger"),
                    ) { Text("Unlock") }
                    StrictMethod.PIN -> PinUnlock(vm, st)
                    StrictMethod.COOLDOWN -> FilledTonalButton(
                        onClick = { vm.startWait(WaitKind.COOLDOWN) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("strict_cooldown_start"),
                    ) { Text("Start the ${row.cooldownMin}-minute cooldown") }
                }
            }
        }
    }
    Spacer(Modifier.size(24.dp))
    TextButton(
        onClick = vm::openEmergency,
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .heightIn(min = 48.dp)
            .testTag("strict_emergency")
    ) { Text("Emergency") }
}

/** Countdown with progress; screen kept on; cancelled by ON_STOP (§9.5). */
@Composable
private fun WaitPanel(vm: StrictViewModel, w: Wait) {
    var remaining by remember(w) { mutableLongStateOf(vm.remaining(w)) }
    OnScreenWait(onStop = vm::cancelWait)
    LaunchedEffect(w) {
        while (true) {
            remaining = vm.remaining(w)
            if (remaining <= 0) {
                vm.finishWait()
                break
            }
            delay(250)
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Keep this screen open", style = MaterialTheme.typography.titleMedium)
        Text(
            Format.countdown(remaining),
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier
                .testTag("strict_wait_remaining")
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        LinearProgressIndicator(progress = { 1f - remaining.toFloat() / w.durationMs }, modifier = Modifier.fillMaxWidth())
        Text(
            "Leaving this screen or turning it off starts the wait over.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        TextButton(onClick = vm::cancelWait, modifier = Modifier.testTag("strict_wait_cancel")) { Text("Cancel") }
    }
}

@Composable
private fun PinUnlock(vm: StrictViewModel, st: StrictUi) {
    val msg by vm.pinMessage.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    val row = st.row
    val lockedOut = StrictPolicy.pinLockedOut(row, st.now, st.elapsed)
    val lockLeft = if (lockedOut) {
        maxOf((row.pinLockedUntilWall ?: 0) - st.now, (row.pinLockedUntilElapsed ?: 0) - st.elapsed, 0)
    } else {
        0L
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (lockedOut) {
            Text(
                "Too many wrong PINs. Try again in ${Format.countdown(lockLeft)}.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("pin_lockout"),
            )
        } else if (row.pinFailures > 0) {
            Text("${row.pinFailures} wrong ${if (row.pinFailures == 1) "try" else "tries"}", style = MaterialTheme.typography.bodySmall)
        }
        PinPad(
            value = pin,
            onValue = { pin = it },
            enabled = !lockedOut && !busy,
            onOk = {
                vm.unlockWithPin(pin)
                pin = ""
            },
        )
        msg?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .testTag("pin_message")
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

/** A 3×4 PIN pad with 64 dp keys. */
@Composable
fun PinPad(value: String, onValue: (String) -> Unit, enabled: Boolean, onOk: () -> Unit) {
    Text(
        if (value.isEmpty()) "Enter PIN" else "•".repeat(value.length),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier
            .padding(vertical = 8.dp)
            .testTag("pin_display"),
    )
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
    rows.forEach { r ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            r.forEach { k ->
                val tag = when (k) {
                    "⌫" -> "pin_back"
                    "OK" -> "pin_ok"
                    else -> "pin_key_$k"
                }
                FilledTonalButton(
                    onClick = {
                        when (k) {
                            "⌫" -> onValue(value.dropLast(1))
                            "OK" -> onOk()
                            else -> if (value.length < 8) onValue(value + k)
                        }
                    },
                    enabled = enabled && (k != "OK" || value.length >= 4),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .size(72.dp)
                        .testTag(tag),
                ) { Text(if (k == "⌫") "Del" else k, style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}
