package dev.brainrotprevention.debug

import dev.brainrotprevention.debug.DebugCommands.Result
import dev.brainrotprevention.platform.log.Brp

/** Commands that exist from M0. Later milestones add theirs here (or call DebugCommands.register). */
internal object BuiltinDebugCommands {
    private val STEP_ID = Regex("[A-Za-z0-9_-]+")

    fun registerAll() {
        // mark --es id X : a step boundary in the event log (SPEC §14.3, §14.9)
        DebugCommands.register("mark") { intent ->
            val id = intent.getStringExtra("id")
            if (id == null || !STEP_ID.matches(id)) {
                Result.fail("mark needs --es id matching [A-Za-z0-9_-]+")
            } else {
                Brp.event("mark", "id" to id)
                Result.ok("mark $id")
            }
        }
    }
}
