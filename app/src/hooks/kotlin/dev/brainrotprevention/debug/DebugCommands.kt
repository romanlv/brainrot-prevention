package dev.brainrotprevention.debug

import android.content.Context
import android.content.Intent
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry of debug-control commands. Add a command with
 *
 *   DebugCommands.register("evaluate") { intent -> …; DebugCommands.Result.ok("…") }
 *
 * The handler runs on a background dispatcher with the application [Context] as receiver, and
 * must finish within ~9 s. Return [Result.fail] (result=0) when a precondition fails.
 * Built-in commands live in [BuiltinDebugCommands].
 */
object DebugCommands {
    data class Result(val ok: Boolean, val message: String) {
        companion object {
            fun ok(message: String = "ok") = Result(true, message)

            fun fail(message: String) = Result(false, message)
        }
    }

    private val handlers = ConcurrentHashMap<String, suspend Context.(Intent) -> Result>()

    @Volatile
    private var builtinsLoaded = false

    fun register(name: String, handler: suspend Context.(Intent) -> Result) {
        handlers[name] = handler
    }

    fun names(): Set<String> = handlers.keys.toSortedSet()

    suspend fun run(context: Context, name: String?, intent: Intent): Result {
        ensureBuiltins()
        if (name.isNullOrEmpty()) return Result.fail("missing --es cmd <name>; known: ${names().joinToString(",")}")
        val handler = handlers[name] ?: return Result.fail("unknown command: $name")
        return context.handler(intent)
    }

    private fun ensureBuiltins() {
        if (builtinsLoaded) return
        synchronized(this) {
            if (!builtinsLoaded) {
                BuiltinDebugCommands.registerAll()
                builtinsLoaded = true
            }
        }
    }
}
