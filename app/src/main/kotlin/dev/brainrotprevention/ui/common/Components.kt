package dev.brainrotprevention.ui.common

import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.brainrotprevention.BuildConfig
import dev.brainrotprevention.ui.LocalUiDeps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Exposes test tags as resource ids (TAGS.md) on debug/qa builds. Apply on every composition root: the activity
 * content and each dialog, popup menu and bottom sheet (they are separate windows).
 */
fun Modifier.debugRoot(): Modifier = if (BuildConfig.BUILD_TYPE != "release") semantics { testTagsAsResourceId = true } else this

/** A card section with a heading. */
@Composable
fun Section(title: String, modifier: Modifier = Modifier, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** A full-row switch with a 48 dp target and one TalkBack node. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .testTag(tag)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** − value + stepper; [format] renders the value. */
@Composable
fun Stepper(
    value: Long,
    onChange: (Long) -> Unit,
    range: LongRange,
    step: (Long, Boolean) -> Long,
    format: (Long) -> String,
    tag: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier.testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = { onChange(step(value, false).coerceIn(range)) },
            enabled = value > range.first,
            modifier = Modifier.testTag("${tag}_minus"),
        ) { Icon(Icons.Default.Remove, contentDescription = "Less $label") }
        Text(
            format(value),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .widthIn(min = 96.dp)
                .testTag("${tag}_value")
                .semantics { stateDescription = "$label ${format(value)}" },
        )
        FilledTonalIconButton(
            onClick = { onChange(step(value, true).coerceIn(range)) },
            enabled = value < range.last,
            modifier = Modifier.testTag("${tag}_plus"),
        ) { Icon(Icons.Default.Add, contentDescription = "More $label") }
    }
}

/** A small rounded label. */
@Composable
fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

/** Dialog for a refused weakening edit (§9.4): tag `locked_by_strict`. */
@Composable
fun LockedDialog(reason: String, condition: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .debugRoot()
            .testTag("locked_by_strict"),
        icon = { Icon(Icons.Default.Lock, contentDescription = null) },
        title = { Text("Locked by Strict Mode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (reason.isNotEmpty()) Text(reason)
                Text(condition, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("locked_ok")) { Text("OK") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    tag: String,
    dismiss: String = "Cancel",
    onDismissButton: () -> Unit = onDismiss,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .debugRoot()
            .testTag(tag),
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag("${tag}_confirm")) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismissButton, modifier = Modifier.testTag("${tag}_dismiss")) { Text(dismiss) } },
    )
}

/** Small in-memory LRU of app icons; a missing icon is cached too, so it isn't looked up again on every scroll. */
object AppIconCache {
    private object None

    private val cache = LruCache<String, Any>(256)

    fun peek(pkg: String): ImageBitmap? = cache.get(pkg) as? ImageBitmap

    suspend fun load(pkg: String, icon: (String) -> Drawable?): ImageBitmap? {
        cache.get(pkg)?.let { return it as? ImageBitmap }
        val b = withContext(Dispatchers.IO) { runCatching { icon(pkg)?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
        cache.put(pkg, b ?: None)
        return b
    }
}

/** The installed app's icon (from the platform), or a letter avatar. */
@Composable
fun AppIcon(pkg: String, label: String, size: Dp = 40.dp) {
    val apps = LocalUiDeps.current.platform.apps
    // PackageManager + bitmap work off the main thread (a picker can have hundreds of rows), cached by package.
    val bitmap = produceState(AppIconCache.peek(pkg), pkg) {
        if (value == null) dev.brainrotprevention.ui.uiGuarded { value = AppIconCache.load(pkg) { apps.icon(it) } }
    }.value
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(
            Modifier
                .size(size)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Gives a row a TalkBack description of its own. */
fun Modifier.describe(text: String): Modifier = semantics { contentDescription = text }
