package dev.brainrotprevention.platform

import dev.brainrotprevention.platform.log.Brp

/**
 * Runs a main-thread callback (accessibility event, broadcast, posted runnable) so that a RuntimeException is logged
 * instead of killing the process. The process hosts the accessibility service: a crash stops all blocking, and after
 * repeated crashes Android leaves the service disabled-as-crashed until the user toggles it.
 */
internal inline fun guarded(where: String, block: () -> Unit) {
    try {
        block()
    } catch (e: RuntimeException) {
        Brp.event("error", "where" to where, "type" to e.javaClass.name, "msg" to e.message?.take(200))
    }
}
