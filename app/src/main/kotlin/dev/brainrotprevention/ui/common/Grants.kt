package dev.brainrotprevention.ui.common

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import dev.brainrotprevention.ui.LocalUiDeps
import dev.brainrotprevention.ui.platform.FixResult
import dev.brainrotprevention.ui.platform.UiGrant
import dev.brainrotprevention.ui.platform.UiGrantStatus

/** Plain-language title and reason for each grant (§11.3, §12 onboarding). */
object GrantText {
    fun title(g: UiGrant): String = when (g) {
        UiGrant.ACCESSIBILITY -> "Accessibility service"
        UiGrant.USAGE_ACCESS -> "Usage access"
        UiGrant.NOTIFICATION_LISTENER -> "Notification access"
        UiGrant.POST_NOTIFICATIONS -> "Notifications"
        UiGrant.EXACT_ALARMS -> "Alarms and reminders"
        UiGrant.LOCATION_FINE -> "Location"
        UiGrant.LOCATION_BACKGROUND -> "Location all the time"
        UiGrant.BATTERY_OPTIMIZATION -> "Run without battery limits"
        UiGrant.DEVICE_ADMIN -> "Device admin"
    }

    fun reason(g: UiGrant): String = when (g) {
        UiGrant.ACCESSIBILITY -> "Sees which app or site is on screen and sends blocked ones away. Nothing leaves your phone."
        UiGrant.USAGE_ACCESS -> "Knows which app is open and counts opens for budgets."
        UiGrant.NOTIFICATION_LISTENER -> "Holds back notifications from blocked apps until the block ends. Optional."
        UiGrant.POST_NOTIFICATIONS -> "Shows what's blocking, budget warnings and problems."
        UiGrant.EXACT_ALARMS -> "Starts and ends schedules exactly on time."
        UiGrant.LOCATION_FINE -> "Needed to read the Wi-Fi name and for place-based schedules."
        UiGrant.LOCATION_BACKGROUND -> "Lets Wi-Fi and place schedules work while the app is closed."
        UiGrant.BATTERY_OPTIMIZATION -> "Stops some phones from closing blocking in the background. Optional."
        UiGrant.DEVICE_ADMIN -> "Only used by Strict Mode to block uninstalling."
    }
}

/**
 * One grant row: green when granted, red when a required grant is missing, with a Fix/Grant button. Settings deep
 * links go through the platform so they are refused while Strict Mode blocks Settings (§9.3).
 */
@Composable
fun GrantRow(
    item: UiGrantStatus,
    buttonLabel: String,
    onLocked: () -> Unit,
    onChanged: () -> Unit,
    tagPrefix: String,
    onOpened: (UiGrant) -> Unit = {},
) {
    val deps = LocalUiDeps.current
    val context = LocalContext.current
    val perm = deps.platform.health.runtimePermission(item.grant)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    val color = when {
        item.granted -> Color(0xFF2E7D32)
        item.required -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val state = when {
        item.granted -> "granted"
        item.required -> "missing, required"
        else -> "not granted, optional"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(vertical = 4.dp)
            .testTag("${tagPrefix}_${item.grant.name.lowercase()}")
            .semantics(mergeDescendants = false) { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                item.granted -> Icons.Default.CheckCircle
                item.required -> Icons.Default.Error
                else -> Icons.Outlined.Circle
            },
            contentDescription = state,
            tint = color,
            modifier = Modifier.testTag("${tagPrefix}_${item.grant.name.lowercase()}_${if (item.granted) "ok" else "missing"}"),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(GrantText.title(item.grant), style = MaterialTheme.typography.bodyLarge)
            Text(
                GrantText.reason(item.grant),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color) }
        }
        if (!item.granted) {
            Spacer(Modifier.size(8.dp))
            FilledTonalButton(
                onClick = {
                    val activity = context.findActivity() ?: return@FilledTonalButton
                    if (perm != null && !askedBefore(activity, perm)) {
                        markAsked(activity, perm)
                        launcher.launch(perm)
                    } else if (perm != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)) {
                        launcher.launch(perm)
                    } else {
                        when (deps.platform.health.open(activity, item.grant)) {
                            FixResult.LOCKED_BY_STRICT -> onLocked()
                            FixResult.OPENED -> onOpened(item.grant)
                            FixResult.UNAVAILABLE -> Unit
                        }
                    }
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("${tagPrefix}_fix_${item.grant.name.lowercase()}"),
            ) { Text(buttonLabel) }
        }
    }
}

// A denied-twice runtime permission can only be granted from App info, so the second tap opens the fix screen.
private fun askedBefore(a: Activity, perm: String) = a.getSharedPreferences("ui", 0).getBoolean("asked_$perm", false)

private fun markAsked(a: Activity, perm: String) = a.getSharedPreferences("ui", 0).edit { putBoolean("asked_$perm", true) }

/** Shown when a Settings deep link is refused because Strict Mode blocks Settings. */
val SETTINGS_LOCKED_TEXT = "Settings are blocked while Strict Mode is active. You can fix this when Strict Mode ends."
