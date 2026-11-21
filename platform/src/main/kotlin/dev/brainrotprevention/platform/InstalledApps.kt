package dev.brainrotprevention.platform

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import dev.brainrotprevention.model.PackageDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

data class AppEntry(val packageName: String, val label: String, val isSystem: Boolean = false)

/** Installed apps for the picker (§12) and the unsupported-browser list (§7.7). */
class InstalledApps internal constructor(private val app: Context, private val g: PlatformGraph) {
    private val pm: PackageManager get() = app.packageManager

    /** Bundled `app_domains.json`: package → domains ("Also block instagram.com?", §12). */
    val appDomains: Map<String, List<String>> by lazy {
        try {
            val text = app.assets.open("app_domains.json").bufferedReader().use { it.readText() }
            Json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(String.serializer())), text)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun isExempt(pkg: String): Boolean {
        if (pkg in PackageDefaults.ALWAYS_EXEMPT) return true
        val snap = g.data.snapshots.snapshot.value
        return pkg == app.packageName || pkg in g.packages.exempt || snap?.exemptPackages?.contains(pkg) == true
    }

    /** Launcher apps, sorted by label, without always-exempt packages. */
    suspend fun launchable(): List<AppEntry> = withContext(Dispatchers.IO) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filterNot { isExempt(it.packageName) }
            .map { entry(it) }
            .sortedBy { it.label.lowercase() }
    }

    suspend fun unsupportedBrowsers(): List<AppEntry> = withContext(Dispatchers.IO) {
        g.packages.unsupportedBrowsers().mapNotNull { p -> info(p)?.let { entry(it) } }.sortedBy { it.label.lowercase() }
    }

    suspend fun allowlistEssentials(): List<AppEntry> = withContext(Dispatchers.IO) {
        val snap = g.data.snapshots.current()
        (PackageDefaults.ALLOWLIST_ESSENTIALS + snap.allowlistEssentials).mapNotNull { p -> info(p)?.let { entry(it) } }
    }

    fun label(pkg: String): String = info(pkg)?.let { pm.getApplicationLabel(it).toString() } ?: pkg

    fun icon(pkg: String): Drawable? = try {
        pm.getApplicationIcon(pkg)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun info(pkg: String): ApplicationInfo? = try {
        pm.getApplicationInfo(pkg, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun entry(a: ApplicationInfo) =
        AppEntry(a.packageName, pm.getApplicationLabel(a).toString(), a.flags and ApplicationInfo.FLAG_SYSTEM != 0)
}
