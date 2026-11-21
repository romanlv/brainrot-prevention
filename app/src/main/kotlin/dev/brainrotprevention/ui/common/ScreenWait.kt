package dev.brainrotprevention.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * For on-screen strict waits (§9.5 COOLDOWN, standby turn-off, §9.7 countdown): keeps the screen on with
 * `FLAG_KEEP_SCREEN_ON` while composed and calls [onStop] on `ON_STOP` (the wait is cancelled), except for the
 * `ON_STOP` of a configuration change such as rotation.
 */
@Composable
fun OnScreenWait(onStop: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val stop by rememberUpdatedState(onStop)
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(owner, context) {
        val activity = context.findActivity()
        // A rotation (or any configuration change) also stops the activity, but the user never left the screen,
        // and the Wait itself lives in the ViewModel, so the wait carries on in the recreated activity.
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) stop()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}
