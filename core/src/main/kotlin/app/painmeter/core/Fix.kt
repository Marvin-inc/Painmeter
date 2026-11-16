package app.painmeter.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One GPS position, as delivered by the location provider (about once a second).
 *
 * @property timeMs        UTC time of the fix, milliseconds since the Unix epoch
 * @property speedMps      Doppler speed from the GPS chip, null when the provider gave none
 * @property altitudeM     GPS altitude (WGS84), null when the provider gave none
 * @property accuracyM     horizontal accuracy radius (68 %), null when unknown
 * @property demAltitudeM  ground elevation from the offline elevation tiles, null when not covered
 */
data class Fix(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double? = null,
    val speedMps: Double? = null,
    val accuracyM: Double? = null,
    val demAltitudeM: Double? = null,
)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun distanceM(a: Fix, b: Fix): Double = distanceM(a.lat, a.lon, b.lat, b.lon)
}
