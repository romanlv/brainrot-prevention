package dev.brainrotprevention.platform

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import dev.brainrotprevention.model.StrictDisplayState
import dev.brainrotprevention.model.StrictOptions
import dev.brainrotprevention.platform.time.SystemTimeAndroid
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The grants of §11.3. */
enum class Grant {
    ACCESSIBILITY,
    USAGE_ACCESS,
    NOTIFICATION_LISTENER,
    POST_NOTIFICATIONS,
    EXACT_ALARMS,
    LOCATION_FINE,
    LOCATION_BACKGROUND,
    BATTERY_OPTIMIZATION,
    DEVICE_ADMIN,
}

data class GrantStatus(val kind: Grant, val granted: Boolean, val required: Boolean, val detail: String? = null)

data class HealthReport(
    val items: List<GrantStatus> = emptyList(),
    /** Installed from a file / browser: restricted settings may block the toggles (§11.3). */
    val restrictedInstall: Boolean = false,
    /** Android Advanced Protection is on (API 36+): accessibility is blocked for non-tool apps. */
    val advancedProtection: Boolean = false,
    /** Strict Mode ACTIVE with BLOCK_SETTINGS: our Settings deep links show "Locked by Strict Mode" (§9.3). */
    val settingsLockedByStrict: Boolean = false,
    /**
     * The service is enabled but hasn't bound yet, within [PermissionHealth.UNBOUND_GRACE_MS] of process start or of
     * the last unbind (a cold start): not reported as "Blocking is off" yet (review round 3, minor 5).
     */
    val accessibilityStarting: Boolean = false,
) {
    val ok: Boolean get() = items.all { it.granted || !it.required }

    operator fun get(kind: Grant): GrantStatus? = items.firstOrNull { it.kind == kind }
}

sealed interface FixOutcome {
    data object Opened : FixOutcome

    data object LockedByStrict : FixOutcome

    data object Unavailable : FixOutcome
}

/** Permission health (§11.3, §11.6). */
class PermissionHealth internal constructor(private val app: Context, private val g: PlatformGraph) {
    private val _report = MutableStateFlow(HealthReport())
    val report: StateFlow<HealthReport> = _report.asStateFlow()

    val a11yComponent: ComponentName get() = ComponentName(app, BlockerAccessibilityService::class.java)
    val listenerComponent: ComponentName get() = ComponentName(app, NotificationBlockerService::class.java)
    val adminComponent: ComponentName get() = ComponentName(app, StrictAdminReceiver::class.java)

    @Volatile private var offPosted = false

    /** When the service was last seen unbound (process start counts): enabled-but-unbound longer than this is off. */
    @Volatile private var unboundSinceRt = SystemTimeAndroid.elapsedRealtime()

    @Volatile private var recheckPending = false

    internal fun onServiceConnected(connected: Boolean) {
        if (!connected) unboundSinceRt = SystemTimeAndroid.elapsedRealtime()
    }

    /**
     * Enabled in Settings but not bound for more than [UNBOUND_GRACE_MS]: a crashed service is listed under
     * "Crashed services" and not rebound, so it is as off as a disabled one.
     */
    private fun enabledButDead(enabled: Boolean): Boolean {
        if (!enabled || g.service != null) return false
        val dead = SystemTimeAndroid.elapsedRealtime() - unboundSinceRt >= UNBOUND_GRACE_MS
        if (!dead && !recheckPending) {
            recheckPending = true
            g.scope.launch {
                delay(UNBOUND_GRACE_MS)
                recheckPending = false
                refresh()
            }
        }
        return dead
    }

    /** Health check (§11.6: app open, service connect, every 6 h). Posts "Blocking is off" once per outage. */
    fun refresh(): HealthReport {
        val r = check()
        _report.value = r
        val anyEnabled = g.data.snapshots.snapshot.value?.schedules?.any { it.enabled } == true
        val enabled = accessibilityEnabled()
        val off = !enabled || enabledButDead(enabled)
        if (off && anyEnabled) {
            if (!offPosted) {
                offPosted = true
                g.notifier.postHealth(
                    "Blocking is off",
                    if (settingsLockedByStrict()) {
                        "Turn the accessibility service back on when Strict Mode ends."
                    } else {
                        if (enabled) {
                            "The accessibility service stopped. Turn it off and on again in Accessibility settings."
                        } else {
                            "Turn the Brainrot Prevention accessibility service back on."
                        }
                    },
                )
            }
        } else {
            offPosted = false
        }
        return r
    }

