package dev.brainrotprevention.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brainrotprevention.platform.log.Brp
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus

/**
 * The UI shares a process with the accessibility service, so an uncaught exception in any screen coroutine would
 * also stop blocking (and Android then marks the service as crashed until it is toggled or the app is updated).
 * Every UI coroutine runs under [UiGuard]: the error is logged and shown as a toast, and the process keeps running.
 */
object UiGuard {
    @Volatile private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())

    fun install(context: Context) {
        app = context.applicationContext
    }

    val handler = CoroutineExceptionHandler { _, t ->
        Brp.event("ui_error", "type" to t.javaClass.name, "msg" to t.message?.take(200))
        val ctx = app ?: return@CoroutineExceptionHandler
        main.post {
            runCatching { Toast.makeText(ctx, "Something went wrong. Blocking keeps running.", Toast.LENGTH_SHORT).show() }
        }
    }
}

/** [viewModelScope] with [UiGuard.handler]; use it instead of `viewModelScope` in every ViewModel. */
val ViewModel.safeScope: CoroutineScope get() = viewModelScope + UiGuard.handler
