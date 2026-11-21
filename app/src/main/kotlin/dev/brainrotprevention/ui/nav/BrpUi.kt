package dev.brainrotprevention.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.brainrotprevention.model.Prefs
import dev.brainrotprevention.ui.LocalUiDeps
import dev.brainrotprevention.ui.UiDeps
import dev.brainrotprevention.ui.common.debugRoot
import dev.brainrotprevention.ui.editor.EditorScreen
import dev.brainrotprevention.ui.editor.QuickBlockEditorScreen
import dev.brainrotprevention.ui.editor.Template
import dev.brainrotprevention.ui.home.HomeScreen
import dev.brainrotprevention.ui.onboarding.OnboardingScreen
import dev.brainrotprevention.ui.settings.SettingsScreen
import dev.brainrotprevention.ui.strict.StrictScreen
import dev.brainrotprevention.ui.theme.BrpTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

object Routes {
    const val HOME = "home"
    const val STRICT = "strict"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
    const val QUICK_BLOCK = "quickblock"
    const val EDITOR = "editor?id={id}&template={template}"

    fun editor(id: Long = 0, template: Template? = null, blank: Boolean = false) =
        "editor?id=$id&template=${template?.name ?: if (blank) SCRATCH else ""}"

    const val SCRATCH = "SCRATCH"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector, val tag: String)

private val TABS = listOf(
    Tab(Routes.HOME, "Home", Icons.Outlined.Home, Icons.Filled.Home, "nav_home"),
    Tab(Routes.STRICT, "Strict", Icons.Outlined.Lock, Icons.Filled.Lock, "nav_strict"),
    Tab(Routes.SETTINGS, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings, "nav_settings"),
)

/** The whole app UI. [startRoute] overrides the onboarding check (tests). */
@Composable
fun BrpUi(deps: UiDeps, startRoute: String? = null) {
    BrpTheme {
        CompositionLocalProvider(LocalUiDeps provides deps) {
            Surface(
                Modifier
                    .fillMaxSize()
                    .debugRoot(),
                color = MaterialTheme.colorScheme.background,
            ) {
                val doneFlow = remember(deps) { deps.graph.settings.prefs.map<Prefs, Boolean?> { it.onboardingDone } }
                val onboardingDone by doneFlow.collectAsStateWithLifecycle(initialValue = null)
                // Decided once: finishing onboarding must not swap the graph's start under the running NavController.
                var start by rememberSaveable { mutableStateOf(startRoute) }
                LaunchedEffect(onboardingDone) {
                    if (start == null && onboardingDone != null) start = if (onboardingDone == true) Routes.HOME else Routes.ONBOARDING
                }
                if (start == null) {
                    Box(Modifier.fillMaxSize())
                } else {
                    AppNav(deps, rememberNavController(), start!!)
                }
            }
        }
    }
}

@Composable
private fun AppNav(deps: UiDeps, nav: NavHostController, start: String) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val scope = rememberCoroutineScope()
    Scaffold(
        bottomBar = {
            if (TABS.any { it.route == route }) {
                NavigationBar {
                    TABS.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    // HOME is the root after onboarding (the graph's start may be the finished onboarding).
                                    popUpTo(Routes.HOME) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (route == t.route) t.selectedIcon else t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                            modifier = Modifier.testTag(t.tag),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = start) {
            composable(Routes.HOME) {
                HomeScreen(
                    deps,
                    onEdit = { nav.navigate(Routes.editor(it)) },
                    onNew = { nav.navigate(Routes.editor()) },
                    onEditQuickBlock = { nav.navigate(Routes.QUICK_BLOCK) },
                    contentPadding = padding,
                )
            }
            composable(Routes.STRICT) { StrictScreen(deps, padding) }
            composable(Routes.SETTINGS) { SettingsScreen(deps, padding) }
            composable(Routes.QUICK_BLOCK) { QuickBlockEditorScreen(deps, onDone = { nav.popBackStack() }) }
            composable(
                Routes.EDITOR,
                arguments = listOf(
                    navArgument("id") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                    navArgument("template") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { e ->
                val id = e.arguments?.getLong("id") ?: 0L
                val arg = e.arguments?.getString("template").orEmpty()
                val template = Template.entries.firstOrNull { it.name == arg }
                EditorScreen(deps, id, template, startBlank = arg == Routes.SCRATCH, onDone = {
                    if (!nav.popBackStack()) nav.navigate(Routes.HOME)
                })
            }
            composable(Routes.ONBOARDING) {
                OnboardingScreen(deps) { template, openEditor ->
                    scope.launch {
                        deps.graph.settings.setOnboardingDone(true)
                        nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                        if (openEditor) nav.navigate(Routes.editor(0, template, blank = template == null))
                    }
                }
            }
        }
    }
}
