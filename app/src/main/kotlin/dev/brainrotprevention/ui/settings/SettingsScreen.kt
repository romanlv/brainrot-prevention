package dev.brainrotprevention.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.brainrotprevention.BuildConfig
import dev.brainrotprevention.ui.UiDeps
import dev.brainrotprevention.ui.common.ConfirmDialog
import dev.brainrotprevention.ui.common.GrantRow
import dev.brainrotprevention.ui.common.Notice
import dev.brainrotprevention.ui.common.NoticeHost
import dev.brainrotprevention.ui.common.SETTINGS_LOCKED_TEXT
import dev.brainrotprevention.ui.common.Section
import dev.brainrotprevention.ui.common.Stepper
import dev.brainrotprevention.ui.common.SwitchRow
import dev.brainrotprevention.ui.common.debugRoot
import dev.brainrotprevention.ui.common.findActivity
import dev.brainrotprevention.ui.common.readText
import dev.brainrotprevention.ui.common.writeText
import dev.brainrotprevention.ui.depsViewModel
import dev.brainrotprevention.ui.format.Format
import dev.brainrotprevention.ui.platform.FixResult
import dev.brainrotprevention.ui.platform.UiGrant
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(deps: UiDeps, contentPadding: PaddingValues) {
    val vm = depsViewModel(deps) { SettingsViewModel(it) }
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val excluded by vm.excluded.collectAsStateWithLifecycle()
    val unsupported by vm.unsupported.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val pendingImport by vm.pendingImport.collectAsStateWithLifecycle()
    val xKeepFollowing by vm.xKeepFollowing.collectAsStateWithLifecycle()
    var howStrong by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope { dev.brainrotprevention.ui.UiGuard.handler }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshHealth() }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { vm.exported(writeText(context, uri, vm.exportText())) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.picked(readText(context, uri)) }
    }

    Column(
        Modifier
            .padding(contentPadding)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("settings_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Section(
            "Permissions",
            subtitle = if (health.ok) "Everything blocking needs is granted." else "Blocking needs the red items below."
        ) {
            if (health.advancedProtection) {
                Text(
                    "Advanced Protection prevents blocking apps from using accessibility; Brainrot Prevention can't work while it's on.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Device admin is added and removed by Strict Mode itself (§9.2), so it has no Fix row here.
            health.items.filter { it.grant != UiGrant.DEVICE_ADMIN }.forEach { item ->
                GrantRow(
                    item,
                    buttonLabel = "Fix",
                    onLocked = { vm.show(Notice.Locked(null, SETTINGS_LOCKED_TEXT)) },
                    onChanged = vm::refreshHealth,
                    tagPrefix = "health",
                )
            }
            if (health.restrictedInstall && health[UiGrant.ACCESSIBILITY]?.granted == false) {
                Text(
                    "If Android says the setting is restricted: open App info, tap ⋮ → Allow restricted settings, then try again.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = {
                        context.findActivity()?.let {
                            if (deps.platform.health.openAppDetails(it) == FixResult.LOCKED_BY_STRICT) {
                                vm.show(Notice.Locked(null, SETTINGS_LOCKED_TEXT))
                            }
                        }
                    },
                    modifier = Modifier.testTag("health_app_info"),
                ) { Text("Open App info") }
            }
        }
        val p = prefs
        if (p != null) {
            Section("Notifications") {
                Text("Warn before a budget runs out", style = MaterialTheme.typography.bodyLarge)
                Stepper(
                    p.limitWarningMin.toLong(),
                    { v -> vm.editPrefs { it.copy(limitWarningMin = v.toInt()) } },
                    0L..120L,
                    { v, up ->
                        if (up) {
                            v + 5 - v % 5
                        } else if (v % 5 == 0L) {
                            v - 5
                        } else {
                            v - v % 5
                        }
                    },
                    { if (it == 0L) "Off" else "$it min" },
                    tag = "pref_limit_warning",
                    label = "warning minutes"
                )
                Text("Remind me before a schedule starts", style = MaterialTheme.typography.bodyLarge)
                Stepper(
                    p.notifyBeforeStartMin.toLong(),
                    { v -> vm.editPrefs { it.copy(notifyBeforeStartMin = v.toInt()) } },
                    0L..120L,
                    { v, up ->
                        if (up) {
                            v + 5 - v % 5
                        } else if (v % 5 == 0L) {
                            v - 5
                        } else {
                            v - v % 5
                        }
                    },
                    { if (it == 0L) "Off" else "$it min" },
                    tag = "pref_before_start",
                    label = "minutes before start"
                )
                Text("Remind me before a pause ends", style = MaterialTheme.typography.bodyLarge)
                Stepper(
                    p.notifyBeforePauseEndMin.toLong(),
                    { v -> vm.editPrefs { it.copy(notifyBeforePauseEndMin = v.toInt()) } },
                    0L..60L,
                    { v, up -> if (up) v + 1 else v - 1 },
                    { if (it == 0L) "Off" else "$it min" },
                    tag = "pref_before_pause_end",
                    label = "minutes before pause end"
                )
            }
            Section("Budgets and pausing") {
                val effective = p.pendingDayStartHour ?: p.dayStartHour
                Text("Day starts at", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Daily budgets reset at this hour.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Stepper(
                    effective.toLong(),
                    { v -> vm.editPrefs { it.copy(dayStartHour = v.toInt()) } },
                    0L..23L,
                    { v, up ->
                        if (up) v + 1 else v - 1
                    },
                    { Format.minuteOfDay(it.toInt() * 60) },
                    tag = "pref_day_start",
                    label = "day start hour"
                )
                if (p.pendingDayStartHour != null) {
                    Text(
                        "Changes from ${Format.minuteOfDay(
                            p.dayStartHour * 60
                        )} to ${Format.minuteOfDay(effective * 60)} at the next daily reset, " +
                            "so today's budgets aren't reset early.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("pref_day_start_pending"),
                    )
                }
                SwitchRow(
                    "Hide the pause option",
                    p.hidePauseOption,
                    { on -> vm.editPrefs { it.copy(hidePauseOption = on) } },
                    tag = "pref_hide_pause",
                    subtitle = "Removes Pause from schedule cards and the block screen. The on/off switch stays.",
                )
            }
        }
        Section(
            "Excluded browsers",
            subtitle = "Apps that open web links but aren't browsers. They aren't blocked as unsupported browsers."
        ) {
            val all = (excluded + unsupported.map { it.packageName }).distinct()
            if (all.isEmpty()) Text("No unsupported browsers found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            all.forEach { pkg ->
                val isEx = pkg in excluded
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("excluded_$pkg"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(unsupported.firstOrNull { it.packageName == pkg }?.label ?: vm.label(pkg))
                        Text(if (isEx) "Excluded" else "Blocked while websites are blocked", style = MaterialTheme.typography.bodySmall)
                    }
                    if (isEx) {
                        IconButton(onClick = { vm.setExcluded(pkg, false) }, modifier = Modifier.testTag("excluded_remove_$pkg")) {
                            Icon(Icons.Default.Close, contentDescription = "Stop excluding")
                        }
                    } else {
                        TextButton(onClick = {
                            vm.setExcluded(pkg, true)
                        }, modifier = Modifier.testTag("excluded_add_$pkg")) { Text("Exclude") }
                    }
                }
            }
        }
        Section("Experimental", subtitle = "Try these out; switch them off if they misbehave. They aren't saved in backups.") {
            SwitchRow(
                "X: keep Home on Following",
                xKeepFollowing,
                vm::setXKeepFollowing,
                tag = "pref_x_following",
                subtitle = "When X's Home opens on \"For you\", taps \"Following\" for you. Search, profiles and messages are left alone.",
            )
        }
        Section("Backup", subtitle = "Saves schedules, Quick Block items and settings to a file you choose. Nothing is uploaded.") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { exportLauncher.launch(vm.suggestedBackupName()) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("backup_export")
                ) { Text("Export") }
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("backup_import")
                ) { Text("Import") }
            }
        }
        Section("About") {
            Text("Brainrot Prevention ${BuildConfig.VERSION_NAME}")
            Text(
                "Local only: no accounts, ads or trackers, and no data leaves your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { howStrong = true },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("about_how_strong")
            ) { Text("How strong is this?") }
        }
        Spacer(Modifier.size(24.dp))
    }

    pendingImport?.let {
        ConfirmDialog(
            title = "Replace everything?",
            text = "Importing replaces all schedules, Quick Block items, excluded browsers and settings with the ones in the file.",
            confirm = "Replace",
            onConfirm = vm::confirmImport,
            onDismiss = vm::cancelImport,
            tag = "import_confirm",
        )
    }
    if (howStrong) HowStrongDialog { howStrong = false }
    NoticeHost(notice) { vm.show(null) }
}

