package dev.brainrotprevention

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.brainrotprevention.platform.platform
import dev.brainrotprevention.ui.UiDeps
import dev.brainrotprevention.ui.nav.BrpUi
import dev.brainrotprevention.ui.platform.PlatformPorts

/** The only activity: Home / Strict / Settings, the editor and onboarding (§12). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val p = platform
        val deps = UiDeps(p.data, PlatformPorts.of(p))
        setContent { BrpUi(deps) }
    }

    override fun onResume() {
        super.onResume()
        // §11.6: the health check runs on each app open.
        runCatching { platform.health.refresh() }
    }
}
