package dev.brainrotprevention.platform.enforce

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.brainrotprevention.model.BrowserEntry
import dev.brainrotprevention.model.ForegroundApp
import dev.brainrotprevention.model.UrlBarIdKind
import dev.brainrotprevention.model.UrlBarSource
import dev.brainrotprevention.model.WebTarget
import dev.brainrotprevention.platform.log.Brp
import dev.brainrotprevention.platform.time.SystemTimeAndroid
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads browser URL bars (§7.8): throttled per window, judged only when the bar isn't focused and the text has
 * no internal whitespace; keeps `lastUrl` per (browser, window, bar) until a new URL is read in that bar, the window
 * goes away or the browser leaves the foreground set. Every application window of a browser is read (split screen,
 * "Open in new window", a Custom Tab next to the browser), not only the first one (review round 2, adversarial 3),
 * and every URL bar of a window is judged (a preview sheet with its own bar, review round 3 minor 4).
 *
 * Reads are dropped only for windows that `getWindows()` no longer lists. A failed `getWindows()` or a listed window
 * whose root is null (a busy UI thread, a transition) keeps them (review round 3, minor 3).
 *
 * Flags `url_bar_missing` after 10 s in which a browser window had no URL bar (§7.2). That clock is kept for
 * [MISSING_GRACE_MS] after the browser leaves the foreground, so relaunching a web app that the browser draws
 * without a URL bar doesn't restart the 10 s each time (adversarial 5).
 */
class BrowserUrlReader(private val service: AccessibilityService, private val browsers: () -> Map<String, BrowserEntry>) {
    data class Read(val url: String, val reliable: Boolean, val windowId: Int, val atRt: Long)

    /** [bar] is the URL bar's index among [findAll]'s matches in that window. */
    private data class Key(val pkg: String, val windowId: Int, val bar: Int)

    /** (browser package, window id, bar index) → latest read of that bar. */
    private val lastUrl = ConcurrentHashMap<Key, Read>()
    private val lastExtractRt = ConcurrentHashMap<Int, Long>()

    /** browser package → when one of its windows was first seen without a URL bar (and no earlier read). */
    private val firstSeenWithoutBarRt = ConcurrentHashMap<String, Long>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** browser package → when it left the foreground set (the missing clock is dropped [MISSING_GRACE_MS] later). */
    private val leftRt = ConcurrentHashMap<String, Long>()

