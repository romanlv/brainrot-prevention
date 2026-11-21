package dev.brainrotprevention.e2edriver

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * UI actions for the e2e scenarios, without JUnit:
 *
 *   am instrument -w -e cmd tap|wait|text -e res <id> [-e text <s>] [-e timeout <ms>] [-e pkg <pkg>] \
 *       dev.brainrotprevention.e2edriver/.DriverInstrumentation
 *
 * `res` is a Compose testTag exposed with testTagsAsResourceId (plain id) or a view id; both
 * `<id>` and `<pkg>:id/<id>` match. Success: INSTRUMENTATION_RESULT result=OK and code -1
 * (RESULT_OK). Failure: result=FAIL, error=…, code 0.
 */
class DriverInstrumentation : Instrumentation() {
    private lateinit var args: Bundle

    override fun onCreate(arguments: Bundle?) {
        args = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        super.onStart()
        val out = Bundle()
        val cmd = args.getString("cmd") ?: "tap"
        val res = args.getString("res")
        val timeout = args.getString("timeout")?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS
        out.putString("cmd", cmd)
        out.putString("res", res ?: "")
        try {
            requireNotNull(res) { "missing -e res <id>" }
            InstrumentationRegistry.registerInstance(this, args)
            // Keep our accessibility service connected while UiAutomation is in use (SPEC §14.4).
            Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
            Configurator.getInstance().waitForIdleTimeout = 500
            val device = UiDevice.getInstance(this)
            val sel = selector(res, args.getString("pkg"))
            val started = SystemClock.uptimeMillis()
            val obj: UiObject2 = device.wait(Until.findObject(sel), timeout)
                ?: throw IllegalStateException("no object with res '$res' within $timeout ms")
            // read before acting: a tap may remove the object's window (e.g. the block overlay's Close)
            out.putString("found", obj.resourceName ?: "")
            when (cmd) {
                "tap" -> {
                    obj.click()
                    device.waitForIdle(1000)
                }
                "wait" -> Unit
                "text" -> obj.text = args.getString("text") ?: ""
                else -> throw IllegalArgumentException("unknown cmd '$cmd' (tap|wait|text)")
            }
            out.putLong("ms", SystemClock.uptimeMillis() - started)
            out.putString("result", "OK")
            Log.i(TAG, "$cmd $res OK")
            finish(Activity.RESULT_OK, out)
        } catch (t: Throwable) {
            Log.w(TAG, "$cmd $res FAIL", t)
            out.putString("result", "FAIL")
            out.putString("error", "${t.javaClass.simpleName}: ${t.message}")
            finish(Activity.RESULT_CANCELED, out)
        }
    }

    private fun selector(res: String, pkg: String?): BySelector {
        val idPart = Pattern.quote(res)
        val prefix = if (pkg != null) Pattern.quote(pkg) + ":id/" else "[^:]+:id/"
        return By.res(Pattern.compile("($prefix)?$idPart"))
    }

    companion object {
        private const val TAG = "BRPDRIVER"
        private const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
