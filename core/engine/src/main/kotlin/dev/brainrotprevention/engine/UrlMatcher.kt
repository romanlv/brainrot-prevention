package dev.brainrotprevention.engine

import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind

/** §7.4. No wildcards, no regex. */
object UrlMatcher {
    fun matches(rule: WebRule, url: NormalizedUrl): Boolean = when (rule.kind) {
        WebRuleKind.DOMAIN -> url.hasHost && (url.hostAscii == rule.value || url.hostAscii.endsWith("." + rule.value))
        // Anywhere-in-URL is a superset of host matching (so widening a host keyword never unblocks, §9.4).
        WebRuleKind.KEYWORD -> hostKeyword(rule.value, url) ||
            (rule.anywhereInUrl && (url.urlDecoded.contains(rule.value) || url.text.contains(rule.value)))
    }

    private fun hostKeyword(value: String, url: NormalizedUrl): Boolean =
        url.hasHost && (url.hostUnicode.contains(value) || url.hostAscii.contains(value))

    fun matchesAny(rules: Collection<WebRule>, url: NormalizedUrl): Boolean = rules.any { matches(it, url) }
}
