package dev.brainrotprevention.platform

import androidx.core.content.FileProvider
import java.io.File

/**
 * Serves our bundled `blank.html` as the redirect target of a blocked page (§7.6). Authority
 * `${applicationId}.blank`; not exported; `grantUriPermissions`. The asset is copied to `files/blank/` on start.
 */
class BlankPageProvider : FileProvider() {
    override fun onCreate(): Boolean {
        val ctx = context ?: return super.onCreate()
        try {
            val dir = File(ctx.filesDir, "blank").apply { mkdirs() }
            val f = File(dir, "blank.html")
            val text = ctx.assets.open("blank.html").bufferedReader().use { it.readText() }
            if (!f.isFile || f.readText() != text) f.writeText(text)
        } catch (_: Exception) {
        }
        return super.onCreate()
    }
}
