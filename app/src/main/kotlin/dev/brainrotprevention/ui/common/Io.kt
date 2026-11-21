package dev.brainrotprevention.ui.common

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads at most [max] bytes; null when the stream is longer. */
fun InputStream.readLimited(max: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > max) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** Reads a SAF document as UTF-8 text (at most [max] bytes); null on failure or when too large. */
suspend fun readText(context: Context, uri: Uri, max: Int = 1_000_000): String? = withContext(Dispatchers.IO) {
    runCatching { context.contentResolver.openInputStream(uri)?.use { it.readLimited(max) }?.toString(Charsets.UTF_8) }.getOrNull()
}

/** Writes UTF-8 text to a SAF document (truncating). */
suspend fun writeText(context: Context, uri: Uri, text: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
    }.getOrDefault(false)
}
