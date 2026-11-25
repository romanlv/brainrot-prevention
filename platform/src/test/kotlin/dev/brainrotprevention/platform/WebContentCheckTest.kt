package dev.brainrotprevention.platform

import dev.brainrotprevention.platform.enforce.BrowserUrlReader
import dev.brainrotprevention.platform.enforce.BrowserUrlReader.Companion.showsWebContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which bar-less browser windows count toward `url_bar_missing` (shapes from Brave 1.100 on API 37). */
class WebContentCheckTest {
    private class N(override val className: String?, private val kids: List<N> = emptyList()) : BrowserUrlReader.Node {
        override fun children() = kids
    }

    private fun frame(vararg kids: N) = N("android.widget.FrameLayout", kids.toList())

    @Test
    fun webAppWindowCounts() {
        // A site drawn without a URL bar (web app / TWA): compositor view > web contents.
        val web = N("android.webkit.WebView", listOf(N("android.view.View")))
        val root = frame(frame(N("android.widget.FrameLayout", listOf(web))))
        assertTrue(showsWebContent(root))
    }

    @Test
    fun nativeScreensDontCount() {
        // Brave's History screen: toolbar + RecyclerView of entries, no web content.
        val rows = List(50) { N("android.widget.LinearLayout", listOf(N("android.widget.TextView"), N("android.widget.TextView"))) }
        val toolbar = N("android.view.ViewGroup", listOf(N("android.widget.TextView")))
        val history = frame(toolbar, N("androidx.recyclerview.widget.RecyclerView", rows))
        assertFalse(showsWebContent(history))
        // The overflow menu popup and the splash screen.
        assertFalse(showsWebContent(frame(N("android.widget.ListView", List(14) { N("android.widget.TextView") }))))
        assertFalse(showsWebContent(frame(N("android.widget.ImageView"))))
    }

    @Test
    fun searchIsBreadthFirstAndBounded() {
        val deepList = N("androidx.recyclerview.widget.RecyclerView", List(600) { N("android.widget.TextView") })
        // Web content next to a long native list is still found (breadth first) …
        assertTrue(showsWebContent(frame(deepList, N("android.webkit.WebView"))))
        // … but the search stops after maxNodes (here the web content is queued behind the list's 600 rows).
        assertFalse(showsWebContent(frame(deepList, frame(N("android.webkit.WebView"))), maxNodes = 50))
    }
}
