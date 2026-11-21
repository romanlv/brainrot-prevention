package dev.brainrotprevention.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.brainrotprevention.ui.common.AppIcon
import dev.brainrotprevention.ui.common.debugRoot
import dev.brainrotprevention.ui.platform.UiApp

/** Popular social, video and game apps, listed first in the picker (§12). Our own list. */
val POPULAR_PACKAGES = listOf(
    "com.instagram.android",
    "com.zhiliaoapp.musically",
    "com.ss.android.ugc.trill",
    "com.google.android.youtube",
    "com.facebook.katana",
    "com.twitter.android",
    "com.reddit.frontpage",
    "com.snapchat.android",
    "com.instagram.barcelona",
    "com.pinterest",
    "com.tumblr",
    "com.bereal.ft",
    "com.netflix.mediaclient",
    "tv.twitch.android.app",
    "com.discord",
    "com.facebook.orca",
    "org.telegram.messenger",
    "com.whatsapp",
    "com.linkedin.android",
    "com.roblox.client",
    "com.mojang.minecraftpe",
    "com.supercell.clashofclans",
    "com.king.candycrushsaga",
    "com.spotify.music",
)

/** The selected apps with remove buttons and an "Add apps" button that opens the picker. */
@Composable
fun AppsList(
    selected: List<String>,
    labelOf: (String) -> String,
    allApps: List<UiApp>?,
    addLabel: String,
    onChange: (List<String>) -> Unit,
    removeVerb: String = "Remove",
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        selected.forEach { pkg ->
            val label = labelOf(pkg)
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .testTag("app_selected_$pkg"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(pkg, label)
                Spacer(Modifier.size(12.dp))
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                IconButton(onClick = { onChange(selected - pkg) }, modifier = Modifier.testTag("app_remove_$pkg")) {
                    Icon(Icons.Default.Close, contentDescription = "$removeVerb $label")
                }
            }
        }
        OutlinedButton(
            onClick = { picking = true },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("apps_add")
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(addLabel)
        }
    }
    if (picking) {
        AppPickerDialog(
            all = allApps,
            initial = selected,
            onDismiss = { picking = false },
            onDone = {
                picking = false
                onChange(it)
            },
        )
    }
}

/** Searchable multi-select with icons, popular apps first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerDialog(all: List<UiApp>?, initial: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(initial) } }
    var query by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier
                .fillMaxSize()
                .debugRoot()
                .testTag("app_picker"),
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Choose apps") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel") }
                        },
                        actions = {
                            TextButton(onClick = { onDone(chosen.toList()) }, modifier = Modifier.testTag("app_picker_done")) {
                                Text("Done (${chosen.size})")
                            }
                        },
                    )
                },
            ) { inner ->
                Column(
                    Modifier
                        .padding(inner)
                        .imePadding(),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        placeholder = { Text("Search apps") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("app_search"),
                    )
                    if (all == null) {
                        CircularProgressIndicator(Modifier.padding(32.dp).align(Alignment.CenterHorizontally))
                        return@Column
                    }
                    val q = query.trim().lowercase()
                    val matches = all.filter { q.isEmpty() || it.label.lowercase().contains(q) || it.packageName.contains(q) }
                    val popular = POPULAR_PACKAGES.mapNotNull { p -> matches.firstOrNull { it.packageName == p } }
                    val rest = matches.filter { it !in popular }
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (popular.isNotEmpty()) {
                            item { PickerHeader("Popular") }
                            items(popular, key = {
                                "p_" + it.packageName
                            }) { AppRow(it, it.packageName in chosen) { on -> chosen.toggle(it.packageName, on) } }
                            item { PickerHeader("All apps") }
                        }
                        items(rest, key = {
                            it.packageName
                        }) { AppRow(it, it.packageName in chosen) { on -> chosen.toggle(it.packageName, on) } }
                        if (matches.isEmpty()) {
                            item {
                                Text(
                                    "No apps match “$query”.",
                                    modifier = Modifier.padding(24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun MutableList<String>.toggle(pkg: String, on: Boolean) {
    if (on && pkg !in this) {
        add(pkg)
    } else if (!on) {
        remove(pkg)
    }
}

@Composable
private fun PickerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun AppRow(app: UiApp, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(checked, role = Role.Checkbox, onValueChange = onToggle)
            .testTag("app_row_${app.packageName}")
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, app.label)
        Spacer(Modifier.size(16.dp))
        Text(app.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

/** A read-only list of apps (allowlist essentials). */
@Composable
fun AppsReadOnly(apps: List<UiApp>) {
    Column {
        apps.forEach { a ->
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(a.packageName, a.label, size = 28.dp)
                Spacer(Modifier.size(12.dp))
                Text(a.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun PrimaryWideButton(text: String, onClick: () -> Unit, tag: String, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .testTag(tag)
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}
