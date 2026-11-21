package dev.brainrotprevention.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.telecom.InCallService
import android.telecom.TelecomManager
import dev.brainrotprevention.data.PlatformFacts
import dev.brainrotprevention.model.BrowserEntry
import dev.brainrotprevention.model.StrictGuards
import dev.brainrotprevention.platform.log.Brp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Resolves the platform facts of the snapshot (§6.3 exempt/essential packages, §7.2 browser table, §7.7
 * unsupported browsers, §7.9 web apps, §9.3 guard lists) and pushes them into the SnapshotStore.
 */
class PackageFacts internal constructor(private val app: Context, private val g: PlatformGraph) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val pm: PackageManager get() = app.packageManager

    val browsers: List<BrowserEntry> by lazy {
        val base = readAsset("browsers.json")
        val dbg = readAsset("browsers.debug.json")
        listOfNotNull(base, dbg).flatMap {
            runCatching { json.decodeFromString(ListSerializer(BrowserEntry.serializer()), it) }.getOrDefault(emptyList())
        }
    }

    val strictGuards: StrictGuards by lazy {
        readAsset("strict_guards.json")?.let { runCatching { json.decodeFromString(StrictGuards.serializer(), it) }.getOrNull() }
            ?: StrictGuards()
    }

    val browserPackages: Set<String> by lazy { browsers.map { it.packageName }.toSet() }

    val redirectUriPrefix: String get() = "content://${app.packageName}.blank/"

    @Volatile var exempt: Set<String> = emptySet()
        private set

    /** The default launcher at the last [refresh]. */
    @Volatile private var launcher: String? = null

    @Volatile private var homeCheckedRt = 0L

    /** The default HOME package; never "android" (resolver) or a guarded package (Settings' FallbackHome). */
    private fun resolveHome(): String? =
        runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
        }.getOrNull()?.takeIf { it != "android" && it !in strictGuards.settings && it !in strictGuards.installer }

    /**
     * True when the default launcher differs from the one of the last [refresh] (changing it sends no broadcast).
     * Checked at most once per [HOME_CHECK_MS]; call from the engine dispatcher.
     */
    fun homeChanged(): Boolean {
        val now = dev.brainrotprevention.platform.time.SystemTimeAndroid.elapsedRealtime()
        if (launcher == null || now - homeCheckedRt < HOME_CHECK_MS) return false
        homeCheckedRt = now
        val home = resolveHome() ?: return false
        return home != launcher
    }

    private fun readAsset(name: String): String? = try {
        app.assets.open(name).bufferedReader().use { it.readText() }
    } catch (_: Exception) {
        null
    }

    /** Recomputes everything and updates the snapshot. Logs `browsers` (§7.7). */
    suspend fun refresh(reason: String) = mutex.withLock {
        val exemptPkgs = resolveExempt()
        exempt = exemptPkgs
        val unsupported = unsupportedBrowsers()
        val webApps = webApps()
        val facts = PlatformFacts(
            browsers = browsers,
            strictGuards = strictGuards,
            exemptPackages = exemptPkgs,
            exemptComponents = emptySet(),
            allowlistEssentials = essentials(),
            unsupportedBrowsers = unsupported,
            webApps = webApps,
            ownPackage = app.packageName,
            redirectUriPrefix = redirectUriPrefix,
        )
        g.data.snapshots.setPlatformFacts(facts)
        val installed = installedPackages()
        val prev = g.data.settings.lastPackageSnapshot.firstOrNullSafe()
        val added = if (prev.isEmpty()) emptySet() else installed - prev
        val removed = if (prev.isEmpty()) emptySet() else prev - installed
        g.data.settings.setLastPackageSnapshot(installed)
        Brp.event(
            "browsers",
            "reason" to reason,
            "unsupported" to unsupported.sorted(),
            "webApps" to webApps.keys.sorted(),
            "added" to added.sorted(),
            "removed" to removed.sorted(),
        )
    }

    private suspend fun kotlinx.coroutines.flow.Flow<Set<String>>.firstOrNullSafe(): Set<String> =
        runCatching { first() }.getOrDefault(emptySet())

    private fun installedPackages(): Set<String> =
        runCatching { pm.getInstalledApplications(0).map { it.packageName }.toSet() }.getOrDefault(emptySet())

    fun unsupportedBrowsers(): Set<String> {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val found = runCatching { pm.queryIntentActivities(i, PackageManager.MATCH_ALL).map { it.activityInfo.packageName } }
            .getOrDefault(emptyList())
        return found.toSet() - browserPackages - app.packageName
    }

    private fun webApps(): Map<String, String> {
        val out = HashMap<String, String>()
        val apps = runCatching { pm.getInstalledApplications(PackageManager.GET_META_DATA) }.getOrDefault(emptyList())
        for (a in apps) {
            val url = a.metaData?.getString(WEBAPK_START_URL) ?: continue
            out[a.packageName] = url
        }
        return out
    }

    private fun resolveExempt(): Set<String> {
        val out = LinkedHashSet<String>()
        out += app.packageName
        // default launcher
        val home = resolveHome()
        home?.let { out += it }
        launcher = home ?: ""
        homeCheckedRt = dev.brainrotprevention.platform.time.SystemTimeAndroid.elapsedRealtime()
        // active IME
        Settings.Secure.getString(app.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it)?.packageName }?.let { out += it }
        // default dialer and in-call UIs
        runCatching { app.getSystemService(TelecomManager::class.java)?.defaultDialerPackage }.getOrNull()?.let { out += it }
        runCatching {
            pm.queryIntentServices(Intent(InCallService.SERVICE_INTERFACE), PackageManager.GET_META_DATA).forEach { r ->
                if (r.serviceInfo.metaData?.getBoolean(TelecomManager.METADATA_IN_CALL_SERVICE_UI) == true) out += r.serviceInfo.packageName
            }
        }
        // emergency dialer, emergency info, cell broadcast
        pm.resolveActivity(Intent("com.android.phone.EmergencyDialer.DIAL"), 0)?.activityInfo?.packageName?.let { out += it }
        pm.resolveActivity(Intent("android.settings.EDIT_EMERGENCY_INFO"), 0)?.activityInfo?.packageName?.let {
            if (it != "com.android.settings") out += it
        }
        for (p in EXTRA_EXEMPT) if (isInstalled(p)) out += p
        return out
    }

    private fun essentials(): Set<String> {
        val out = LinkedHashSet<String>()
        // Clock (§6.3), and the system screens other apps open on Android 14+ (share sheet, photo picker, captive
        // portal login; review round 2, adversarial 8), whatever package they live in on this device.
        val intents = listOf(
            Intent(AlarmClock.ACTION_SHOW_ALARMS),
            Intent(Intent.ACTION_CHOOSER),
            Intent("android.provider.action.PICK_IMAGES"),
            Intent("android.net.conn.CAPTIVE_PORTAL"),
        )
        for (i in intents) {
            runCatching { pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()?.let {
                if (it != "android") out += it
            }
        }
        return out
    }

    private fun isInstalled(p: String) = runCatching {
        pm.getPackageInfo(p, 0)
        true
    }.getOrDefault(false)

    companion object {
        const val WEBAPK_START_URL = "org.chromium.webapk.shell_apk.startUrl"
        private const val HOME_CHECK_MS = 1_000L
        private val EXTRA_EXEMPT = listOf(
            "com.android.emergency",
            "com.google.android.apps.safetyhub",
            "com.android.cellbroadcastreceiver",
            "com.google.android.cellbroadcastreceiver",
            "com.android.cellbroadcastreceiver.module",
        )
    }
}
