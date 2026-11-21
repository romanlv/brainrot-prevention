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
 * no internal whitespace; keeps `lastUrl` per (browser, window) until a new URL is read, the window changes or
 * the browser leaves the foreground set. Flags `url_bar_missing` after 10 s without a URL bar (§7.2).
 */
class BrowserUrlReader(private val service: AccessibilityService, private val browsers: () -> Map<String, BrowserEntry>) {
    data class Read(val url: String, val reliable: Boolean, val windowId: Int, val atRt: Long)

    /** browser package → latest read (only the current window is kept). */
    private val lastUrl = ConcurrentHashMap<String, Read>()
    private val lastExtractRt = ConcurrentHashMap<Int, Long>()
    private val firstSeenWithoutBarRt = ConcurrentHashMap<String, Long>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

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
        return extract(pkg, entry, wid)
    }

    private fun isUrlBar(e: AccessibilityEvent, entry: BrowserEntry): Boolean {
        val id = runCatching { e.source?.viewIdResourceName }.getOrNull() ?: return false
        return entry.urlBarIds.any { it.id == id || id.endsWith(":id/${it.id}") || id.endsWith("/${it.id}") }
    }

    /** Reads the URL bar of [pkg]'s window now (also used by the poll). */
    fun extract(pkg: String, entry: BrowserEntry, windowId: Int = -1): Boolean {
        val root = rootFor(pkg, windowId) ?: return false
        val wid = root.windowId
        val found = find(root, pkg, entry) ?: run {
            noteMissing(pkg, wid)
            return false
        }
        val (node, reliable, source) = found
        firstSeenWithoutBarRt.remove(pkg)
        if (missing.remove(pkg)) Brp.event("url_bar_found", "pkg" to pkg)
        val text = (if (source == UrlBarSource.TEXT) node.text else node.contentDescription)?.toString()?.trim()
        val focused = node.isFocused
        if (text.isNullOrEmpty() || focused || text.any { it.isWhitespace() }) return false
        val prev = lastUrl[pkg]
        if (prev != null && prev.url == text && prev.windowId == wid) return false
        lastUrl[pkg] = Read(text, reliable, wid, SystemTimeAndroid.elapsedRealtime())
        Brp.event("url", "pkg" to pkg, "url" to text, "reliable" to reliable)
        return true
    }

    private fun noteMissing(pkg: String, wid: Int) {
        val prev = lastUrl[pkg]
        if (prev != null && prev.windowId == wid) return // toolbar hidden after a read: keep lastUrl
        val now = SystemTimeAndroid.elapsedRealtime()
        val first = firstSeenWithoutBarRt.getOrPut(pkg) { now }
        if (now - first >= 10_000 && missing.add(pkg)) Brp.event("url_bar_missing", "pkg" to pkg)
    }

    /** The URL bar's current text, read now without touching the cache; null when not found or focused. */
    fun peek(pkg: String, entry: BrowserEntry): String? {
        val root = rootFor(pkg, -1) ?: return null
        val (node, _, source) = find(root, pkg, entry) ?: return null
        node.refresh()
        if (node.isFocused) return null
        return (if (source == UrlBarSource.TEXT) node.text else node.contentDescription)?.toString()?.trim()
    }

    private fun rootFor(pkg: String, windowId: Int): AccessibilityNodeInfo? {
        val windows = try {
            service.windows
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val w = windows.firstOrNull { it.id == windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() == pkg }
            ?: return null
        val root = w.root ?: return null
        return if (root.packageName?.toString() == pkg) root else null
    }

    /** Finds the URL-bar node: the browser's ids in order (VIEW_ID by id lookup, COMPOSE_TAG by tree walk). */
    fun find(root: AccessibilityNodeInfo, pkg: String, entry: BrowserEntry): Triple<AccessibilityNodeInfo, Boolean, UrlBarSource>? {
        for (id in entry.urlBarIds) {
            val node = when (id.kind) {
                UrlBarIdKind.VIEW_ID -> root.findAccessibilityNodeInfosByViewId("$pkg:id/${id.id}")?.firstOrNull()
                UrlBarIdKind.COMPOSE_TAG -> walk(root, id.id, 0)
            }
            if (node != null) return Triple(node, id.reliable, id.source)
        }
        return null
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

    /** Current web targets: `lastUrl` of browsers that are in the foreground set. */
    fun targets(foreground: List<ForegroundApp>): List<WebTarget> {
        val fg = foreground.map { it.packageName }.toSet()
        lastUrl.keys.retainAll(fg)
        firstSeenWithoutBarRt.keys.retainAll(fg)
        missing.retainAll(fg)
        return lastUrl.map { (pkg, r) -> WebTarget(pkg, r.url, r.reliable, r.windowId) }
    }

    /** Browsers in the table that showed no URL bar for 10 s (treated as unsupported, §7.2). */
    fun urlBarMissing(): Set<String> = missing.toSet()

    /** After a rewrite or redirect: forget the blocked URL so it isn't judged again before the bar updates. */
    fun forget(pkg: String) {
        lastUrl.remove(pkg)
    }

    fun clear() {
        lastUrl.clear()
        missing.clear()
        firstSeenWithoutBarRt.clear()
    }
}
