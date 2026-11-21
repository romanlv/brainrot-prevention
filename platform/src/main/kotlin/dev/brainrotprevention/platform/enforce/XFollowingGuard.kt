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

    @Volatile var enabled = false

    private var lastCheckRt = 0L
    private var lastTapRt = 0L
    private var trailingPosted = false
    private var labels: Labels? = null
    private val main = Handler(Looper.getMainLooper())

    fun onEvent(e: AccessibilityEvent) {
        if (!enabled) return
        val t = e.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        val now = SystemTimeAndroid.elapsedRealtime()
        if (now - lastCheckRt < THROTTLE_MS) {
            // Make sure the last change of a burst is still looked at.
            if (!trailingPosted) {
                trailingPosted = true
                main.postDelayed({
                    trailingPosted = false
                    guarded("x-following") { check() }
                }, THROTTLE_MS)
            }
            return
        }
        check()
    }

    /** Package or locale changes: re-read X's tab labels on the next check. */
    fun invalidateLabels() {
        labels = null
    }

    private fun check() {
        if (!enabled) return
        val now = SystemTimeAndroid.elapsedRealtime()
        lastCheckRt = now
        if (now - lastTapRt < AFTER_TAP_MS) return
        val root = xRoot() ?: return
        val l = labels ?: loadLabels().also { labels = it }
        val target = findTapTarget(AndroidNode(root), l.forYou, l.following, MAX_NODES) ?: return
        val ok = (target as AndroidNode).n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        lastTapRt = now
        Brp.event("x_following", "tapped" to ok)
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
        const val AFTER_TAP_MS = 1_000L
        const val MAX_NODES = 2_000

        /** X string resources that read "Following"; whichever exist in the installed version are used. */
        private val FOLLOWING_NAMES = listOf("social_following", "swish_following_tweets", "profile_connection_tab_following")

        /**
         * The tab to tap, or null: only when X's home screen ([HOME_TAG]) shows a selected "For you" tab. Prefers the
         * clickable sibling labelled "Following", else the clickable tab right after "For you". Visits at most
         * [maxNodes] nodes.
         */
        fun findTapTarget(root: Node, forYou: Set<String>, following: Set<String>, maxNodes: Int = MAX_NODES): Node? {
            val home = findFirst(root, maxNodes) { it.viewId?.endsWith(HOME_TAG) == true } ?: return null
            val label = findFirst(home, maxNodes) { n -> n.desc in forYou && n.parent()?.selected == true } ?: return null
            val tab = label.parent() ?: return null
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
                n.children().asReversed().forEach(stack::addLast)
            }
            return null
        }
    }
}
