package app.painmeter

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Positions once a second, from one of two sources (Parameters → Location):
 * - Google's location service (default): fast first fix (Wi-Fi and cell help), but it filters
 *   and can add about a second of delay;
 * - the raw GPS chip: no extra filtering, so less lag, but a slower first fix and no help under
 *   trees or in tunnels.
 */
@SuppressLint("MissingPermission") // Callers check the location permission first.
class LocationFeed(context: Context, private val onLocation: (Location) -> Unit) {
    private val app = context.applicationContext
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(app) }
    private val manager by lazy { app.getSystemService(LocationManager::class.java) }
    private var raw: Boolean? = null

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(onLocation)
        }
    }
    private val rawListener = LocationListener { onLocation(it) }

    val running: Boolean get() = raw != null

    fun start(useRawGps: Boolean) {
        if (raw == useRawGps) return
        stop()
        raw = useRawGps
        if (useRawGps) {
            manager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, rawListener, Looper.getMainLooper())
        } else {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMinUpdateIntervalMillis(1000)
                .setMaxUpdateDelayMillis(0) // never batch: each position as soon as it exists
                .setWaitForAccurateLocation(false)
                .build()
            fused.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
        }
    }

    fun stop() {
        when (raw) {
            true -> manager?.removeUpdates(rawListener)
            false -> fused.removeLocationUpdates(fusedCallback)
            null -> Unit
        }
        raw = null
    }
}
