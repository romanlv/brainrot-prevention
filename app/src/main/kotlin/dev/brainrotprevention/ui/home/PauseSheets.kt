package dev.brainrotprevention.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.brainrotprevention.data.PausePreset
import dev.brainrotprevention.engine.ScheduleEvaluator
import dev.brainrotprevention.model.Schedule
import dev.brainrotprevention.ui.common.Stepper
import dev.brainrotprevention.ui.common.debugRoot
import dev.brainrotprevention.ui.format.Format
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
private fun OptionRow(text: String, icon: ImageVector, tag: String, onClick: () -> Unit, subtitle: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (subtitle !=
                null
            ) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

sealed interface PauseChoice {
    data class Minutes(val minutes: Int, val custom: Boolean) : PauseChoice

    data object EndOfToday : PauseChoice

    data class Until(val at: Long) : PauseChoice

    data object TurnOff : PauseChoice
}

/** §8 pause options: 15 min, 1 h, end of today, the last 3 custom durations, custom, until a date/time, turn off. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PauseSheet(name: String, presets: List<PausePreset>, now: Long, zone: ZoneId, onChoice: (PauseChoice) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf(false) }
    var until by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .debugRoot()
                .testTag("pause_sheet")
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                "Pause $name",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            OptionRow("15 minutes", Icons.Default.PauseCircle, "pause_option_15m", { onChoice(PauseChoice.Minutes(15, false)) })
            OptionRow("1 hour", Icons.Default.PauseCircle, "pause_option_1h", { onChoice(PauseChoice.Minutes(60, false)) })
            OptionRow("Until the end of today", Icons.Default.PauseCircle, "pause_option_today", { onChoice(PauseChoice.EndOfToday) })
            presets.filter { it.durationMin != 15 && it.durationMin != 60 }.take(3).forEach { p ->
                OptionRow(
                    Format.duration(p.durationMin * 60_000L),
                    Icons.Default.Timer,
                    "pause_option_preset_${p.durationMin}",
                    { onChoice(PauseChoice.Minutes(p.durationMin, true)) },
                    subtitle = "Used recently",
                )
            }
            OptionRow("Custom…", Icons.Default.Tune, "pause_option_custom", { custom = true })
            OptionRow("Until a date and time…", Icons.Default.Event, "pause_option_until", { until = true })
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            OptionRow(
                "Turn off",
                Icons.Default.PowerSettingsNew,
                "pause_option_off",
                { onChoice(PauseChoice.TurnOff) },
                subtitle = "Stays off until you turn it on again",
            )
        }
    }
    if (custom) {
        DurationDialog(
            title = "Pause for",
            initialMin = 30,
            onDismiss = { custom = false },
            onConfirm = {
                custom = false
                onChoice(PauseChoice.Minutes(it, true))
            },
        )
    }
    if (until) {
        DateTimeDialog(now = now, zone = zone, onDismiss = { until = false }, onPick = {
            until = false
            onChoice(PauseChoice.Until(it))
        })
    }
}

/** Start now…: 30 min, 1 h, 2 h, until I stop it (§8). [onPick] gets minutes, null = until stopped. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartNowSheet(name: String, onPick: (Int?) -> Unit, onDismiss: () -> Unit, alsoArms: String? = null) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .debugRoot()
                .testTag("start_sheet")
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                "Start $name now",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            Text(
                "Blocks right away, even outside its times. With a budget, the budget counts from now.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            if (alsoArms != null) {
                Text(
                    alsoArms,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                        .testTag("start_also_arms"),
                )
            }
            OptionRow("30 minutes", Icons.Default.PlayArrow, "start_option_30m", { onPick(30) })
            OptionRow("1 hour", Icons.Default.PlayArrow, "start_option_1h", { onPick(60) })
            OptionRow("2 hours", Icons.Default.PlayArrow, "start_option_2h", { onPick(120) })
            OptionRow("Until I stop it", Icons.Default.PlayArrow, "start_option_stop", { onPick(null) })
        }
    }
}

/** The extra line on the Start now sheet when starting also turns the schedule on or ends its pause. */
fun startNowAlsoArms(s: Schedule, now: Long): String? = when {
    !s.enabled -> "${s.name} is off. Starting it also turns it on."
    ScheduleEvaluator.isPaused(s, now) -> "${s.name} is paused. Starting it also ends the pause."
    else -> null
}

/** Hours + minutes stepper dialog (1 min – 24 h). */
@Composable
fun DurationDialog(title: String, initialMin: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit, maxMin: Int = 24 * 60) {
    var minutes by remember { mutableLongStateOf(initialMin.toLong()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .debugRoot()
            .testTag("duration_dialog"),
        title = { Text(title) },
        text = {
            Stepper(
                value = minutes,
                onChange = { minutes = it },
                range = 1L..maxMin.toLong(),
                step = { v, up ->
                    val s = if (v < 60 || (v == 60L && !up)) 5 else 15
                    if (up) (v / s + 1) * s else ((v - 1) / s) * s
                },
                format = { Format.duration(it * 60_000) },
                tag = "duration",
                label = "duration",
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(minutes.toInt()) }, modifier = Modifier.testTag("duration_ok")) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Date picker, then time picker; returns the policy instant in [zone]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimeDialog(now: Long, zone: ZoneId, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    var date by remember { mutableStateOf<LocalDate?>(null) }
    if (date == null) {
        val state = rememberDatePickerState(initialSelectedDateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.debugRoot(),
            confirmButton = {
                TextButton(onClick = {
                    date = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: today
                }, modifier = Modifier.testTag("date_ok")) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) { DatePicker(state) }
    } else {
        val t = Instant.ofEpochMilli(now).atZone(zone).toLocalTime().plusHours(1)
        val state = rememberTimePickerState(initialHour = t.hour, initialMinute = 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier.debugRoot(),
            title = {
                Text("Until what time on ${date!!.dayOfMonth} ${date!!.month.name.lowercase().replaceFirstChar { it.uppercase() }}?")
            },
            // Scrollable so the clock dial isn't clipped at large font sizes (200 %).
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimePicker(state) } },
            confirmButton = {
                TextButton(onClick = {
                    val local = LocalDateTime.of(date!!, LocalTime.of(state.hour, state.minute))
                    onPick(local.atZone(zone).toInstant().toEpochMilli())
                }, modifier = Modifier.testTag("time_ok")) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}
