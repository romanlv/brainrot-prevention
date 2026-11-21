package dev.brainrotprevention.platform.enforce

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.brainrotprevention.platform.guarded
import dev.brainrotprevention.platform.log.Brp
import dev.brainrotprevention.platform.time.SystemTimeAndroid

/**
 * Experimental: keeps X's home timeline on "Following" by tapping that tab whenever "For you" is the selected one.
 *
 * Observed on X 12.44 (Compose; test tags are exposed as view ids): the home screen is under `scaffold_home_tabbed`.
 * The tab row holds one node per tab; the open tab is `selected` and not clickable, the others are clickable. Each tab
 * has a child whose content description is the tab label ("For you", "Following", "Add").
 *
 * Only the home screen is touched (Explore has its own "For you" tab and is left alone). Nothing is blocked or covered:
 * if X changes its layout, this simply stops doing anything.
 */
class XFollowingGuard(private val service: AccessibilityService) {
    /** The minimal node shape [findTapTarget] needs, so the decision can be unit-tested without Android. */
    interface Node {
        val desc: String?
        val viewId: String?
        val selected: Boolean
        val clickable: Boolean
        fun parent(): Node?
        fun children(): List<Node>
    }

    /** Set from the settings collector; a switch-off drops any pending check. */
    @Volatile var enabled = false
        set(v) {
            field = v
            if (!v) main.post { cancelPending() }
        }

    private var lastCheckRt = 0L
    private var lastTapRt = Long.MIN_VALUE / 2
    private var pending = false
    private var labels: Labels? = null
    private val main = Handler(Looper.getMainLooper())

    /** Taps whose result was still "For you" on the next check (X ignored the tap); each one doubles the wait. */
    private var failures = 0
    private var awaitingResult = false
    private val recheck = Runnable {
        pending = false
        guarded("x-following") { check() }
    }

    fun onEvent(e: AccessibilityEvent) {
        if (!enabled) return
        val t = e.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val since = SystemTimeAndroid.elapsedRealtime() - lastCheckRt
        // Throttled; the last change of a burst is still looked at.
        if (since < THROTTLE_MS) schedule(THROTTLE_MS - since) else check()
    }

    /** Package or locale changes: re-read X's tab labels on the next check. */
    fun invalidateLabels() {
        labels = null
    }

    private fun schedule(delayMs: Long) {
        if (pending) return
        pending = true
        main.postDelayed(recheck, delayMs)
    }

    private fun cancelPending() {
        main.removeCallbacks(recheck)
        pending = false
    }

    /** 1 s after a tap, doubling per ignored tap, at most [MAX_BACKOFF_MS]. */
    private fun tapInterval(): Long = minOf(AFTER_TAP_MS shl minOf(failures, 6), MAX_BACKOFF_MS)

    private fun check() {
        if (!enabled) return
        val now = SystemTimeAndroid.elapsedRealtime()
        lastCheckRt = now
        val wait = lastTapRt + tapInterval() - now
        if (wait > 0) {
            // Re-armed, so "For you" tapped right after an automatic tap is still caught without another event.
            schedule(wait)
            return
        }
        val root = xRoot() ?: return
        val l = labels ?: loadLabels().also { labels = it }
        val target = findTapTarget(AndroidNode(root), l.forYou, l.following, MAX_NODES)
        if (target == null) {
            if (failures > 0) Brp.event("x_following", "state" to "ok")
            failures = 0
            awaitingResult = false
            return
        }
        if (awaitingResult) failures++
        val ok = (target as AndroidNode).n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!ok) failures++
        lastTapRt = now
        awaitingResult = true
        if (failures <= 3) Brp.event("x_following", "tapped" to ok, "failures" to failures)
    }

    private fun xRoot(): AccessibilityNodeInfo? {
        service.rootInActiveWindow?.let { if (it.packageName == X_PKG) return it }
        return try {
            service.windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName == X_PKG } }
        } catch (e: RuntimeException) {
            null
        }
    }

    private data class Labels(val forYou: Set<String>, val following: Set<String>)

    /** X's own (translated) labels, plus the English ones. */
    private fun loadLabels(): Labels {
        val forYou = mutableSetOf("For you")
        val following = mutableSetOf("Following")
        try {
            val r = service.packageManager.getResourcesForApplication(X_PKG)
            fun add(set: MutableSet<String>, name: String) {
                val id = r.getIdentifier(name, "string", X_PKG)
                if (id != 0) runCatching { r.getString(id) }.getOrNull()?.takeIf { it.isNotBlank() }?.let(set::add)
            }
            add(forYou, "for_you_title")
            FOLLOWING_NAMES.forEach { add(following, it) }
        } catch (e: PackageManager.NameNotFoundException) {
            // X not installed: keep the English labels.
        }
        return Labels(forYou, following)
    }

    private class AndroidNode(val n: AccessibilityNodeInfo) : Node {
        override val desc: String? get() = n.contentDescription?.toString()
        override val viewId: String? get() = n.viewIdResourceName
        override val selected: Boolean get() = n.isSelected
        override val clickable: Boolean get() = n.isClickable
        override fun parent(): Node? = n.parent?.let(::AndroidNode)
        override fun children(): List<Node> = (0 until n.childCount).mapNotNull { i -> n.getChild(i)?.let(::AndroidNode) }
        override fun equals(other: Any?) = other is AndroidNode && other.n == n
        override fun hashCode() = n.hashCode()
    }

    companion object {
        const val X_PKG = "com.twitter.android"
        const val HOME_TAG = "scaffold_home_tabbed"
        const val THROTTLE_MS = 400L
        const val POST_TAG = "timeline_post"
        const val AFTER_TAP_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val MAX_NODES = 400

        /** X string resources that read "Following"; whichever exist in the installed version are used. */
        private val FOLLOWING_NAMES = listOf("social_following", "swish_following_tweets", "profile_connection_tab_following")

        /**
         * The tab to tap, or null: only when X's home screen ([HOME_TAG]) shows a selected "For you" tab. Prefers the
         * clickable sibling labelled "Following", else the clickable tab right after "For you".
         *
         * Cheap by design (it runs while X scrolls): timeline posts aren't descended into, the search stops at the
         * first tab label it meets, and at most [maxNodes] nodes are visited in each of the two searches.
         */
        fun findTapTarget(root: Node, forYou: Set<String>, following: Set<String>, maxNodes: Int = MAX_NODES): Node? {
            val home = findFirst(root, maxNodes) { it.viewId?.endsWith(HOME_TAG) == true } ?: return null
            val label = findFirst(home, maxNodes) { it.desc in forYou || it.desc in following } ?: return null
            if (label.desc !in forYou) return null
            val tab = label.parent()?.takeIf { it.selected } ?: return null
            val tabs = tab.parent()?.children() ?: return null
            val i = tabs.indexOf(tab)
            return tabs.firstOrNull { it != tab && it.clickable && it.children().any { c -> c.desc in following } }
                ?: tabs.getOrNull(i + 1)?.takeIf { i >= 0 && it.clickable }
        }

        private fun findFirst(root: Node, maxNodes: Int, match: (Node) -> Boolean): Node? {
            val stack = ArrayDeque<Node>().apply { add(root) }
            var seen = 0
            while (stack.isNotEmpty() && seen < maxNodes) {
                val n = stack.removeLast()
                seen++
                if (match(n)) return n
                if (n.viewId?.endsWith(POST_TAG) == true) continue
                n.children().asReversed().forEach(stack::addLast)
            }
            return null
        }
    }
}
