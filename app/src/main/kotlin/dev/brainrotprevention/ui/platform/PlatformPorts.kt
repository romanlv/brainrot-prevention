package dev.brainrotprevention.ui.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.location.LocationManager
import androidx.core.content.ContextCompat
import dev.brainrotprevention.data.WriteResult
import dev.brainrotprevention.engine.EmergencyResult
import dev.brainrotprevention.engine.StrictActivation
import dev.brainrotprevention.model.SensorState
import dev.brainrotprevention.platform.ActivationOutcome
import dev.brainrotprevention.platform.FixOutcome
import dev.brainrotprevention.platform.Grant
import dev.brainrotprevention.platform.PlatformGraph
import dev.brainrotprevention.platform.StrictActivator
import dev.brainrotprevention.platform.UnlockOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Wires the UI ports to the platform graph (platform/API.md). The only UI file that imports `platform.*`. */
object PlatformPorts {
    fun of(p: PlatformGraph): UiPlatform = UiPlatform(
        engine = object : EnginePort {
            override fun requestEvaluate() = p.engineHost.requestEvaluate("ui")

            override fun sensors(): SensorState = p.sensors.state()
        },
        health = object : HealthPort {
            override fun check(): UiHealth = p.health.check().let { r ->
                UiHealth(
                    items = r.items.map { UiGrantStatus(UiGrant.valueOf(it.kind.name), it.granted, it.required, it.detail) },
                    restrictedInstall = r.restrictedInstall,
                    advancedProtection = r.advancedProtection,
                    settingsLockedByStrict = r.settingsLockedByStrict,
                    accessibilityStarting = r.accessibilityStarting,
                )
            }

            override fun open(activity: Activity, grant: UiGrant): FixResult = fix(p.health.open(activity, Grant.valueOf(grant.name)))

            override fun runtimePermission(grant: UiGrant): String? = p.health.runtimePermission(Grant.valueOf(grant.name))

            override fun openAppDetails(activity: Activity): FixResult {
                if (p.health.settingsLockedByStrict()) return FixResult.LOCKED_BY_STRICT
                return runCatching { activity.startActivity(p.health.appDetailsIntent()) }
                    .fold({ FixResult.OPENED }, { FixResult.UNAVAILABLE })
            }
        },
        apps = object : AppsPort {
            override suspend fun launchable(): List<UiApp> = p.apps.launchable().map { UiApp(it.packageName, it.label) }

            override fun icon(pkg: String): Drawable? = p.apps.icon(pkg)

            override fun label(pkg: String): String = p.apps.label(pkg)

            override suspend fun unsupportedBrowsers(): List<UiApp> = p.apps.unsupportedBrowsers().map { UiApp(it.packageName, it.label) }

            override suspend fun allowlistEssentials(): List<UiApp> = p.apps.allowlistEssentials().map { UiApp(it.packageName, it.label) }

            override val appDomains: Map<String, List<String>> get() = p.apps.appDomains

            override fun currentSsid(): String? = p.sensors.state().ssid

            // TODO(platform): no "current location" entry point in platform/API.md yet; this reads the last known
            // fix directly (no request, no Play Services) until the platform exposes one.
            @SuppressLint("MissingPermission")
            override suspend fun currentLocation(): Pair<Double, Double>? = withContext(Dispatchers.IO) {
                val ctx = p.app
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                ) {
                    return@withContext null
                }
                val lm = ctx.getSystemService(LocationManager::class.java) ?: return@withContext null
                runCatching {
                    lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let {
                        it.latitude to
                            it.longitude
                    }
                }.getOrNull()
            }
        },
        strict = object : StrictPort {
            private val s: StrictActivator = p.strict

            override val pledge: String = StrictActivator.PLEDGE

            override suspend fun activate(req: StrictActivation): UiActivation = when (val o = s.activate(req)) {
                ActivationOutcome.Activated -> UiActivation.Activated
                is ActivationOutcome.Refused -> UiActivation.Refused(o.reason, o.message)
                is ActivationOutcome.NeedsDeviceAdmin -> UiActivation.NeedsDeviceAdmin(o.intent)
            }

            override suspend fun unlockWithCharger(): UiUnlock = unlock(s.unlockWithCharger())

            override suspend fun unlockWithPin(pin: String): UiUnlock = unlock(s.unlockWithPin(pin))

            override suspend fun finishCooldown(startedElapsed: Long): UiUnlock = unlock(s.finishCooldown(startedElapsed))

            override suspend fun finishStandbyTurnOff(startedElapsed: Long): UiUnlock = unlock(s.finishStandbyTurnOff(startedElapsed))

            override suspend fun extendTimer(extraMs: Long): WriteResult<Unit> = s.extendTimer(extraMs)

            override suspend fun finishEmergency(startedElapsed: Long): EmergencyResult = s.finishEmergency(startedElapsed)
        },
    )

    private fun fix(o: FixOutcome): FixResult = when (o) {
        FixOutcome.Opened -> FixResult.OPENED
        FixOutcome.LockedByStrict -> FixResult.LOCKED_BY_STRICT
        FixOutcome.Unavailable -> FixResult.UNAVAILABLE
    }

    private fun unlock(o: UnlockOutcome): UiUnlock = when (o) {
        UnlockOutcome.Unlocked -> UiUnlock.Unlocked
        is UnlockOutcome.WrongPin -> UiUnlock.WrongPin(o.lockedUntil)
        is UnlockOutcome.Refused -> UiUnlock.Refused(o.reason, o.remainingMs)
    }
}
