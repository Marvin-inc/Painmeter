package app.painmeter.core.route

import app.painmeter.core.Geo
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class RoutePoint(val lat: Double, val lon: Double, val ele: Double? = null)

/** A route to follow: its points and the distance from the start to each of them. */
class Route(val name: String, val points: List<RoutePoint>) {
    init {
        require(points.size >= 2) { "a route needs at least 2 points" }
    }

    /** Distance from the start to each point, metres. */
    val cumDist: DoubleArray = DoubleArray(points.size).also { d ->
        for (i in 1 until points.size) d[i] = d[i - 1] + Geo.distanceM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
    }

    val lengthM: Double get() = cumDist.last()

    /** Elevation gain from the file's elevations (4 m hysteresis), null when the file has none. */
    val gainM: Double? by lazy {
        if (points.count { it.ele != null } < points.size / 2) return@lazy null
        val g = app.painmeter.core.ElevationGain(4.0)
        points.forEach { p -> p.ele?.let(g::add) }
        g.totalM
    }

    /** Position at a distance along the route. */
    fun pointAt(distanceM: Double): RoutePoint {
        val d = distanceM.coerceIn(0.0, lengthM)
        var i = cumDist.binarySearch(d).let { if (it >= 0) it else -it - 2 }.coerceIn(0, points.size - 2)
        while (i < points.size - 2 && cumDist[i + 1] < d) i++
        val seg = cumDist[i + 1] - cumDist[i]
        val f = if (seg > 0) (d - cumDist[i]) / seg else 0.0
        val a = points[i]
        val b = points[i + 1]
        val ele = if (a.ele != null && b.ele != null) a.ele + (b.ele - a.ele) * f else a.ele ?: b.ele
        return RoutePoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f, ele)
    }

    companion object {
        /** Initial bearing from a to b, degrees clockwise from north. */
        fun bearing(a: RoutePoint, b: RoutePoint): Double {
            val p1 = Math.toRadians(a.lat)
            val p2 = Math.toRadians(b.lat)
            val dl = Math.toRadians(b.lon - a.lon)
            val y = sin(dl) * cos(p2)
            val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }
    }
}

/**
 * Reads a GPX file: the track points (`trkpt`), or the route points (`rtept`) when there is no
 * track, with their elevation when present. Tolerant of attribute order and namespaces.
 */
object GpxParser {
    private val pointTag = Regex("""<(?:\w+:)?(trkpt|rtept)\b([^>]*?)(/>|>)""")
    private val latAttr = Regex("""\blat\s*=\s*["']([-+0-9.eE]+)["']""")
    private val lonAttr = Regex("""\blon\s*=\s*["']([-+0-9.eE]+)["']""")
    private val eleTag = Regex("""<(?:\w+:)?ele>\s*([-+0-9.eE]+)\s*</(?:\w+:)?ele>""")
    private val nameTag = Regex("""<(?:\w+:)?name>\s*(?:<!\[CDATA\[)?(.*?)(?:]]>)?\s*</(?:\w+:)?name>""", RegexOption.DOT_MATCHES_ALL)

    fun parse(xml: String, fallbackName: String = "Route"): Route {
        val all = pointTag.findAll(xml).toList()
        val kind = if (all.any { it.groupValues[1] == "trkpt" }) "trkpt" else "rtept"
        val points = mutableListOf<RoutePoint>()
        for ((i, m) in all.withIndex()) {
            if (m.groupValues[1] != kind) continue
            val lat = latAttr.find(m.groupValues[2])?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val lon = lonAttr.find(m.groupValues[2])?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            var ele: Double? = null
            if (m.groupValues[3] == ">") {
                // The point's children run until its closing tag (or, if missing, the next point).
                val next = all.getOrNull(i + 1)?.range?.first ?: xml.length
                val close = Regex("""</(?:\w+:)?$kind>""").find(xml, m.range.last)?.range?.first ?: next
                ele = eleTag.find(xml.substring(m.range.last, minOf(close, next)))?.groupValues?.get(1)?.toDoubleOrNull()
            }
            if (lat in -90.0..90.0 && lon in -180.0..180.0) points += RoutePoint(lat, lon, ele)
        }
        // Drop consecutive duplicates, which some exporters write.
        val clean = points.filterIndexed { i, p -> i == 0 || p.lat != points[i - 1].lat || p.lon != points[i - 1].lon }
        require(clean.size >= 2) { "no route points in this file" }
        val name = nameTag.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName
        return Route(name, clean)
    }
}
