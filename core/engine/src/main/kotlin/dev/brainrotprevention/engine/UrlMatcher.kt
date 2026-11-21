package dev.brainrotprevention.engine

import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind

/** §7.4. No wildcards, no regex. */
object UrlMatcher {
    fun matches(rule: WebRule, url: NormalizedUrl): Boolean = when (rule.kind) {
        // The host, and the original host behind a translate/AMP proxy URL (round 2, adversarial 4).
        WebRuleKind.DOMAIN -> url.hasHost && url.matchHosts.any { it == rule.value || it.endsWith("." + rule.value) }
        // Anywhere-in-URL is a superset of host matching (so widening a host keyword never unblocks, §9.4).
        WebRuleKind.KEYWORD -> hostKeyword(rule.value, url) ||
            (rule.anywhereInUrl && (url.urlDecoded.contains(rule.value) || url.text.contains(rule.value)))
    }

    private fun hostKeyword(value: String, url: NormalizedUrl): Boolean =
        url.hasHost && (url.hostUnicode.contains(value) || url.matchHosts.any { it.contains(value) })

    fun matchesAny(rules: Collection<WebRule>, url: NormalizedUrl): Boolean = rules.any { matches(it, url) }
}
