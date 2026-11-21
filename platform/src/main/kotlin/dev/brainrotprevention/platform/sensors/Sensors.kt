package dev.brainrotprevention.platform.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import dev.brainrotprevention.model.LocationState
import dev.brainrotprevention.model.SensorState
import dev.brainrotprevention.platform.PlatformGraph
import dev.brainrotprevention.platform.log.Brp
import java.util.concurrent.ConcurrentHashMap

/**
 * Condition sensors (§11.5): the current Wi-Fi SSID (network callback) and geofence location states, with the
 * debug overrides of `sensor-ssid` / `sensor-location` (cleared by `sensor-real` and `reset`).
 */
class Sensors internal constructor(private val app: Context, private val g: PlatformGraph) {
    @Volatile private var realSsid: String? = null

    /** SSID per connected Wi-Fi network: losing one network (roaming, handover) keeps the other's SSID. */
    private val ssidByNetwork = ConcurrentHashMap<Network, String>()

    @Volatile private var loggedSsid: Any? = UNSET

    /** Debug override of the SSID: [UNSET] = use the real sensor. */
    @Volatile private var ssidOverride: Any? = UNSET
    private val locationOverride = ConcurrentHashMap<Long, LocationState>()
    private var callback: ConnectivityManager.NetworkCallback? = null
    val geofences = GeofenceSource(app, g)

    fun state(): SensorState {
        val o = ssidOverride
        val ssid = if (o === UNSET) realSsid else o as String?
        return SensorState(ssid = ssid, locations = HashMap(locationOverride))
    }

    /** Registers the Wi-Fi callback (idempotent; re-registers to pick up a new location grant). */
    @SuppressLint("MissingPermission")
    fun start() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        ssidByNetwork.clear() // the new callback reports every current network again
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = if (Build.VERSION.SDK_INT >= 31) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    setReal(network, (caps.transportInfo as? WifiInfo)?.ssid)
                }

                override fun onLost(network: Network) = setReal(network, null)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = setReal(network, legacySsid())

                override fun onLost(network: Network) = setReal(network, null)
            }
        }
        try {
            cm.registerNetworkCallback(req, cb)
            callback = cb
        } catch (e: RuntimeException) {
            Brp.event("error", "where" to "wifi-callback", "msg" to e.toString())
        }
        geofences.registerAll()
    }

    @Suppress("DEPRECATION")
    private fun legacySsid(): String? = app.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid

    private fun setReal(network: Network, raw: String?) {
        val ssid = clean(raw)
        if (ssid == null) ssidByNetwork.remove(network) else ssidByNetwork[network] = ssid
        realSsid = ssidByNetwork.values.minOrNull()
        if (ssidOverride === UNSET) changed()
    }

    private fun changed() {
        val now = state().ssid
        if (loggedSsid === UNSET || loggedSsid != now) {
            loggedSsid = now
            Brp.event("sensor", "ssid" to now)
        }
        g.engineHost.requestEvaluate("sensor")
    }

    fun overrideSsid(ssid: String?) {
        ssidOverride = ssid
        changed()
    }

    fun overrideLocation(rowId: Long, state: LocationState) {
        locationOverride[rowId] = state
        Brp.event("sensor", "row" to rowId, "state" to state.name)
        g.engineHost.requestEvaluate("sensor")
    }

    /** `sensor-real` and `reset`: drop every override. */
    fun real() {
        ssidOverride = UNSET
        locationOverride.clear()
        loggedSsid = UNSET
        start()
        changed()
    }

    fun hasLocationPermission(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    companion object {
        private val UNSET = Any()

        /** Strips only the surrounding quotes; `<unknown ssid>` means null (§11.5). */
        fun clean(raw: String?): String? {
            if (raw == null) return null
            val s = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
            return if (s.isEmpty() || s == WifiManager.UNKNOWN_SSID || s == "<unknown ssid>") null else s
        }
    }
}
