package dev.brainrotprevention.engine

import dev.brainrotprevention.model.WebRule
import dev.brainrotprevention.model.WebRuleKind
import java.io.ByteArrayOutputStream
import java.net.IDN
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale

/** A normalised URL-bar text (§7.3). [host] is "" for non-http(s) schemes. */
data class NormalizedUrl(
    /** Text after steps 1–3 (trimmed, NFC, lower-case, view-source and Samsung refer unwrapped). */
    val text: String,
    val scheme: String,
    val hostAscii: String,
    val hostUnicode: String,
    val urlDecoded: String,
    /**
     * Hosts of the page this URL proxies (ASCII): Google Translate's `*.translate.goog`, the AMP cache
     * (`*.cdn.ampproject.org`, `cdn.ampproject.org/c/s/<host>/…`) and Google's AMP viewer (`www.google.com/amp/s/<host>/…`, any google TLD).
     * Host rules match these too (review round 2, adversarial 4).
     */
    val proxiedHosts: List<String> = emptyList(),
) {
    val hasHost: Boolean get() = hostAscii.isNotEmpty()

    /** [hostAscii] plus [proxiedHosts]. */
    val matchHosts: List<String> get() = if (proxiedHosts.isEmpty()) listOf(hostAscii) else listOf(hostAscii) + proxiedHosts

    /** Launch-counting item key (§5.4): hostAscii without a leading `www.`. */
    val itemKey: String get() = hostAscii.removePrefix("www.")
}

/** Result of normalising one user-typed rule (§7.3). */
sealed interface RuleInput {
    data class Ok(val rule: WebRule) : RuleInput

    /** A URL with a path: offer "Block the whole site" ([domain]) or "pages containing this text" ([keyword]). */
    data class Choice(val domain: WebRule, val keyword: WebRule) : RuleInput

    data class Invalid(val reason: String) : RuleInput
}

/** Bulk add result (§7.3). */
data class BulkAddResult(
    val added: List<WebRule>,
    val duplicates: Int,
    val invalid: Int,
    /** Entries not added because the list reached [ScheduleValidator.MAX_WEB_RULES] (not parsed). */
    val overLimit: Int = 0,
)

object UrlNormalizer {
    private const val SAMSUNG_REFER = "r.internet.apps.samsung.com/refer"
    private val schemeRegex = Regex("^([a-z][a-z0-9+.-]*):")
    private val domainChars = Regex("^[a-z0-9.-]+$")
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val ipv6 = Regex("^[0-9a-f:.]+$")
    private val whitespace = Regex("\\s")

