package dev.brainrotprevention.fixture.browser2

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import org.json.JSONObject

/** A browser that is in no browser table: it only claims https VIEW intents. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.dataString
        setContentView(
            TextView(this).apply {
                text = "BRP fixture browser2\n${url ?: ""}"
                textSize = 20f
            }
        )
        Log.i("BRPFIX", JSONObject().put("ev", "page").put("pkg", packageName).put("url", url ?: JSONObject.NULL).toString())
    }

    override fun onResume() {
        super.onResume()
        Log.i("BRPFIX", JSONObject().put("ev", "resumed").put("pkg", packageName).toString())
    }
}
