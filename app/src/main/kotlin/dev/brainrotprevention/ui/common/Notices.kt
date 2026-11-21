package dev.brainrotprevention.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.engine.Refusal
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.StrictModeRow
import dev.brainrotprevention.ui.LocalUiDeps
import dev.brainrotprevention.ui.format.Format

/** A one-shot message a screen shows in a dialog. */
sealed interface Notice {
    /** Refused by Strict Mode; [refusal] null = generic (e.g. a Settings deep link while the guard is on). */
    data class Locked(val refusal: Refusal?, val text: String? = null) : Notice

    data class Message(val title: String, val text: String) : Notice
}

/** Null when [this] is Ok. */
fun WriteResult<*>.notice(): Notice? = when (this) {
    is WriteResult.Ok -> null
    is WriteResult.Locked -> Notice.Locked(refusal)
    is WriteResult.Invalid -> Notice.Message("Can't save this", error.message.replaceFirstChar { it.uppercase() })
    is WriteResult.NotFound -> Notice.Message("Not found", "It was deleted in the meantime.")
}

/** Shows [notice]; a Locked notice gets the "Locked by Strict Mode" dialog with the unlock condition. */
@Composable
fun NoticeHost(notice: Notice?, onDismiss: () -> Unit) {
    when (notice) {
        null -> Unit
        is Notice.Locked -> {
            val deps = LocalUiDeps.current
            val row by deps.graph.strict.strict.collectAsStateWithLifecycle(StrictModeRow())
            val status by deps.status.collectAsStateWithLifecycle(null)
            val c = deps.clock.read()
            val condition = Format.unlockCondition(row, status?.strict ?: StrictDisplayState.ACTIVE, c.now, c.elapsed, c.zone)
            LockedDialog(
                reason = notice.text ?: notice.refusal?.let(Format::refusal).orEmpty(),
                condition = condition,
                onDismiss = onDismiss
            )
        }
        is Notice.Message -> AlertDialog(
            onDismissRequest = onDismiss,
            modifier = Modifier
                .debugRoot()
                .testTag("notice_dialog"),
            title = { Text(notice.title) },
            text = { Text(notice.text) },
            confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("notice_ok")) { Text("OK") } },
        )
    }
}