    /** Steps 1–3: trim, NFC, lower-case, strip `view-source:`, unwrap Samsung refer links. */
    fun clean(text: String): String {
        var s = Normalizer.normalize(text.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
        s = s.removePrefix("view-source:")
        val refer = s.removePrefix("https://").removePrefix("http://")
        if (refer.startsWith(SAMSUNG_REFER)) {
            val q = refer.substringAfter('?', "")
            val param = q.split('&').firstOrNull { it.startsWith("url=") }
            if (param != null) s = lowerNfc(percentDecode(param.removePrefix("url=")).trim()).removePrefix("view-source:")
        }
        return s
    }

    /** Splits a scheme from [s], or returns null when there is none (`host:port` is not a scheme). */
    fun schemeOf(s: String): String? {
        val m = schemeRegex.find(s) ?: return null
        val scheme = m.groupValues[1]
        val rest = s.substring(m.range.last + 1)
        if (rest.startsWith("//")) return scheme
        if ('.' in scheme) return null
        if (rest.firstOrNull()?.isDigit() == true) return null
        return scheme
    }

    /** Normalises detected URL-bar text (§7.3 steps 1–8). */
    fun normalizeDetected(raw: String): NormalizedUrl {
        val text = clean(raw)
        val scheme = schemeOf(text) ?: "https"
        val withScheme = if (schemeOf(text) == null) "https://$text" else text
        val host = if (scheme == "http" || scheme == "https") parseHost(withScheme.substringAfter(':')) else ""
        val ascii = toAscii(host)
        val proxied = if (ascii.isEmpty()) emptyList() else proxiedHosts(ascii, withScheme.substringAfter(':').removePrefix("//"))
        return NormalizedUrl(text, scheme, ascii, toUnicode(ascii), lowerNfc(percentDecode(text)), proxied)
    }

    private const val TRANSLATE_SUFFIX = ".translate.goog"
    private const val AMP_CACHE = "cdn.ampproject.org"
    private val googleHost = Regex("^(www\\.)?google(\\.[a-z]{2,3}){1,2}$")

    /**
     * The original host behind a proxy URL (see [NormalizedUrl.proxiedHosts]). [rest] is the text after `scheme://`.
     * The translate.goog and AMP-cache subdomain encodes the host with `-` for `.` and `--` for `-`.
     */
    internal fun proxiedHosts(host: String, rest: String): List<String> {
        val out = ArrayList<String>(1)
        val label = when {
            host.endsWith(TRANSLATE_SUFFIX) -> host.removeSuffix(TRANSLATE_SUFFIX)
            host.endsWith(".$AMP_CACHE") -> host.removeSuffix(".$AMP_CACHE")
            else -> null
        }
        if (label != null && '.' !in label && label.isNotEmpty()) decodeProxyLabel(label)?.let { out += it }
        val path = rest.substringAfter('/', "").substringBefore('?').substringBefore('#')
        val segments = path.split('/')
        val viaPath = when {
            // cdn.ampproject.org/c/s/www.example.com/… (also /v/ viewer and /i/ image paths); `s/` = https
            host == AMP_CACHE || host.endsWith(".$AMP_CACHE") ->
                if (segments.size >= 2 && segments[0] in setOf("c", "v", "i")) segments.drop(1) else null
            // www.google.com/amp/s/www.example.com/…
            googleHost.matches(host) -> if (segments.size >= 2 && segments[0] == "amp") segments.drop(1) else null
            else -> null
        }
        if (viaPath != null) {
            val h = (if (viaPath.firstOrNull() == "s") viaPath.drop(1) else viaPath).firstOrNull()
            if (h != null && '.' in h) {
                val ascii = toAscii(parseHost(h))
                if (ascii.isNotEmpty() && domainChars.matches(ascii)) out += ascii
            }
        }
        return out
    }

    private fun decodeProxyLabel(label: String): String? {
        val sb = StringBuilder(label.length)
        var i = 0
        while (i < label.length) {
            val c = label[i]
            if (c == '-' && i + 1 < label.length && label[i + 1] == '-') {
                sb.append('-')
                i += 2
            } else {
                sb.append(if (c == '-') '.' else c)
                i++
            }
        }
        val h = sb.toString().trim('.')
        return h.takeIf { '.' in it && domainChars.matches(it) }
    }

    /** NFC then lower-case (also after percent-decoding, which can produce upper-case or decomposed text). */
    private fun lowerNfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    /** Parses an authority after `scheme:` (step 6): drops userinfo, handles `[ipv6]`, cuts the port, strips trailing dots. */
    internal fun parseHost(afterScheme: String): String {
        val s = afterScheme.removePrefix("//")
        var authority = s.substring(0, s.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) s.length else it })
        authority = authority.substringAfterLast('@')
        val host = if (authority.startsWith("[")) {
            authority.substring(1, authority.indexOf(']').let { if (it < 0) authority.length else it })
        } else {
            authority.substringBefore(':')
        }
        return host.trimEnd('.')
    }

    fun toAscii(host: String): String = if (host.isEmpty() || isIpLiteral(host)) {
        host
    } else {
        try {
            // IDN maps U+3002 and other full stops to '.', so a trailing one only shows up here.
            IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT).trimEnd('.')
        } catch (_: IllegalArgumentException) {
            host
        }
    }

    fun toUnicode(host: String): String = if (host.isEmpty()) host else IDN.toUnicode(host, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)

    private fun isIpLiteral(host: String) = ipv4.matches(host) || (':' in host && ipv6.matches(host))

    /** Percent-decodes as UTF-8 where valid; invalid sequences are kept as typed. `+` is kept. */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && hexAt(s, i)) {
                val start = i
                val bytes = ByteArrayOutputStream()
                while (i < s.length && s[i] == '%' && hexAt(s, i)) {
                    bytes.write(s.substring(i + 1, i + 3).toInt(16))
                    i += 3
                }
                val decoded = decodeUtf8(bytes.toByteArray())
                out.append(decoded ?: s.substring(start, i))
            } else {
                out.append(s[i])
                i++
            }
        }
        return out.toString()
    }

    private fun hexAt(s: String, i: Int): Boolean =
        i + 2 < s.length && s[i + 1].isHex() && s[i + 2].isHex()

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun decodeUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** Normalises a user-typed rule of [kind] (§7.3 "User rule input"). */
    fun normalizeRuleInput(kind: WebRuleKind, input: String, anywhereInUrl: Boolean = false): RuleInput = when (kind) {
        WebRuleKind.DOMAIN -> normalizeDomainInput(input)
        WebRuleKind.KEYWORD -> normalizeKeyword(input, anywhereInUrl)
    }

    private fun normalizeDomainInput(input: String): RuleInput {
        val text = clean(input)
        if (text.isEmpty()) return RuleInput.Invalid("empty")
        if (whitespace.containsMatchIn(text)) return RuleInput.Invalid("domain contains whitespace")
        val scheme = schemeOf(text)
        if (scheme != null && scheme != "http" && scheme != "https") return RuleInput.Invalid("not a web address")
        val rest = if (scheme == null) text else text.substringAfter(':')
        val host = parseHost(rest).removePrefix("www.")
        val ascii = toAscii(host)
        val ok = isIpLiteral(ascii) || ('.' in ascii && domainChars.matches(ascii) && !ascii.startsWith(".") && ".." !in ascii)
        if (!ok) return RuleInput.Invalid("not a valid domain")
        val domain = WebRule(kind = WebRuleKind.DOMAIN, value = ascii)
        val afterAuthority = rest.removePrefix("//").let { r ->
            val i = r.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (i < 0) "" else r.substring(i)
        }
        val hasPath = afterAuthority.trimEnd('/').isNotEmpty()
        if (!hasPath) return RuleInput.Ok(domain)
        val kwText = percentDecode(text.removePrefix("https://").removePrefix("http://").removePrefix("www."))
        return when (val kw = normalizeKeyword(kwText, anywhereInUrl = true)) {
            is RuleInput.Ok -> RuleInput.Choice(domain, kw.rule)
            else -> RuleInput.Ok(domain)
        }
    }

    private fun normalizeKeyword(input: String, anywhereInUrl: Boolean): RuleInput {
        val k = Normalizer.normalize(input.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)
        if (k.length < 2) return RuleInput.Invalid("keyword needs at least 2 characters")
        if (whitespace.containsMatchIn(k)) return RuleInput.Invalid("keyword contains whitespace")
        return RuleInput.Ok(WebRule(kind = WebRuleKind.KEYWORD, value = k, anywhereInUrl = anywhereInUrl))
    }

    /**
     * Guesses the kind of a free-form entry: a valid domain (or URL) becomes DOMAIN (a URL with a path
     * returns [RuleInput.Choice]); anything else is tried as a host KEYWORD.
     */
    fun suggest(input: String): RuleInput {
        val asDomain = normalizeDomainInput(input)
        if (asDomain !is RuleInput.Invalid) return asDomain
        return normalizeKeyword(input, anywhereInUrl = false)
    }

    /** Bulk add (§7.3): one rule per line; CSV lines use the first column. Choices resolve to DOMAIN. */
    fun bulkAdd(text: String, existing: Collection<WebRule>, max: Int = ScheduleValidator.MAX_WEB_RULES): BulkAddResult {
        val seen = existing.map { it.kind to it.value }.toMutableSet()
        val added = ArrayList<WebRule>()
        var dup = 0
        var invalid = 0
        var over = 0
        for (line in text.lineSequence()) {
            val entry = line.substringBefore(',').trim().trim('"')
            if (entry.isEmpty()) continue
            if (existing.size + added.size >= max) {
                over++
                continue
            }
            val rule = when (val r = suggest(entry)) {
                is RuleInput.Ok -> r.rule
                is RuleInput.Choice -> r.domain
                is RuleInput.Invalid -> null
            }
            when {
                rule == null -> invalid++
                !seen.add(rule.kind to rule.value) -> dup++
                else -> added += rule
            }
        }
        return BulkAddResult(added, dup, invalid, over)
    }
}
