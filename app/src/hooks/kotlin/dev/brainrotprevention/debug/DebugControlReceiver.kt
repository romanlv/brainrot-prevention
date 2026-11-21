package dev.brainrotprevention.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.brainrotprevention.platform.log.Brp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Test hook (SPEC §14.3 item 2), debug and qa builds only. Exported, guarded by
 * `android.permission.DUMP`, so only the shell (adb) can call it:
 *
 *   am broadcast -f 0x20 -n <pkg>/dev.brainrotprevention.debug.DebugControlReceiver \
 *       -a dev.brainrotprevention.debug.CONTROL --es cmd <cmd> …
 *
 * Every command runs off the main thread under goAsync() and is acknowledged with
 * setResultCode(1) (done) or 0 (refused / unknown / failed) plus a short setResultData.
 */
class DebugControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        val app = context.applicationContext
        val name = intent.getStringExtra("cmd")
        scope.launch {
            val result = withTimeoutOrNull(TIMEOUT_MS) {
                try {
                    DebugCommands.run(app, name, intent)
                } catch (t: Throwable) {
                    Brp.event("error", "where" to "debug-control", "cmd" to name, "msg" to t.toString())
                    DebugCommands.Result.fail("error: ${t.javaClass.simpleName}: ${t.message}")
                }
            } ?: DebugCommands.Result.fail("timeout after $TIMEOUT_MS ms")
            pending.resultCode = if (result.ok) 1 else 0
            pending.resultData = result.message.take(MAX_DATA)
            pending.finish()
        }
    }

    companion object {
        const val ACTION = "dev.brainrotprevention.debug.CONTROL"
        private const val TIMEOUT_MS = 9_000L // below the 10 s foreground-broadcast ANR limit
        private const val MAX_DATA = 400
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
