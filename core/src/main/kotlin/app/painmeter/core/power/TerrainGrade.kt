package app.painmeter.core.power

import app.painmeter.core.Geo
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gradient from the elevation tiles without lag: the terrain is a map, so it can be read ahead.
 *
 * Elevations are sampled every [stepM] along the road already ridden (up to [behindM] back) and
 * along the current heading ahead (up to [aheadM]), and the gradient is the slope of a straight
 * line fitted through them. Centring the window removes the lag of a trailing window (half its
 * length); the fit over ±150 m averages out the bumps of the 30 m elevation data (buildings,
 * trees, bridges), which a point-to-point difference turns into false gradients. On a real 4 km
 * town ride with a 90 m climb this gave 1.5 % mean error on the flat instead of 3 % for a 100 m
 * trailing window, and no lag on the climb.
 */
class TerrainGrade(
    private val lookup: (lat: Double, lon: Double) -> Double?,
    private val behindM: Double = 150.0,
    private val aheadM: Double = 150.0,
    private val stepM: Double = 10.0,
) {
    private class P(val d: Double, val lat: Double, val lon: Double)

    private val track = ArrayDeque<P>()
    private var distance = 0.0

    /** Adds the rider's position; returns the gradient (%) here, or null without elevation data. */
    fun update(lat: Double, lon: Double): Double? {
        track.lastOrNull()?.let { distance += Geo.distanceM(it.lat, it.lon, lat, lon) }
        if (track.isEmpty() || distance - track.last().d >= 2) track.addLast(P(distance, lat, lon))
        while (track.size > 2 && track.elementAt(1).d < distance - behindM - 20) track.removeFirst()

        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        // Behind: on the road actually ridden.
        var k = 0.0
        while (k <= behindM) {
            val p = at(distance - k) ?: break
            lookup(p.first, p.second)?.let { xs += -k; ys += it }
            k += stepM
        }
        // Ahead: straight on along the heading of the last 30 m.
        val h = heading()
        if (h != null) {
            var a = stepM
            while (a <= aheadM) {
                val (la, lo) = project(lat, lon, h, a)
                lookup(la, lo)?.let { xs += a; ys += it }
                a += stepM
            }
        }
        if (xs.size < 5 || xs.max() - xs.min() < 40) return null
        val mx = xs.average()
        val my = ys.average()
        var sxx = 0.0
        var sxy = 0.0
        for (i in xs.indices) { sxx += (xs[i] - mx) * (xs[i] - mx); sxy += (xs[i] - mx) * (ys[i] - my) }
        return if (sxx > 0) (sxy / sxx * 100).coerceIn(-25.0, 25.0) else null
    }

    /** Position on the ridden track at a distance, interpolated. */
    private fun at(d: Double): Pair<Double, Double>? {
        if (track.isEmpty() || d < track.first().d) return null
        for (i in track.size - 1 downTo 1) {
            val b = track.elementAt(i)
            val a = track.elementAt(i - 1)
            if (d >= a.d && d <= b.d) {
                val f = if (b.d > a.d) (d - a.d) / (b.d - a.d) else 0.0
                return a.lat + (b.lat - a.lat) * f to a.lon + (b.lon - a.lon) * f
            }
        }
        val last = track.last()
        return if (d >= last.d) last.lat to last.lon else null
    }

    private fun heading(): Double? {
        val last = track.lastOrNull() ?: return null
        val from = at(distance - 30) ?: return null
        if (Geo.distanceM(from.first, from.second, last.lat, last.lon) < 20) return null
        val p1 = Math.toRadians(from.first)
        val p2 = Math.toRadians(last.lat)
        val dl = Math.toRadians(last.lon - from.second)
        return atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl))
    }

    private fun project(lat: Double, lon: Double, headingRad: Double, m: Double): Pair<Double, Double> {
        val dLat = m * cos(headingRad) / 111_195.0
        val dLon = m * sin(headingRad) / (111_195.0 * cos(Math.toRadians(lat)))
        return lat + dLat to lon + dLon
    }
}
