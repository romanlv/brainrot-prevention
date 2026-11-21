package dev.brainrotprevention.fixture.target

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "BRP fixture target"
                textSize = 24f
            }
        )
    }

    override fun onResume() {
        super.onResume()
        Fix.log("resumed", "act" to "main")
    }

    override fun onPause() {
        super.onPause()
        Fix.log("paused", "act" to "main")
    }
}
