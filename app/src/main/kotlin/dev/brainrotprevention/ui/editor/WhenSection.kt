package dev.brainrotprevention.ui.editor

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.brainrotprevention.engine.ScheduleValidator
import dev.brainrotprevention.model.Combine
import dev.brainrotprevention.model.LocationCondition
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.model.TimeWindow
import dev.brainrotprevention.ui.LocalUiDeps
import dev.brainrotprevention.ui.common.SwitchRow
import dev.brainrotprevention.ui.common.debugRoot
import dev.brainrotprevention.ui.common.findActivity
import dev.brainrotprevention.ui.format.Format
import dev.brainrotprevention.ui.launchGuarded
import dev.brainrotprevention.ui.platform.UiGrant
import java.util.Locale
import kotlinx.coroutines.launch

private val DAY_NAMES = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

/** The "When" section: time windows, Wi-Fi, locations and (with ≥ 2 trigger types) Combine (§12, PL-m1). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WhenEditor(s: Schedule, onChange: ((Schedule) -> Schedule) -> Unit) {
    val deps = LocalUiDeps.current
    val context = LocalContext.current
    var wifiOpen by rememberSaveable { mutableStateOf(false) }
    var locationOpen by rememberSaveable { mutableStateOf(false) }
    // Which dialog to open once location is granted ("wifi" / "location"); saveable across the permission prompt.
    var pendingTrigger by rememberSaveable { mutableStateOf<String?>(null) }
    var deniedFor by rememberSaveable { mutableStateOf<String?>(null) }
    val perm = deps.platform.health.runtimePermission(UiGrant.LOCATION_FINE)

    fun open(kind: String) {
        if (kind == "wifi") wifiOpen = true else locationOpen = true
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val kind = pendingTrigger
        pendingTrigger = null
        if (kind == null) return@rememberLauncherForActivityResult
        // Denied: the trigger could never match (no Wi-Fi name, no location), so say why instead of adding it.
        if (granted) open(kind) else deniedFor = kind
    }

    /** §11.3: location is asked only when a Wi-Fi or location trigger is added. */
    fun withLocation(kind: String) {
        if (perm != null && ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
            pendingTrigger = kind
            permLauncher.launch(perm)
        } else {
            open(kind)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.triggerTypes.isEmpty()) {
            Text(
                if (s.limits.isEmpty()) {
                    "No times or places: this schedule blocks all the time."
                } else {
                    "No times or places: the budget counts all day."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("when_always"),
            )
        }
        s.timeWindows.forEachIndexed { i, w ->
            WindowRow(i, w, onChange = { nw ->
                onChange { it.copy(timeWindows = it.timeWindows.toMutableList().also { l -> l[i] = nw }) }
            }, onRemove = {
                onChange { it.copy(timeWindows = it.timeWindows.filterIndexed { j, _ -> j != i }) }
            })
        }
        s.wifi.forEach { row ->
            TriggerRow(Icons.Default.Wifi, "On Wi-Fi “${row.ssid}”", "wifi_${row.ssid}") { onChange { it.copy(wifi = it.wifi - row) } }
        }
        s.locations.forEachIndexed { i, row ->
            TriggerRow(
                Icons.Default.Place,
                (if (row.inverted) "Not at " else "At ") + "${row.label} · ${row.radiusM} m",
                "location_$i",
            ) { onChange { it.copy(locations = it.locations - row) } }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    onChange { it.copy(timeWindows = it.timeWindows + EditorOps.defaultWindow(it.timeWindows)) }
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("when_add_window")
            ) {
                Icon(Icons.Default.Schedule, null)
                Text(" Time")
            }
            OutlinedButton(
                onClick = { withLocation("wifi") },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("when_add_wifi")
            ) {
                Icon(Icons.Default.Wifi, null)
                Text(" Wi-Fi")
            }
            OutlinedButton(
                onClick = { withLocation("location") },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("when_add_location")
            ) {
                Icon(Icons.Default.Place, null)
                Text(" Place")
            }
        }
        if (s.wifi.isNotEmpty() || s.locations.isNotEmpty()) BackgroundLocationHint()
        if (s.triggerTypes.size >= 2) {
            Text("Block when", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = s.combine == Combine.ANY,
                    onClick = { onChange { it.copy(combine = Combine.ANY) } },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    modifier = Modifier.testTag("combine_any"),
                ) { Text("Any of these") }
                SegmentedButton(
                    selected = s.combine == Combine.ALL,
                    onClick = { onChange { it.copy(combine = Combine.ALL) } },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    modifier = Modifier.testTag("combine_all"),
                ) { Text("All together") }
            }
        }
    }
    deniedFor?.let { kind ->
        AlertDialog(
            onDismissRequest = { deniedFor = null },
            modifier = Modifier
                .debugRoot()
                .testTag("location_denied"),
            title = { Text("Location is needed") },
            text = {
                Text(
                    if (kind == "wifi") {
                        "Android only tells apps the Wi-Fi name when they may use location, so a Wi-Fi condition would never " +
                            "match. Allow location for Brainrot Prevention (in App info → Permissions if the prompt doesn't " +
                            "appear) and add it again."
                    } else {
                        "A place condition needs location to know where you are; without it, it would never match. Allow " +
                            "location for Brainrot Prevention (in App info → Permissions if the prompt doesn't appear) and add it again."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { deniedFor = null }, modifier = Modifier.testTag("location_denied_ok")) { Text("OK") }
            },
        )
    }
    if (wifiOpen) {
        WifiDialog(onDismiss = { wifiOpen = false }, onAdd = { ssid ->
            wifiOpen = false
            onChange { cur -> EditorOps.addWifi(cur.wifi, ssid)?.let { cur.copy(wifi = it) } ?: cur }
        })
    }
    if (locationOpen) {
        LocationDialog(onDismiss = { locationOpen = false }, onAdd = { row ->
            locationOpen = false
            onChange { it.copy(locations = EditorOps.addLocation(it.locations, row)) }
        })
    }
}

