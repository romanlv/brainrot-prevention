package dev.brainrotprevention.platform

import dev.brainrotprevention.engine.Decision
import dev.brainrotprevention.engine.Reason
import dev.brainrotprevention.engine.ReasonSource
import dev.brainrotprevention.engine.Until
import dev.brainrotprevention.platform.log.Brp

/** Field shapes of the `BRP` events (scripts/e2e/TAGS.md). */
internal object Events {
    fun sourceName(s: ReasonSource): String = when (s) {
        is ReasonSource.Schedule -> "SCHEDULE"
        ReasonSource.QuickBlock -> "QUICK_BLOCK"
        ReasonSource.StrictSettings -> "STRICT_SETTINGS"
        ReasonSource.StrictUninstall -> "STRICT_UNINSTALL"
        is ReasonSource.UnsupportedBrowser -> "UNSUPPORTED_BROWSER"
    }

    fun reason(r: Reason): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["source"] = sourceName(r.source)
        m["limit"] = r.limit?.name
        when (val s = r.source) {
            is ReasonSource.Schedule -> m["scheduleId"] = s.scheduleId
            is ReasonSource.UnsupportedBrowser -> m["scheduleId"] = s.scheduleId
            else -> {}
        }
        m["until"] = until(r.until)
        return m
    }

    fun until(u: Until?): String? = when (u) {
        null -> null
        is Until.At -> "At(${u.instant})"
        is Until.WhileConditions -> "WhileConditions(${u.label})"
        Until.UntilStopped -> "UntilStopped"
        is Until.UnlockCondition -> "UnlockCondition(${u.method}${u.endsAt?.let { ",$it" } ?: ""})"
        Until.Ongoing -> "Ongoing"
    }

    fun decision(target: String, url: String?, d: Decision) {
        val fields = ArrayList<Pair<String, Any?>>()
        fields += "target" to target
        if (url != null) fields += "url" to url
        when (d) {
            Decision.Allow -> {
                fields += "result" to "ALLOW"
                fields += "reasons" to emptyList<Any>()
            }
            is Decision.Block -> {
                fields += "result" to "BLOCK"
                fields += "reasons" to d.reasons.map { reason(it) }
                fields += "until" to until(d.until)
            }
        }
        Brp.event("decision", *fields.toTypedArray())
    }
}
