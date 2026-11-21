package dev.brainrotprevention.platform.ui

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** What the block screen shows (§10.3). */
data class BlockModel(
    /** App name or site host. */
    val title: String,
    val icon: ImageBitmap? = null,
    /** "Blocked by Focus". */
    val blockedBy: String,
    /** Extra reasons as chips. */
    val chips: List<String> = emptyList(),
    /** "until 17:40", "resets in 2 h 10 m", … */
    val untilLine: String? = null,
    val showPause: Boolean = false,
    val showAllowBrowser: Boolean = false,
    /** Close can't dismiss yet ("Exit the app first"). */
    val stuck: Boolean = false,
    val message: String? = null,
    val motivation: String? = null,
    /** "3 notifications held today". */
    val notificationsLine: String? = null,
    /** Changes whenever the overlay content is re-shown. */
    val token: Long = 0,
    /** The blocked package; the 10 s Pause timer restarts only when the target changes, not on every re-show. */
    val target: String = "",
)

interface BlockActions {
    fun close()

    fun pause15()

    fun allowBrowser()
}

private const val PAUSE_DELAY_MS = 10_000L

@Composable
fun BlockTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** The full-bleed block screen (§10.3, §12). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BlockScreen(model: BlockModel, actions: BlockActions, modifier: Modifier = Modifier) {
    var showPauseSheet by remember(model.target) { mutableStateOf(false) }
    var progress by remember(model.target) { mutableFloatStateOf(0f) }
    LaunchedEffect(model.target, model.showPause) {
        if (!model.showPause) return@LaunchedEffect
        val steps = 50
        for (i in 1..steps) {
            delay(PAUSE_DELAY_MS / steps)
            progress = i / steps.toFloat()
        }
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    if (model.icon != null) {
                        Image(model.icon, contentDescription = null, modifier = Modifier.size(56.dp))
                    } else {
                        Text(model.title.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium)
                    }
                }
                Text(model.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Blocked", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
                Text(model.blockedBy, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                if (model.chips.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        model.chips.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                    }
                }
                model.untilLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                model.notificationsLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                model.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
                model.motivation?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = actions::close,
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("block_close").semantics { contentDescription = "Close" },
                ) { Text("Close") }
                if (model.showPause) {
                    val ready = progress >= 1f
                    TextButton(
                        onClick = { showPauseSheet = true },
                        enabled = ready,
                        modifier = Modifier.height(48.dp).testTag("block_pause"),
                    ) {
                        if (!ready) {
                            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text("Pause…")
                    }
                    if (showPauseSheet) {
                        TextButton(onClick = actions::pause15, modifier = Modifier.height(48.dp).testTag("pause_15m")) {
                            Text("Pause for 15 min")
                        }
                    }
                }
                if (model.showAllowBrowser) {
                    TextButton(onClick = actions::allowBrowser, modifier = Modifier.height(48.dp).testTag("block_allow_browser")) {
                        Text("Not a browser? Allow")
                    }
                }
            }
        }
    }
}
