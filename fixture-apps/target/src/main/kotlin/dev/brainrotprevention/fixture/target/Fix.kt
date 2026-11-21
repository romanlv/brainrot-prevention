package dev.brainrotprevention.fixture.target

import android.util.Log
import org.json.JSONObject

/** Logs one compact JSON line to tag BRPFIX, `ev` first. */
object Fix {
    fun log(ev: String, vararg fields: Pair<String, Any?>) {
        val o = JSONObject()
        o.put("ev", ev)
        o.put("pkg", "dev.brainrotprevention.fixture.target")
        for ((k, v) in fields) o.put(k, v ?: JSONObject.NULL)
        Log.i("BRPFIX", o.toString())
    }
}
