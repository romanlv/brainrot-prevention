package dev.brainrotprevention.platform.log

import android.util.Log
import dev.brainrotprevention.platform.time.SystemTimeAndroid

/**
 * The event log (SPEC §14.3 item 3): one compact JSON object per line on logcat tag `BRP`.
 *
 * Field order: `ev` first; for `mark`, `id` second; then the given fields; then `t` (policy ms) and
 * `rt` (real ms). The app replaces [policyNow] with its PolicyClock once that exists.
 */
object Brp {
    const val TAG = "BRP"

    /** Policy time in epoch ms (PolicyClock). Defaults to the real clock until the app installs its clock. */
    @Volatile
    var policyNow: () -> Long = { SystemTimeAndroid.currentTimeMillis() }

    /** Real time in epoch ms (SystemTime). */
    @Volatile
    var realNow: () -> Long = { SystemTimeAndroid.currentTimeMillis() }

    /** Where lines go; replaceable in JVM tests. */
    @Volatile
    var sink: (String) -> Unit = { line -> Log.i(TAG, line) }

    fun event(ev: String, vararg fields: Pair<String, Any?>) {
        val line = try {
            format(ev, policyNow(), realNow(), fields.asList())
        } catch (t: Throwable) {
            "{\"ev\":\"error\",\"what\":\"log-format\",\"msg\":${quote(t.toString())}}"
        }
        sink(line)
    }

    fun format(ev: String, t: Long, rt: Long, fields: List<Pair<String, Any?>>): String {
        val sb = StringBuilder(128)
        sb.append("{\"ev\":").append(quote(ev))
        val ordered = if (ev == "mark") fields.sortedBy { if (it.first == "id") 0 else 1 } else fields
        for ((k, v) in ordered) {
            if (k == "ev" || k == "t" || k == "rt") continue
            sb.append(',').append(quote(k)).append(':')
            value(sb, v)
        }
        sb.append(",\"t\":").append(t).append(",\"rt\":").append(rt).append('}')
        return sb.toString()
    }

    private fun value(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v)
            is Double -> if (v.isFinite()) sb.append(v) else sb.append("null")
            is Float -> if (v.isFinite()) sb.append(v) else sb.append("null")
            is Number -> sb.append(v)
            is Enum<*> -> sb.append(quote(v.name))
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(quote(k.toString())).append(':')
                    value(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> list(sb, v.iterator())
            is Array<*> -> list(sb, v.iterator())
            is JsonRaw -> sb.append(v.json)
            else -> sb.append(quote(v.toString()))
        }
    }

    private fun list(sb: StringBuilder, it: Iterator<*>) {
        sb.append('[')
        var first = true
        while (it.hasNext()) {
            if (!first) sb.append(',')
            first = false
            value(sb, it.next())
        }
        sb.append(']')
    }

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** A pre-encoded JSON value, embedded as is. */
    @JvmInline
    value class JsonRaw(val json: String)
}
