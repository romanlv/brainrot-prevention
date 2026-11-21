package dev.brainrotprevention.ui.strict

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.brainrotprevention.ui.common.OnScreenWait
import dev.brainrotprevention.ui.common.debugRoot
import dev.brainrotprevention.ui.format.Format
import kotlinx.coroutines.delay

/** §9.7: explain + "Is this a real emergency?" → retype the pledge → 60 s on-screen countdown → INACTIVE. */
@Composable
fun EmergencyFlow(vm: StrictViewModel, step: EmergencyStep, st: StrictUi) {
    val availableAt = st.emergencyAvailableAt
    val modifier = Modifier
        .debugRoot()
        .testTag("emergency_dialog")
    when {
        availableAt != null -> AlertDialog(
            onDismissRequest = vm::closeEmergency,
            modifier = modifier,
            title = { Text("Emergency unblock was used recently") },
            text = {
                Text(
                    "It can be used once every 30 days. It will be available again on ${Format.dateLong(availableAt, st.zone)}.",
                    modifier = Modifier.testTag("emergency_unavailable"),
                )
            },
            confirmButton = { TextButton(onClick = vm::closeEmergency) { Text("Close") } },
        )
        step == EmergencyStep.EXPLAIN -> AlertDialog(
            onDismissRequest = vm::closeEmergency,
            modifier = modifier,
            title = { Text("Emergency unblock") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This turns Strict Mode off right away, whatever unlock method you chose. Your schedules stay as they are.")
                    Text("You can use it once every 30 days. You'll retype a short pledge and wait one minute.")
                    Text("Is this a real emergency?", style = MaterialTheme.typography.titleMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = vm::emergencyConfirmed, modifier = Modifier.testTag("emergency_yes")) { Text("Yes, it is") }
            },
            dismissButton = {
                TextButton(onClick = vm::closeEmergency, modifier = Modifier.testTag("emergency_no")) { Text("No, keep Strict Mode") }
            },
        )
        step == EmergencyStep.PLEDGE -> {
            var text by rememberSaveable { mutableStateOf("") }
            var wrong by remember { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = vm::closeEmergency,
                modifier = modifier,
                properties = DialogProperties(dismissOnClickOutside = false),
                title = { Text("Type this exactly") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(vm.pledge, fontStyle = FontStyle.Italic, modifier = Modifier.testTag("emergency_pledge_text"))
                        OutlinedTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                wrong = false
                            },
                            minLines = 3,
                            isError = wrong,
                            supportingText = { if (wrong) Text("That doesn't match yet.") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("emergency_pledge"),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        wrong = !vm.pledgeTyped(text)
                    }, modifier = Modifier.testTag("emergency_pledge_ok")) { Text("Continue") }
                },
                dismissButton = { TextButton(onClick = vm::closeEmergency) { Text("Cancel") } },
            )
        }
        else -> {
            val wait by vm.wait.collectAsStateWithLifecycle()
            val w = wait
            var remaining by remember(w) { mutableLongStateOf(w?.let(vm::remaining) ?: 0) }
            OnScreenWait(onStop = vm::cancelWait)
            LaunchedEffect(w) {
                if (w == null) return@LaunchedEffect
                while (true) {
                    remaining = vm.remaining(w)
                    if (remaining <= 0) {
                        vm.finishWait()
                        break
                    }
                    delay(250)
                }
            }
            AlertDialog(
                onDismissRequest = {},
                modifier = modifier,
                properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
                title = { Text("Ending Strict Mode") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Keep this screen open for one minute.")
                        Text(
                            Format.countdown(remaining),
                            style = MaterialTheme.typography.displaySmall,
                            modifier = Modifier
                                .testTag("emergency_countdown")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                        LinearProgressIndicator(
                            progress = { 1f - remaining.toFloat() / (w?.durationMs ?: 1L) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = vm::closeEmergency, modifier = Modifier.testTag("emergency_cancel")) { Text("Cancel") }
                },
            )
        }
    }
}