/** §17 known bypasses in plain words. */
val BYPASSES = listOf(
    "Safe mode" to "Restarting in safe mode turns off other apps' accessibility services, and device admin can be removed there.",
    "A computer with ADB" to "Developer tools can turn off or uninstall anything.",
    "Factory reset" to "Erasing the phone removes the app and its settings.",
    "Other users and profiles" to "Guest mode, other users, the work profile, Private Space, Secure Folder and app clones aren't covered.",
    "Browsers inside apps" to "Web pages opened inside apps like Instagram or Telegram can't be read.",
    "Chat heads and bubbles" to "Floating chat windows may stay usable.",
    "Battery savers" to "Some phones close background apps; allow Brainrot Prevention to run without battery limits.",
    "Advanced Protection" to "On Android 17 and later it blocks accessibility for apps like this one.",
    "Accessibility shortcut" to "A shortcut set up after Strict Mode starts can switch the service off unless Settings are blocked.",
)

@Composable
private fun HowStrongDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .debugRoot()
            .testTag("how_strong_dialog"),
        title = { Text("How strong is this?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Brainrot Prevention makes giving in slow and deliberate. It can't make it impossible: without full control of the " +
                        "phone, a determined person can get around it like this.",
                )
                BYPASSES.forEach { (title, text) ->
                    Column {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("how_strong_ok")) { Text("Got it") } },
    )
}
