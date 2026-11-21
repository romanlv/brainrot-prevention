package dev.brainrotprevention.platform.sensors

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.brainrotprevention.model.LocationCondition
import dev.brainrotprevention.model.LocationState
import dev.brainrotprevention.platform.PlatformGraph
import dev.brainrotprevention.platform.log.Brp
import dev.brainrotprevention.platform.platform
import kotlinx.coroutines.launch

/**
 * Location triggers through the Play Services Geofencing API (§11.5): one geofence per row (ENTER/EXIT), then
 * one `getCurrentLocation` to set `lastState`. Transitions arrive through [GeofenceReceiver].
 */
class GeofenceSource internal constructor(private val app: Context, private val g: PlatformGraph) {
    private fun pendingIntent(): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(app, 1, Intent(app, GeofenceReceiver::class.java), flags)
    }

    /** (Re-)registers every location row (boot, PROVIDERS_CHANGED, service start, config change). */
    @SuppressLint("MissingPermission")
    fun registerAll() {
        g.scope.launch {
            try {
                val rows = g.data.schedules.getAll().filter { it.enabled }.flatMap { it.locations }.take(100)
                val client = LocationServices.getGeofencingClient(app)
                client.removeGeofences(pendingIntent())
                if (rows.isEmpty() || !g.sensors.hasLocationPermission()) return@launch
                val fences = rows.map {
                    Geofence.Builder()
                        .setRequestId(it.id.toString())
                        .setCircularRegion(it.lat, it.lon, it.radiusM.toFloat())
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                        .build()
                }
                val req = GeofencingRequest.Builder()
                    .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                    .addGeofences(fences)
                    .build()
                client.addGeofences(req, pendingIntent())
                    .addOnFailureListener { Brp.event("error", "where" to "geofence", "msg" to it.toString()) }
                LocationServices.getFusedLocationProviderClient(app)
                    .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { loc -> if (loc != null) applyLocation(rows, loc) }
            } catch (e: Exception) {
                Brp.event("error", "where" to "geofence", "msg" to e.toString())
            }
        }
    }

    private fun applyLocation(rows: List<LocationCondition>, loc: Location) {
        g.scope.launch {
            for (r in rows) {
                val d = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, r.lat, r.lon, d)
                val st = if (d[0] <= r.radiusM) LocationState.INSIDE else LocationState.OUTSIDE
                setState(r.id, st)
            }
        }
    }

    internal suspend fun setState(rowId: Long, st: LocationState) {
        g.data.schedules.setLocationState(rowId, st)
        Brp.event("sensor", "row" to rowId, "state" to st.name)
    }
}

/** Geofence transitions (not exported). */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) return
        val st = when (ev.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> LocationState.INSIDE
            Geofence.GEOFENCE_TRANSITION_EXIT -> LocationState.OUTSIDE
            else -> return
        }
        val g = context.platform
        val pending = goAsync()
        g.scope.launch {
            try {
                ev.triggeringGeofences?.forEach { f -> f.requestId.toLongOrNull()?.let { g.sensors.geofences.setState(it, st) } }
            } finally {
                pending.finish()
            }
        }
    }
}
