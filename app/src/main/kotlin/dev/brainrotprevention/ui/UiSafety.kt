package dev.brainrotprevention.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.brainrotprevention.platform.log.Brp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
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

    /** Logs [t] and shows the toast. */
    fun report(t: Throwable) {
        Brp.event("ui_error", "type" to t.javaClass.name, "msg" to t.message?.take(200))
        val ctx = app ?: return
        main.post {
            runCatching { Toast.makeText(ctx, "Something went wrong. Blocking keeps running.", Toast.LENGTH_SHORT).show() }
        }
    }

    val handler = CoroutineExceptionHandler { _, t -> report(t) }
}

/**
 * Runs [block], reporting any failure through [UiGuard] instead of letting it propagate. Use it inside Compose
 * scopes (`rememberCoroutineScope`, `LaunchedEffect`, `produceState`): a failed child there would cancel the
 * composition's effect job and freeze the screen, which a [CoroutineExceptionHandler] can't prevent.
 */
suspend fun uiGuarded(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        UiGuard.report(t)
    }
}

/** `launch` whose failures are reported by [UiGuard] and never cancel the scope (see [uiGuarded]). */
fun CoroutineScope.launchGuarded(block: suspend CoroutineScope.() -> Unit): Job = launch { uiGuarded { block() } }

/**
 * Reports an upstream failure and re-subscribes after a short back-off (1 s, 2 s … up to 10 s), so a `stateIn`
 * built on it recovers instead of keeping its last value for the rest of the ViewModel's life.
 */
fun <T> Flow<T>.retryReporting(): Flow<T> = retryWhen { cause, attempt ->
    if (cause is CancellationException) return@retryWhen false
    UiGuard.report(cause)
    delay(1_000L * minOf(attempt + 1, 10))
    true
}

/** [viewModelScope] with [UiGuard.handler]; use it instead of `viewModelScope` in every ViewModel. */
val ViewModel.safeScope: CoroutineScope get() = viewModelScope + UiGuard.handler
