package dev.brainrotprevention.engine

import dev.brainrotprevention.model.WebRuleKind
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlTest {
    private data class N(val input: String, val ascii: String, val unicode: String = ascii)

    @Test
    fun detectedNormalisation() {
        val cases = listOf(
            N("https://m.example.com/x", "m.example.com"),
            N("  HTTPS://WWW.Example.COM/Path ", "www.example.com"),
            N("example.com.", "example.com"), // trailing dot
            N("https://example.com./a", "example.com"),
            N("view-source:https://evil.test/", "evil.test"),
            N("https://user:pw@secret.test:8443/a", "secret.test"), // userinfo + port
            N("http://[2001:db8::1]:8080/x", "2001:db8::1"), // IPv6
            N("192.168.0.1:8080/admin", "192.168.0.1"),
            N("localhost:3000", "localhost"),
            N("https://bücher.de/", "xn--bcher-kva.de", "bücher.de"), // IDN
            N("xn--bcher-kva.de", "xn--bcher-kva.de", "bücher.de"),
            N("chrome://newtab", ""),
            N("about:blank", ""),
            N("javascript:alert(1)", ""),
            N("content://dev.x.blank/blank.html", ""),
            N("https://r.internet.apps.samsung.com/refer?id=1&url=https%3A%2F%2Fwww.reddit.com%2Fr%2Fgames", "www.reddit.com"),
            N("https://example.com?q=1#frag", "example.com"),
        )
        for (c in cases) {
            val n = UrlNormalizer.normalizeDetected(c.input)
            assertEquals(c.input, c.ascii, n.hostAscii)
            assertEquals(c.input, c.unicode, n.hostUnicode)
        }
        val ref = UrlNormalizer.normalizeDetected("https://r.internet.apps.samsung.com/refer?url=https%3A%2F%2Fsite.test%2Fr%2Fgames")
        assertEquals("https://site.test/r/games", ref.text)
        assertEquals("site.test", ref.itemKey)
        assertEquals("example.com", UrlNormalizer.normalizeDetected("www.example.com").itemKey)
    }

    @Test
    fun percentDecoding() {
        assertEquals("a b/ü", UrlNormalizer.percentDecode("a%20b%2F%C3%BC"))
        assertEquals("bad%C3x", UrlNormalizer.percentDecode("bad%C3x")) // invalid UTF-8 kept
        assertEquals("trail%2", UrlNormalizer.percentDecode("trail%2"))
        assertEquals("a+b", UrlNormalizer.percentDecode("a+b"))
        assertEquals("ok", UrlNormalizer.percentDecode("ok"))
    }

    @Test
    fun matching() {
        data class M(val rule: dev.brainrotprevention.model.WebRule, val url: String, val expected: Boolean)
        val cases = listOf(
            M(domain("facebook.com"), "https://m.facebook.com/x", true),
            M(domain("facebook.com"), "facebook.com", true),
            M(domain("facebook.com"), "notfacebook.com", false),
            M(domain("example.com"), "https://example.com.", true),
            M(keyword("game"), "https://store.epicgames.com", true),
            M(keyword("game"), "https://reddit.com/r/games", false),
            M(keyword("games", true), "https://site.test/r/games", true),
            M(keyword("games", true), "https://site.test/r/%67ames", true), // decoded
            M(keyword("bücher"), "https://xn--bcher-kva.de", true), // Unicode keyword on IDN host
            M(keyword("xn--bcher"), "https://bücher.de", true),
            M(keyword("سلام"), "https://سلام.example", true), // Arabic
            M(keyword("newtab"), "chrome://newtab", false), // no host: only anywhere keywords
            M(keyword("newtab", true), "chrome://newtab", true),
            M(domain("example.com"), "about:blank", false),
        )
        for (c in cases) assertEquals(c.toString(), c.expected, UrlMatcher.matches(c.rule, UrlNormalizer.normalizeDetected(c.url)))
        assertTrue(UrlMatcher.matchesAny(listOf(domain("a.test"), keyword("zz")), UrlNormalizer.normalizeDetected("zz.org")))
    }

    @Test
    fun domainRuleInput() {
        data class R(val input: String, val expected: String?)
        val cases = listOf(
            R("facebook.com", "facebook.com"),
            R("https://www.Facebook.com/", "facebook.com"),
            R("www.example.com.", "example.com"),
            R("http://user@host.test:80", "host.test"),
            R("bücher.de", "xn--bcher-kva.de"),
            R("10.0.0.1", "10.0.0.1"),
            R("[::1]", "::1"),
            R("localhost", null), // needs a dot
            R("bad_domain.com", null),
            R("two words.com", null),
            R("", null),
            R("chrome://settings", null),
            R(".com", null),
        )
        for (c in cases) {
            val r = UrlNormalizer.normalizeRuleInput(WebRuleKind.DOMAIN, c.input)
            if (c.expected == null) {
                assertTrue(c.input, r is RuleInput.Invalid)
            } else {
                assertEquals(c.input, c.expected, (r as RuleInput.Ok).rule.value)
            }
        }
        // A URL with a path offers domain vs keyword.
        val choice = UrlNormalizer.normalizeRuleInput(WebRuleKind.DOMAIN, "https://www.reddit.com/r/games") as RuleInput.Choice
        assertEquals("reddit.com", choice.domain.value)
        assertEquals("reddit.com/r/games", choice.keyword.value)
        assertTrue(choice.keyword.anywhereInUrl)
    }

    @Test
    fun keywordRuleInput() {
        assertEquals("game", (UrlNormalizer.normalizeRuleInput(WebRuleKind.KEYWORD, "  GAME ") as RuleInput.Ok).rule.value)
        // NFC: e + combining acute → é
        assertEquals("café", (UrlNormalizer.normalizeRuleInput(WebRuleKind.KEYWORD, "café") as RuleInput.Ok).rule.value)
        assertTrue(UrlNormalizer.normalizeRuleInput(WebRuleKind.KEYWORD, "a") is RuleInput.Invalid)
        assertTrue(UrlNormalizer.normalizeRuleInput(WebRuleKind.KEYWORD, "two words") is RuleInput.Invalid)
        assertTrue((UrlNormalizer.normalizeRuleInput(WebRuleKind.KEYWORD, "porn", true) as RuleInput.Ok).rule.anywhereInUrl)
        assertTrue(UrlNormalizer.suggest("example.com") is RuleInput.Ok)
        assertEquals(WebRuleKind.KEYWORD, (UrlNormalizer.suggest("gambling") as RuleInput.Ok).rule.kind)
    }

    @Test
    fun bulkAdd() {
        val r = UrlNormalizer.bulkAdd(
            "facebook.com\nhttps://www.facebook.com/\n\"tiktok.com\",social\nx\ngame\nreddit.com/r/all\n",
            listOf(domain("tiktok.com"))
        )
        assertEquals(listOf("facebook.com", "game", "reddit.com"), r.added.map { it.value })
        assertEquals(2, r.duplicates)
        assertEquals(1, r.invalid)
    }

    /** Property: rule normalisation is idempotent on random host-like inputs. */
    @Test
    fun propertyRuleNormalisationIdempotent() {
        val rnd = Random(3)
        val alphabet = "abcdefghijklmnopqrstuvwxyzü0123456789-."
        repeat(500) {
            val s = (1..rnd.nextInt(3, 20)).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
            for (kind in WebRuleKind.entries) {
                val r = UrlNormalizer.normalizeRuleInput(kind, s)
                if (r is RuleInput.Ok) {
                    val again = UrlNormalizer.normalizeRuleInput(kind, r.rule.value)
                    assertEquals(s, r, again)
                    if (kind == WebRuleKind.DOMAIN) {
                        assertTrue(UrlMatcher.matches(r.rule, UrlNormalizer.normalizeDetected("https://" + r.rule.value + "/x")))
                    }
                }
            }
            assertFalse(UrlNormalizer.normalizeDetected(s).hostAscii.endsWith("."))
        }
    }
}
