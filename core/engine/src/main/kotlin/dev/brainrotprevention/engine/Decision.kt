package dev.brainrotprevention.engine

import dev.brainrotprevention.model.LimitKind
import dev.brainrotprevention.model.StrictMethod

/** Id used for the Quick Block virtual schedule in statuses and ledgers. */
const val QUICK_BLOCK_ID = -1L

/** How long a block lasts (§6.1). */
sealed interface Until {
    /** Ends at a wall-clock policy instant. */
    data class At(val instant: Long) : Until

    /** Lasts while Wi-Fi / location conditions hold; [label] e.g. "Wi-Fi Home" or a location label. */
    data class WhileConditions(val label: String) : Until

    /** Start now "until I stop it", or Quick Block without timer. */
    data object UntilStopped : Until

    /** Strict guard: ends with the strict unlock condition. [endsAt] is the TIMER end when known. */
    data class UnlockCondition(val method: StrictMethod, val endsAt: Long?) : Until

    /** Continuous for more than 8 days, or "Always on". Not a usable `At` for snoozing. */
    data object Ongoing : Until

    companion object {
        /**
         * Combines reason untils (§6.2): UntilStopped, then WhileConditions, win; then Ongoing,
         * then UnlockCondition; otherwise the latest At.
         */
        fun combine(untils: List<Until>): Until {
            untils.firstOrNull { it is UntilStopped }?.let { return it }
            untils.firstOrNull { it is WhileConditions }?.let { return it }
            untils.firstOrNull { it is Ongoing }?.let { return it }
            untils.firstOrNull { it is UnlockCondition }?.let { return it }
            return untils.filterIsInstance<At>().maxByOrNull { it.instant } ?: Ongoing
        }
    }
}

/** Why something is blocked (§6.1). */
sealed interface ReasonSource {
    data class Schedule(val scheduleId: Long) : ReasonSource

    data object QuickBlock : ReasonSource

    data object StrictSettings : ReasonSource

    data object StrictUninstall : ReasonSource

    /** [scheduleId] is null when the reason comes from Quick Block. */
    data class UnsupportedBrowser(val scheduleId: Long?) : ReasonSource
}

/** One reason chip: source, the limit that caused it (null if none), and its own until line. */
data class Reason(val source: ReasonSource, val limit: LimitKind?, val until: Until)

sealed interface Decision {
    data object Allow : Decision

    data class Block(val reasons: List<Reason>, val until: Until) : Decision

    val isBlock: Boolean get() = this is Block

    companion object {
        fun of(reasons: List<Reason>): Decision = if (reasons.isEmpty()) Allow else Block(reasons, Until.combine(reasons.map { it.until }))
    }
}