    fun check(): HealthReport {
        val snapshot = g.data.snapshots.snapshot.value
        val needsLocation = snapshot?.schedules?.any { it.enabled && (it.wifi.isNotEmpty() || it.locations.isNotEmpty()) } == true
        // Geofencing needs background location on API 29+ (§11.5); without it a location trigger never changes.
        val needsBackgroundLocation = snapshot?.schedules?.any { it.enabled && it.locations.isNotEmpty() } == true
        val a11yOn = accessibilityEnabled()
        val connected = accessibilityConnected()
        val items = listOf(
            GrantStatus(
                Grant.ACCESSIBILITY,
                a11yOn && connected,
                true,
                when {
                    !a11yOn -> "off"
                    !connected -> "enabled, not connected"
                    else -> null
                },
            ),
            GrantStatus(Grant.USAGE_ACCESS, usageAccessGranted(), true),
            GrantStatus(Grant.NOTIFICATION_LISTENER, notificationListenerGranted(), false),
            GrantStatus(Grant.POST_NOTIFICATIONS, postNotificationsGranted(), false),
            GrantStatus(Grant.EXACT_ALARMS, exactAlarms(), false),
            GrantStatus(Grant.LOCATION_FINE, granted(Manifest.permission.ACCESS_FINE_LOCATION), needsLocation),
            GrantStatus(
                Grant.LOCATION_BACKGROUND,
                Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                needsBackgroundLocation,
                g.sensors.geofences.problem.takeIf { needsBackgroundLocation },
            ),
            GrantStatus(Grant.BATTERY_OPTIMIZATION, ignoringBatteryOptimizations(), false),
            GrantStatus(Grant.DEVICE_ADMIN, g.strict.isAdminActive(), false),
        )
        val starting = a11yOn && !connected && SystemTimeAndroid.elapsedRealtime() - unboundSinceRt < UNBOUND_GRACE_MS
        return HealthReport(items, restrictedInstall(), advancedProtection(), settingsLockedByStrict(), starting)
    }

    fun accessibilityEnabled(): Boolean {
        val list = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == a11yComponent }
    }

    fun accessibilityConnected(): Boolean = g.service != null

    fun usageAccessGranted(): Boolean {
        val ops = app.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
        }
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            granted(Manifest.permission.PACKAGE_USAGE_STATS)
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun notificationListenerGranted(): Boolean =
        app.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(listenerComponent) == true

    fun postNotificationsGranted(): Boolean = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    fun exactAlarms(): Boolean =
        Build.VERSION.SDK_INT < 31 || app.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    fun ignoringBatteryOptimizations(): Boolean =
        app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) == true

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** §11.3: installed from a local or downloaded file, or with no installer (API 33+). */
    fun restrictedInstall(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        return try {
            val info = app.packageManager.getInstallSourceInfo(app.packageName)
            info.packageSource == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE ||
                info.packageSource == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE ||
                info.installingPackageName == null
        } catch (_: Exception) {
            false
        }
    }

    fun advancedProtection(): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return try {
            val m = app.getSystemService(android.security.advancedprotection.AdvancedProtectionManager::class.java)
            m?.isAdvancedProtectionEnabled == true
        } catch (_: Throwable) {
            false
        }
    }

    fun settingsLockedByStrict(): Boolean {
        val strict = g.engineHost.status.value.strict
        val row = g.data.snapshots.snapshot.value?.strict ?: return false
        return row.isOn && strict == StrictDisplayState.ACTIVE && StrictOptions.has(row.options, StrictOptions.BLOCK_SETTINGS)
    }

    /** Runtime permission string for [kind], or null when it isn't a runtime permission here. */
    fun runtimePermission(kind: Grant): String? = when (kind) {
        Grant.POST_NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null
        Grant.LOCATION_FINE -> Manifest.permission.ACCESS_FINE_LOCATION
        Grant.LOCATION_BACKGROUND -> if (Build.VERSION.SDK_INT in 29..29) Manifest.permission.ACCESS_BACKGROUND_LOCATION else null
        else -> null
    }

    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))

    /** The Settings screen that fixes [kind]. */
    fun fixIntent(kind: Grant): Intent? = when (kind) {
        Grant.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        Grant.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${app.packageName}"))
        Grant.NOTIFICATION_LISTENER -> if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listenerComponent.flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        Grant.POST_NOTIFICATIONS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
        Grant.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= 31) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${app.packageName}"))
        } else {
            null
        }
        Grant.LOCATION_FINE, Grant.LOCATION_BACKGROUND -> appDetailsIntent()
        @Suppress("BatteryLife")
        Grant.BATTERY_OPTIMIZATION
        -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}"))
        Grant.DEVICE_ADMIN -> Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
    }

    /** Opens the fix screen unless the Strict Settings guard is active (§9.3). */
    fun open(activity: Activity, kind: Grant): FixOutcome {
        if (settingsLockedByStrict()) return FixOutcome.LockedByStrict
        val i = fixIntent(kind) ?: return FixOutcome.Unavailable
        return try {
            activity.startActivity(i)
            FixOutcome.Opened
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(appDetailsIntent())
                FixOutcome.Opened
            } catch (_: ActivityNotFoundException) {
                FixOutcome.Unavailable
            }
        }
    }

    private companion object {
        const val UNBOUND_GRACE_MS = 30_000L
    }
}
