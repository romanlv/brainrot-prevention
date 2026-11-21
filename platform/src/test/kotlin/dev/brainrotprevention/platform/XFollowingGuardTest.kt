package dev.brainrotprevention.platform

import dev.brainrotprevention.platform.enforce.XFollowingGuard
import dev.brainrotprevention.platform.enforce.XFollowingGuard.Companion.findTapTarget
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Tree shapes from X 12.44's home screen (structure only, captured on the phone). */
class XFollowingGuardTest {
    private class N(
        override val desc: String? = null,
        override val viewId: String? = null,
        override val selected: Boolean = false,
        override val clickable: Boolean = false,
        kids: List<N> = emptyList(),
    ) : XFollowingGuard.Node {
        var up: N? = null
        val kids = kids.onEach { it.up = this }
        override fun parent() = up
        override fun children() = kids
    }

    private val forYou = setOf("For you")
    private val following = setOf("Following")

    /** One tab: label child (content description) + text child, selected = open, clickable = not open. */
    private fun tab(label: String, open: Boolean) =
        N(selected = open, clickable = !open, kids = listOf(N(desc = label), N(), N()))

    private fun screen(scaffoldId: String, vararg tabs: N): N {
        val tabRow = N(kids = tabs.toList())
        val header = N(kids = listOf(tabRow))
        val scaffold = N(viewId = scaffoldId, kids = listOf(header, N(viewId = "timeline_post")))
        return N(viewId = "MainLanding", kids = listOf(scaffold))
    }

    @Test
    fun forYouOpenTapsFollowing() {
        val fol = tab("Following", open = false)
        val root = screen("scaffold_home_tabbed", tab("For you", open = true), fol, tab("Add", open = false))
        assertSame(fol, findTapTarget(root, forYou, following))
    }

    @Test
    fun followingOpenDoesNothing() {
        val root = screen("scaffold_home_tabbed", tab("For you", open = false), tab("Following", open = true), tab("Add", open = false))
        assertNull(findTapTarget(root, forYou, following))
    }

    @Test
    fun notHomeDoesNothing() {
        // Explore also has a "For you" tab; only the home scaffold counts.
        val root = screen("scaffold_explore", tab("For you", open = true), tab("Trending", open = false))
        assertNull(findTapTarget(root, forYou, following))
    }

    @Test
    fun unknownFollowingLabelFallsBackToTheNextTab() {
        val next = tab("Подписки", open = false)
        val root = screen("scaffold_home_tabbed", tab("Для вас", open = true), next, tab("Add", open = false))
        assertSame(next, findTapTarget(root, setOf("For you", "Для вас"), following))
    }

    @Test
    fun translatedLabelsMatch() {
        val fol = tab("Читаемые", open = false)
        val root = screen("scaffold_home_tabbed", tab("Для вас", open = true), fol)
        assertSame(fol, findTapTarget(root, setOf("Для вас"), setOf("Читаемые")))
    }

    @Test
    fun nodeBudgetStopsTheSearch() {
        val root = screen("scaffold_home_tabbed", tab("For you", open = true), tab("Following", open = false))
        assertNull(findTapTarget(root, forYou, following, maxNodes = 3))
    }
}
