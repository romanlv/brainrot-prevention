package dev.brainrotprevention.fixture.browser

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import java.io.ByteArrayInputStream
import org.json.JSONObject

/**
 * A minimal "browser" for the URL-reading tests (SPEC §14.2):
 * - EditText `url_bar` + WebView; the bar starts unfocused (the root takes focus in touch mode)
 * - after every navigation: url_bar.clearFocus() and the bar shows the loaded URL
 * - any editor action navigates
 * - `--es go <url>`: focus, set text, fire IME_ACTION_GO (same a11y text events as typing)
 * - `--es type <text>`: focus and set text only, no editor action
 * - http(s) is served locally (the emulator is offline); content:// loads normally
 */
class MainActivity : Activity() {
    private lateinit var bar: EditText
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
        }
        bar = EditText(this).apply {
            id = R.id.url_bar
            isSingleLine = true
            hint = "Search or type URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { v, actionId, _ ->
                log("submit", "text" to v.text.toString(), "actionId" to actionId)
                navigate(v.text.toString())
                true
            }
        }
        web = WebView(this).apply {
            id = R.id.web_view
            settings.allowContentAccess = true
            settings.allowFileAccess = false
            settings.javaScriptEnabled = false
            webViewClient = Client()
        }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
        root.requestFocus()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        log("resumed")
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val go = intent.getStringExtra("go")
        val type = intent.getStringExtra("type")
        when {
            go != null -> bar.post {
                bar.requestFocus()
                bar.setText(go)
                bar.setSelection(bar.text.length)
                bar.onEditorAction(EditorInfo.IME_ACTION_GO)
            }
            type != null -> bar.post {
                bar.requestFocus()
                bar.setText(type)
                bar.setSelection(bar.text.length)
                log("typed", "text" to type)
            }
            intent.action == Intent.ACTION_VIEW && intent.dataString != null -> navigate(intent.dataString!!)
        }
    }

    private fun navigate(raw: String) {
        val t = raw.trim()
        val url = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(t)) t else "https://$t"
        log("nav", "url" to url)
        web.loadUrl(url)
    }

    private fun afterNavigation(url: String) {
        bar.clearFocus()
        bar.setText(url)
        web.requestFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(bar.windowToken, 0)
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            log("page", "url" to url)
            afterNavigation(url)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url
            val scheme = u.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val html = "<html><head><title>${u.host}</title></head><body><h1>${u.host}</h1><p>${u.path}</p></body></html>"
            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray()))
        }
    }

    private fun log(ev: String, vararg fields: Pair<String, Any?>) {
        val o = JSONObject()
        o.put("ev", ev)
        o.put("pkg", packageName)
        for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
        Log.i("BRPFIX", o.toString())
    }
}
