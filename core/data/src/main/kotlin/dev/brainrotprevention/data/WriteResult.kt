package dev.brainrotprevention.data

import dev.brainrotprevention.engine.EditCheck
import dev.brainrotprevention.engine.Refusal
import dev.brainrotprevention.engine.ValidationError

/** Result of every repository mutation that can be refused. */
sealed interface WriteResult<out T> {
    data class Ok<T>(val value: T) : WriteResult<T>

    /** Refused by Strict Mode (EditGuard / WeakenChecker). UI: "Locked by Strict Mode" (tag `locked_by_strict`). */
    data class Locked(val refusal: Refusal) : WriteResult<Nothing>

    /** Failed the editor/import rules; [error] names the first offending row. */
    data class Invalid(val error: ValidationError) : WriteResult<Nothing>

    data class NotFound(val what: String) : WriteResult<Nothing>

    val isOk: Boolean get() = this is Ok

    /** Short text for logs and debug-command acknowledgements. */
    fun describe(): String = when (this) {
        is Ok -> "ok"
        is Locked -> "locked by strict mode: ${refusal.name}"
        is Invalid -> "invalid: $error"
        is NotFound -> "not found: $what"
    }

    companion object {
        internal fun ok(): WriteResult<Unit> = Ok(Unit)

        internal fun locked(check: EditCheck): Locked? = (check as? EditCheck.Refused)?.let { Locked(it.reason) }
    }
}