    /** Returns true when the judged URL changed (evaluate now). */
    fun onEvent(e: AccessibilityEvent): Boolean {
        val pkg = e.packageName?.toString() ?: return false
        val entry = browsers()[pkg] ?: return false
        val type = e.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            return false
        }
        val now = SystemTimeAndroid.elapsedRealtime()
        val wid = e.windowId
        val last = lastExtractRt[wid] ?: 0L
        // Only a text change of the URL bar itself skips the throttle; typing in a web form must not trigger a
        // window walk per keystroke.
        val urlBarText = type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && isUrlBar(e, entry)
        if (!urlBarText && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && now - last < 250) return false
        lastExtractRt[wid] = now
        val root = windowsOf(pkg).roots.firstOrNull { it.windowId == wid } ?: return extract(pkg, entry)
        return extractWindow(pkg, entry, root).changed
    }

    private fun isUrlBar(e: AccessibilityEvent, entry: BrowserEntry): Boolean {
        val id = runCatching { e.source?.viewIdResourceName }.getOrNull() ?: return false
        return entry.urlBarIds.any { it.id == id || id.endsWith(":id/${it.id}") || id.endsWith("/${it.id}") }
    }

    private data class WindowRead(val changed: Boolean, val hasBar: Boolean)

    /**
     * Reads the URL bar of every application window of [pkg] now (the poll path, before every evaluation). Drops
     * reads of windows that are gone. Returns true when a judged URL changed.
     */
    fun extract(pkg: String, entry: BrowserEntry): Boolean {
        val w = windowsOf(pkg)
        val roots = w.roots
        // Prune only on a successful, non-empty listing, and keep windows that are listed but returned no root. A
        // browser that left the foreground is dropped by [targets].
        val listed = w.listedIds
        if (!listed.isNullOrEmpty()) lastUrl.keys.removeIf { it.pkg == pkg && it.windowId !in listed }
        var changed = false
        var anyWithoutBar = false
        for (root in roots) {
            val r = extractWindow(pkg, entry, root)
            changed = changed || r.changed
            if (!r.hasBar && lastUrl.keys.none { it.pkg == pkg && it.windowId == root.windowId }) anyWithoutBar = true
        }
        if (roots.isEmpty()) return changed
        if (anyWithoutBar) {
            noteMissing(pkg)
        } else {
            firstSeenWithoutBarRt.remove(pkg)
            if (missing.remove(pkg)) Brp.event("url_bar_found", "pkg" to pkg)
        }
        return changed
    }

    private fun extractWindow(pkg: String, entry: BrowserEntry, root: AccessibilityNodeInfo): WindowRead {
        val wid = root.windowId
        val bars = findAll(root, pkg, entry)
        if (bars.isEmpty()) {
            // Toolbar hidden after a read in this window: keep lastUrl (handled by the caller's missing check).
            return WindowRead(false, false)
        }
        // A bar that went away (a closed preview sheet) no longer shows its URL.
        lastUrl.keys.removeIf { it.pkg == pkg && it.windowId == wid && it.bar >= bars.size }
        // Judge every usable bar (unfocused, a URL without whitespace); a focused or empty bar keeps its last read.
        var changed = false
        bars.forEachIndexed { i, (node, reliable, source) ->
            val text = (if (source == UrlBarSource.TEXT) node.text else node.contentDescription)?.toString()?.trim()
            if (text.isNullOrEmpty() || node.isFocused || text.any { it.isWhitespace() }) return@forEachIndexed
            val key = Key(pkg, wid, i)
            if (lastUrl[key]?.url == text) return@forEachIndexed
            lastUrl[key] = Read(text, reliable, wid, SystemTimeAndroid.elapsedRealtime())
            Brp.event("url", "pkg" to pkg, "url" to text, "reliable" to reliable, "window" to wid, "bar" to i)
            changed = true
        }
        return WindowRead(changed, true)
    }

    private fun noteMissing(pkg: String) {
        val now = SystemTimeAndroid.elapsedRealtime()
        val first = firstSeenWithoutBarRt.getOrPut(pkg) { now }
        if (now - first >= 10_000 && missing.add(pkg)) Brp.event("url_bar_missing", "pkg" to pkg)
    }

    /**
     * The current text of every URL bar in [windowId] (any window of [pkg] when -1 or gone), read now; focused bars
     * are left out. Empty when no bar is found.
     */
    fun peek(pkg: String, entry: BrowserEntry, windowId: Int = -1): List<String> {
        val root = rootFor(pkg, windowId) ?: return emptyList()
        return findAll(root, pkg, entry).mapNotNull { (node, _, source) ->
            node.refresh()
            if (node.isFocused) return@mapNotNull null
            (if (source == UrlBarSource.TEXT) node.text else node.contentDescription)?.toString()?.trim()
        }
    }

    /**
     * [roots]: [pkg]'s application windows that returned a root, in `getWindows()` order. [listedIds]: the ids of every
     * listed application window (also those whose root was null), or null when `getWindows()` failed.
     */
    private class Windows(val roots: List<AccessibilityNodeInfo>, val listedIds: Set<Int>?)

    private fun windowsOf(pkg: String): Windows {
        val windows = try {
            service.windows
        } catch (_: RuntimeException) {
            null
        } ?: return Windows(emptyList(), null)
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val roots = apps.mapNotNull { w -> runCatching { w.root }.getOrNull()?.takeIf { it.packageName?.toString() == pkg } }
        return Windows(roots, apps.mapTo(HashSet()) { it.id })
    }

    /** Roots of [pkg]'s application windows, in `getWindows()` order. */
    fun rootsFor(pkg: String): List<AccessibilityNodeInfo> = windowsOf(pkg).roots

    /** The root of [pkg]'s window [windowId], else its first application window. */
    fun rootFor(pkg: String, windowId: Int): AccessibilityNodeInfo? {
        val roots = rootsFor(pkg)
        return roots.firstOrNull { it.windowId == windowId } ?: roots.firstOrNull()
    }

    /**
     * Finds the URL-bar node: the browser's ids in order (VIEW_ID by id lookup, COMPOSE_TAG by tree walk). With
     * [showing], the bar whose text is [showing] (the blocked URL) is preferred over the first one.
     */
    fun find(
        root: AccessibilityNodeInfo,
        pkg: String,
        entry: BrowserEntry,
        showing: String? = null,
    ): Triple<AccessibilityNodeInfo, Boolean, UrlBarSource>? {
        val all = findAll(root, pkg, entry)
        if (showing != null) {
            all.firstOrNull { (node, _, source) ->
                (if (source == UrlBarSource.TEXT) node.text else node.contentDescription)?.toString()?.trim() == showing
            }?.let { return it }
        }
        return all.firstOrNull()
    }

    /** Every URL-bar node of [root], in id order (all view-id matches, not only the first). */
    fun findAll(root: AccessibilityNodeInfo, pkg: String, entry: BrowserEntry): List<Triple<AccessibilityNodeInfo, Boolean, UrlBarSource>> {
        val out = ArrayList<Triple<AccessibilityNodeInfo, Boolean, UrlBarSource>>(1)
        for (id in entry.urlBarIds) {
            when (id.kind) {
                UrlBarIdKind.VIEW_ID -> root.findAccessibilityNodeInfosByViewId("$pkg:id/${id.id}")?.forEach {
                    out += Triple(it, id.reliable, id.source)
                }
                UrlBarIdKind.COMPOSE_TAG -> walk(root, id.id, 0)?.let { out += Triple(it, id.reliable, id.source) }
            }
        }
        return out
    }

    private fun walk(n: AccessibilityNodeInfo, tag: String, depth: Int): AccessibilityNodeInfo? {
        if (depth > 40) return null
        val rid = n.viewIdResourceName
        if (rid == tag || rid?.endsWith("/$tag") == true) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, tag, depth + 1)?.let { return it }
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                c.recycle()
            }
        }
        return null
    }

    /** Current web targets: `lastUrl` of every window of the browsers that are in the foreground set. */
    fun targets(foreground: List<ForegroundApp>): List<WebTarget> {
        val fg = foreground.map { it.packageName }.toSet()
        lastUrl.keys.removeIf { it.pkg !in fg }
        val now = SystemTimeAndroid.elapsedRealtime()
        for (pkg in fg) leftRt.remove(pkg)
        for (pkg in firstSeenWithoutBarRt.keys + missing) {
            if (pkg in fg) continue
            val left = leftRt.getOrPut(pkg) { now }
            if (now - left >= MISSING_GRACE_MS) {
                firstSeenWithoutBarRt.remove(pkg)
                missing.remove(pkg)
                leftRt.remove(pkg)
            }
        }
        return lastUrl.map { (k, r) -> WebTarget(k.pkg, r.url, r.reliable, r.windowId) }
    }

    /** Browsers in the table that showed a window without a URL bar for 10 s (treated as unsupported, §7.2). */
    fun urlBarMissing(): Set<String> = missing.toSet()

    /** After a rewrite or redirect: forget the blocked URL so it isn't judged again before the bar updates. */
    fun forget(pkg: String, windowId: Int? = null) {
        lastUrl.keys.removeIf { it.pkg == pkg && (windowId == null || it.windowId == windowId) }
    }

    fun clear() {
        lastUrl.clear()
        missing.clear()
        firstSeenWithoutBarRt.clear()
        leftRt.clear()
    }

    companion object {
        /** How long the url_bar_missing clock survives the browser leaving the foreground. */
        const val MISSING_GRACE_MS = 60_000L
    }
}