@Composable
private fun BackgroundLocationHint() {
    val deps = LocalUiDeps.current
    val context = LocalContext.current
    var health by remember { mutableStateOf(runCatching { deps.platform.health.check() }.getOrNull()) }
    // Re-checked on resume: the user comes back from the location settings with the grant changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { health = runCatching { deps.platform.health.check() }.getOrNull() }
    val bg = health?.get(UiGrant.LOCATION_BACKGROUND)
    if (bg != null && !bg.granted) {
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Wi-Fi and places are checked in the background only with location set to “Allow all the time”.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = {
                    context.findActivity()?.let { deps.platform.health.open(it, UiGrant.LOCATION_BACKGROUND) }
                }, modifier = Modifier.testTag("when_bg_location")) {
                    Text("Open location settings")
                }
            }
        }
    }
}

@Composable
private fun TriggerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tag: String, onRemove: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        IconButton(onClick = onRemove, modifier = Modifier.testTag("${tag}_remove")) {
            Icon(Icons.Default.Close, contentDescription = "Remove $text")
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WindowRow(i: Int, w: TimeWindow, onChange: (TimeWindow) -> Unit, onRemove: () -> Unit) {
    var picking by remember { mutableStateOf<Boolean?>(null) } // true = start, false = end
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .testTag("window_$i")
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0..6).forEach { d ->
                    val on = w.days and (1 shl d) != 0
                    FilterChip(
                        selected = on,
                        onClick = { EditorOps.toggleDay(w.days, d).takeIf { it != 0 }?.let { onChange(w.copy(days = it)) } },
                        label = { Text(Format.dayShort(d).take(2)) },
                        modifier = Modifier
                            .testTag("window_${i}_day_$d")
                            .semantics {
                                contentDescription = DAY_NAMES[d]
                                stateDescription = if (on) "on" else "off"
                            },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { picking = true },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("window_${i}_start")
                        .semantics { contentDescription = "Starts at ${Format.minuteOfDay(w.startMin)}" },
                ) {
                    Text(Format.minuteOfDay(w.startMin), style = MaterialTheme.typography.titleLarge)
                }
                Text("to")
                TextButton(
                    onClick = { picking = false },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("window_${i}_end")
                        .semantics {
                            contentDescription =
                                "Ends at ${Format.minuteOfDay(w.endMin)}" + if (w.overnight) " the next day" else ""
                        },
                ) {
                    Text(Format.minuteOfDay(w.endMin), style = MaterialTheme.typography.titleLarge)
                }
                if (w.overnight) {
                    Text(
                        "(ends next day)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("window_${i}_overnight")
                    )
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onRemove, modifier = Modifier.testTag("window_${i}_remove")) {
                        Icon(Icons.Default.Close, contentDescription = "Remove time ${Format.window(w)}")
                    }
                }
            }
            if (w.startMin == w.endMin % 1440 && w.startMin == w.endMin) {
                Text(
                    "Start and end can't be the same.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
    picking?.let { start ->
        val m = if (start) w.startMin else w.endMin % 1440
        TimeDialog(title = if (start) "Starts at" else "Ends at", initial = m, onDismiss = { picking = null }, onPick = { h, min ->
            picking = null
            onChange(if (start) w.copy(startMin = h * 60 + min) else w.copy(endMin = EditorOps.endMinute(h, min)))
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(title: String, initial: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .debugRoot()
            .testTag("time_dialog"),
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimePicker(state) } },
        confirmButton = {
            TextButton(onClick = { onPick(state.hour, state.minute) }, modifier = Modifier.testTag("time_ok")) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WifiDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val deps = LocalUiDeps.current
    val current = remember { runCatching { deps.platform.apps.currentSsid() }.getOrNull() }
    var ssid by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .debugRoot()
            .testTag("wifi_dialog"),
        title = { Text("Block on Wi-Fi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current != null) {
                    AssistChip(
                        onClick = { ssid = current },
                        label = { Text("Use current network: $current") },
                        leadingIcon = { Icon(Icons.Default.Wifi, null) },
                        modifier = Modifier.testTag("wifi_use_current"),
                    )
                }
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text("Network name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("wifi_input"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(ssid) }, enabled = ssid.isNotBlank(), modifier = Modifier.testTag("wifi_ok")) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LocationDialog(onDismiss: () -> Unit, onAdd: (LocationCondition) -> Unit) {
    val deps = LocalUiDeps.current
    val scope = rememberCoroutineScope()
    var label by rememberSaveable { mutableStateOf("") }
    var lat by rememberSaveable { mutableStateOf("") }
    var lon by rememberSaveable { mutableStateOf("") }
    var radius by remember { mutableFloatStateOf(300f) }
    var inverted by rememberSaveable { mutableStateOf(false) }
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    val latD = lat.replace(',', '.').toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val lonD = lon.replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .debugRoot()
            .testTag("location_dialog"),
        title = { Text("Block at a place") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    label,
                    {
                        label = it.take(40)
                    },
                    label = { Text("Name, e.g. Office") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("loc_label")
                )
                AssistChip(
                    onClick = {
                        scope.launchGuarded {
                            val here = runCatching { deps.platform.apps.currentLocation() }.getOrNull()
                            if (here == null) {
                                info = "Current location isn't available. Enter coordinates instead."
                            } else {
                                lat = "%.6f".format(Locale.ROOT, here.first)
                                lon = "%.6f".format(Locale.ROOT, here.second)
                                info = null
                            }
                        }
                    },
                    label = { Text("Use current location") },
                    leadingIcon = { Icon(Icons.Default.MyLocation, null) },
                    modifier = Modifier.testTag("loc_current"),
                )
                info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        lat,
                        { lat = it },
                        label = { Text("Latitude") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = lat.isNotEmpty() && latD == null,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("loc_lat")
                    )
                    OutlinedTextField(
                        lon,
                        { lon = it },
                        label = { Text("Longitude") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = lon.isNotEmpty() && lonD == null,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("loc_lon")
                    )
                }
                Text("Radius: ${radius.toInt()} m", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = radius,
                    onValueChange = { radius = (it / 50f).toInt() * 50f },
                    valueRange = ScheduleValidator.RADIUS_MIN.toFloat()..ScheduleValidator.RADIUS_MAX.toFloat(),
                    modifier = Modifier
                        .testTag("loc_radius")
                        .semantics { contentDescription = "Radius" },
                )
                SwitchRow("When I'm NOT here", inverted, {
                    inverted = it
                }, tag = "loc_inverted", subtitle = "Blocks everywhere except this place")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onAdd(
                        LocationCondition(
                            label = label.trim(),
                            lat = latD!!,
                            lon = lonD!!,
                            radiusM = radius.toInt().coerceIn(ScheduleValidator.RADIUS_MIN, ScheduleValidator.RADIUS_MAX),
                            inverted = inverted,
                        ),
                    )
                },
                enabled = label.isNotBlank() && latD != null && lonD != null,
                modifier = Modifier.testTag("loc_ok"),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
