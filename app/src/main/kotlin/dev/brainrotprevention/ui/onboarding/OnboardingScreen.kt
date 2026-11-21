package dev.brainrotprevention.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.brainrotprevention.ui.UiDeps
import dev.brainrotprevention.ui.common.GrantRow
import dev.brainrotprevention.ui.common.Notice
import dev.brainrotprevention.ui.common.NoticeHost
import dev.brainrotprevention.ui.common.SETTINGS_LOCKED_TEXT
import dev.brainrotprevention.ui.common.findActivity
import dev.brainrotprevention.ui.editor.Template
import dev.brainrotprevention.ui.editor.TemplateList
import dev.brainrotprevention.ui.platform.FixResult
import dev.brainrotprevention.ui.platform.UiGrant
import dev.brainrotprevention.ui.platform.UiGrantStatus
import dev.brainrotprevention.ui.platform.UiHealth

private data class Intro(val icon: ImageVector, val title: String, val text: String)

private val INTROS = listOf(
    Intro(
        Icons.Default.SelfImprovement,
        "Take your attention back",
        "Block the apps and sites that pull you in, at the times they do the most damage."
    ),
    Intro(
        Icons.Default.Schedule,
        "Schedules and budgets",
        "Block during work or before bed, or allow 30 minutes a day and then block. Pause when you really need to."
    ),
    Intro(
        Icons.Default.Lock,
        "Hard to undo, on purpose",
        "Strict Mode locks your rules until a timer ends or another condition you pick. Everything stays on this phone."
    ),
)

/**
 * §12 onboarding: 3 intro screens → permission checklist (rows turn green on resume; restricted-settings path, §11.3)
 * → first schedule from a template. [onFinish] gets the chosen template (null = start from scratch, or skipped).
 */
@Composable
fun OnboardingScreen(deps: UiDeps, onFinish: (template: Template?, openEditor: Boolean) -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var health by androidx.compose.runtime.remember {
        mutableStateOf(runCatching { deps.platform.health.check() }.getOrDefault(UiHealth()))
    }
    var triedAccessibility by rememberSaveable { mutableStateOf(false) }
    var notice by androidx.compose.runtime.remember { mutableStateOf<Notice?>(null) }
    val context = LocalContext.current
    fun refresh() {
        health = runCatching { deps.platform.health.check() }.getOrDefault(UiHealth())
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .testTag("onboarding"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                step < INTROS.size -> {
                    val intro = INTROS[step]
                    Spacer(Modifier.size(32.dp))
                    Box(
                        Modifier
                            .size(120.dp)
                            .align(Alignment.CenterHorizontally),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(120.dp)) {
                        }
                        Icon(
                            intro.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                    Text(
                        intro.title,
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { heading() }
                            .testTag("onboarding_intro_$step"),
                    )
                    Text(
                        intro.text,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${step + 1} of ${INTROS.size}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Button(
                        onClick = { step++ },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("onboarding_next")
                    ) { Text("Next") }
                    TextButton(
                        onClick = { step = INTROS.size },
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .testTag("onboarding_skip_intro")
                    ) { Text("Skip intro") }
                }
                step == INTROS.size -> {
                    Text(
                        "Permissions",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier
                            .semantics { heading() }
                            .testTag("onboarding_permissions")
                    )
                    Text(
                        "Blocking needs a few grants. Each one opens a system screen; come back here when you're done.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (health.advancedProtection) {
                        Text(
                            "Advanced Protection prevents blocking apps from using accessibility; Brainrot Prevention can't work while it's on.",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("onboarding_advanced_protection"),
                        )
                    }
                    health.items.filter(::shownInOnboarding).forEach { item ->
                        GrantRow(
                            item,
                            buttonLabel = "Grant",
                            onLocked = { notice = Notice.Locked(null, SETTINGS_LOCKED_TEXT) },
                            onChanged = ::refresh,
                            tagPrefix = "onboarding",
                            onOpened = {
                                if (it == UiGrant.ACCESSIBILITY ||
                                    it == UiGrant.NOTIFICATION_LISTENER
                                ) {
                                    triedAccessibility = true
                                }
                            },
                        )
                    }
                    val a11y = health[UiGrant.ACCESSIBILITY]
                    if (triedAccessibility && a11y?.granted == false && health.restrictedInstall) {
                        RestrictedSettingsCard(onOpen = {
                            context.findActivity()?.let {
                                if (deps.platform.health.openAppDetails(it) ==
                                    FixResult.LOCKED_BY_STRICT
                                ) {
                                    notice = Notice.Locked(null, SETTINGS_LOCKED_TEXT)
                                }
                            }
                        })
                    }
                    Button(
                        onClick = { step++ },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("onboarding_continue")
                    ) { Text(if (health.ok) "Continue" else "Continue anyway") }
                    if (!health.ok) {
                        Text(
                            "Without the red items, nothing can be blocked. You can grant them later in Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    Text(
                        "Your first schedule",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier
                            .semantics { heading() }
                            .testTag("onboarding_templates")
                    )
                    Text(
                        "Pick a starting point. Next you'll choose which apps and sites it blocks.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    TemplateList { t -> onFinish(t, true) }
                    OutlinedButton(
                        onClick = { onFinish(null, false) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .testTag("onboarding_later")
                    ) { Text("Not now") }
                }
            }
        }
    }
    NoticeHost(notice) { notice = null }
}

/**
 * The onboarding checklist asks only for what blocking needs now (§11.3): accessibility, usage access, the
 * notification listener and notifications, plus exact alarms if they're missing. Location is "asked only when such a
 * trigger is added", so its rows appear only when a Wi-Fi/place trigger already makes them required; battery
 * optimisation and device admin stay in Settings / Strict Mode.
 */
internal fun shownInOnboarding(item: UiGrantStatus): Boolean = when (item.grant) {
    UiGrant.ACCESSIBILITY, UiGrant.USAGE_ACCESS, UiGrant.NOTIFICATION_LISTENER, UiGrant.POST_NOTIFICATIONS -> true
    UiGrant.EXACT_ALARMS -> !item.granted
    UiGrant.LOCATION_FINE, UiGrant.LOCATION_BACKGROUND -> item.required
    UiGrant.BATTERY_OPTIMIZATION, UiGrant.DEVICE_ADMIN -> false
}

@Composable
private fun RestrictedSettingsCard(onOpen: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .testTag("onboarding_restricted"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Android blocked this setting", style = MaterialTheme.typography.titleMedium)
            Text(
                "Because this app wasn't installed from an app store, Android hides some settings at first. To allow them:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "1. Open App info below.\n2. Tap ⋮ (top right) → Allow restricted settings, and confirm.\n3. Come back and tap Grant again."
            )
            Text(
                "The menu item appears only after a blocked attempt; on some phones it is in a different place.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                Button(
                    onClick = onOpen,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("onboarding_app_info")
                ) { Text("Open App info") }
            }
        }
    }
}
