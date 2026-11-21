package dev.brainrotprevention.engine

import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind

/** §7.4. No wildcards, no regex. */
object UrlMatcher {
    fun matches(rule: WebRule, url: NormalizedUrl): Boolean = when (rule.kind) {
        WebRuleKind.DOMAIN -> url.hasHost && (url.hostAscii == rule.value || url.hostAscii.endsWith("." + rule.value))
        WebRuleKind.KEYWORD -> if (rule.anywhereInUrl) {
            url.urlDecoded.contains(rule.value) || url.text.contains(rule.value)
        } else {
            url.hasHost && (url.hostUnicode.contains(rule.value) || url.hostAscii.contains(rule.value))
        }
    }

    fun matchesAny(rules: Collection<WebRule>, url: NormalizedUrl): Boolean = rules.any { matches(it, url) }
}
