package dev.brainrotprevention.fixture.browser

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView

/** A browser's own screen without a URL bar or web content (like Brave's History or Settings). */
class NativeScreenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(20) { i -> list.addView(TextView(this).apply { text = "History entry $i" }) }
        setContentView(list)
    }
}

/** A site drawn without a URL bar (a web app / TWA): web content only. */
class AppModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            WebView(this).apply {
                settings.javaScriptEnabled = false
                loadDataWithBaseURL(null, "<html><body><h1>web app</h1><p>no URL bar</p></body></html>", "text/html", "utf-8", null)
            }
        )
    }
}
