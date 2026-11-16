package app.painmeter.core.route

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

enum class TurnKind { SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, U_TURN }

/** A turn on the route, found from its shape (no routing engine, SPEC §9.5). */
data class Turn(val distanceM: Double, val angleDeg: Double) {
    val kind: TurnKind
        get() {
            val a = abs(angleDeg)
            return when {
                a >= 150 -> TurnKind.U_TURN
                angleDeg > 0 -> if (a >= 110) TurnKind.SHARP_RIGHT else if (a >= 55) TurnKind.RIGHT else TurnKind.SLIGHT_RIGHT
                else -> if (a >= 110) TurnKind.SHARP_LEFT else if (a >= 55) TurnKind.LEFT else TurnKind.SLIGHT_LEFT
            }
        }
}

object TurnDetector {
    /**
     * Resamples the route every [stepM] and compares the heading over the [spanM] before and after
     * each sample. A change above [minAngleDeg] is a turn; neighbouring candidates within [spanM]
     * are one turn, placed where the change is largest.
     */
    fun find(route: Route, stepM: Double = 10.0, spanM: Double = 30.0, minAngleDeg: Double = 35.0): List<Turn> {
        val n = (route.lengthM / stepM).toInt()
        if (n < 7) return emptyList()
        val pts = (0..n).map { route.pointAt(it * stepM) }
        val k = (spanM / stepM).toInt()
        val candidates = mutableListOf<Turn>()
        for (i in k until pts.size - k) {
            val before = Route.bearing(pts[i - k], pts[i])
            val after = Route.bearing(pts[i], pts[i + k])
            var d = after - before
            while (d > 180) d -= 360
            while (d <= -180) d += 360
            if (abs(d) > minAngleDeg) candidates += Turn(i * stepM, d)
        }
        val turns = mutableListOf<Turn>()
        var group = mutableListOf<Turn>()
        for (c in candidates) {
            if (group.isNotEmpty() && c.distanceM - group.last().distanceM > spanM) {
                turns += group.maxBy { abs(it.angleDeg) }
                group = mutableListOf()
            }
            group += c
        }
        if (group.isNotEmpty()) turns += group.maxBy { abs(it.angleDeg) }
        return turns
    }
}

/** Where the rider is on the route, after the latest position. */
data class NavState(
    val distanceAlongM: Double,
    /** Distance from the route, metres. */
    val offsetM: Double,
    val remainingM: Double,
    val offRoute: Boolean,
    val nextTurn: Turn?,
    val distanceToTurnM: Double?,
)

/**
 * Follows the rider along a route (SPEC §9.5): snaps each position to the route, looking first
 * near the previous position on it (so out-and-back and crossing roads are not confused), then
 * anywhere when the rider is far from it. Off route after more than 50 m for more than 10 s;
 * back on route within 30 m.
 */
class RouteFollower(
    val route: Route,
    private val offRouteM: Double = 50.0,
    private val offRouteAfterMs: Long = 10_000,
    private val backOnRouteM: Double = 30.0,
) {
    val turns: List<Turn> = TurnDetector.find(route)
    private var along: Double? = null
    private var farSinceMs: Long? = null
    private var offRoute = false

    fun update(timeMs: Long, lat: Double, lon: Double): NavState {
        val prev = along
        val local = if (prev != null) nearest(lat, lon, prev - 200, prev + 1000) else null
        val best = if (local != null && local.second <= offRouteM) local else {
            val global = nearest(lat, lon, 0.0, route.lengthM)
            // Jump elsewhere on the route only when clearly on it there.
            if (local == null || (global.second <= backOnRouteM && global.second < local.second)) global else local
        }
        along = best.first
        val offset = best.second

        if (offset > offRouteM) {
            val since = farSinceMs ?: timeMs.also { farSinceMs = it }
            if (timeMs - since >= offRouteAfterMs) offRoute = true
        } else {
            farSinceMs = null
            if (offset <= backOnRouteM) offRoute = false
        }
        val a = best.first
        val next = turns.firstOrNull { it.distanceM > a + 5 }
        return NavState(a, offset, (route.lengthM - a).coerceAtLeast(0.0), offRoute, next, next?.let { it.distanceM - a })
    }

    /** Nearest point of the route between two distances: (distance along, distance from it). */
    private fun nearest(lat: Double, lon: Double, fromM: Double, toM: Double): Pair<Double, Double> {
        val pts = route.points
        val cum = route.cumDist
        val mPerLat = 111_195.0
        val mPerLon = 111_195.0 * cos(Math.toRadians(lat))
        var bestAlong = 0.0
        var bestDist = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            if (cum[i + 1] < fromM || cum[i] > toM) continue
            val ax = (pts[i].lon - lon) * mPerLon
            val ay = (pts[i].lat - lat) * mPerLat
            val bx = (pts[i + 1].lon - lon) * mPerLon
            val by = (pts[i + 1].lat - lat) * mPerLat
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            if (d < bestDist) {
                bestDist = d
                bestAlong = cum[i] + t * (cum[i + 1] - cum[i])
            }
        }
        return bestAlong to bestDist
    }
}
