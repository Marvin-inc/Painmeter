package app.painmeter.core.climb

import app.painmeter.core.route.Route
import app.painmeter.core.route.RoutePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClimbsTest {

    /** A route heading north, one point every 10 m, from (length m, grade %) sections. */
    private fun route(vararg sections: Pair<Double, Double>, withEle: Boolean = true): Route {
        val pts = mutableListOf(RoutePoint(45.0, 5.7, if (withEle) 300.0 else null))
        var lat = 45.0
        var ele = 300.0
        for ((len, grade) in sections) repeat((len / 10).toInt()) {
            lat += 10 / 111_195.0
            ele += 10 * grade / 100
            pts += RoutePoint(lat, 5.7, if (withEle) ele else null)
        }
        return Route("r", pts)
    }

    @Test
    fun findsTheClimbsOfARoute() {
        val r = route(
            2000.0 to 0.0,
            3000.0 to 6.0,      // a real climb: 3 km at 6 %
            1000.0 to 0.0,
            300.0 to 5.0,       // a bump: 15 m, too small
            1000.0 to 0.0,
            1000.0 to 4.0,      // 1 km at 4 %, 200 m flat, 1 km at 5 %: one climb
            200.0 to 0.0,
            1000.0 to 5.0,
            2000.0 to -5.0,
        )
        val climbs = ClimbFinder.find(RouteProfile(r))
        assertEquals(2, climbs.size, "climbs $climbs")
        val a = climbs[0]
        assertEquals(2000.0, a.startM, 60.0)
        assertEquals(5000.0, a.endM, 60.0)
        assertEquals(180.0, a.gainM, 8.0)
        assertEquals(6.0, a.avgGradePercent, 0.3)
        val b = climbs[1]
        assertEquals(7300.0, b.startM, 80.0)
        assertEquals(9500.0, b.endM, 80.0)
        assertEquals(90.0, b.gainM, 8.0)
        assertEquals(listOf(0, 1), climbs.map { it.index })
    }

    @Test
    fun noElevationNoClimbs() {
        assertTrue(ClimbFinder.find(RouteProfile(route(3000.0 to 6.0, withEle = false))).isEmpty())
    }

    @Test
    fun elevationTilesFillTheProfile() {
        val r = route(1000.0 to 0.0, 2000.0 to 5.0, withEle = false)
        // A fake elevation model: 5 % north of 45.009°.
        val dem = { lat: Double, _: Double -> 300 + ((lat - 45.0) * 111_195.0 - 1000).coerceAtLeast(0.0) * 0.05 }
        val p = RouteProfile(r, dem)
        assertEquals(5.0, p.gradeAt(2000.0), 0.1)
        assertEquals(1, ClimbFinder.find(p).size)
    }

    /** Feeds a ride of (length m, grade %) sections at [speed] m/s, 1 Hz, into a detector. */
    private fun ride(d: LiveClimbDetector, speed: Double, vararg sections: Pair<Double, Double>, noise: Boolean = false): List<ClimbProgress?> {
        var t = 0L
        var dist = 0.0
        var ele = 300.0
        val out = mutableListOf<ClimbProgress?>()
        var k = 0
        for ((len, grade) in sections) {
            val end = dist + len
            while (dist < end) {
                dist += speed
                ele += speed * grade / 100
                t += 1000
                val e = if (noise) ele + listOf(0.8, -0.6, 0.3, -0.9, 0.5)[k++ % 5] else ele
                out += d.add(ClimbSample(t, dist, e, 250.0, 160))
            }
        }
        return out
    }

    @Test
    fun liveDetectionMovesTheStartBack() {
        val d = LiveClimbDetector()
        val states = ride(d, 4.0, 1000.0 to 0.0, 2000.0 to 6.0, 600.0 to 0.0)
        val firstActive = states.indexOfFirst { it != null }
        // Announced after about 150–170 m of climbing (10 m gained at 6 %)…
        assertTrue(firstActive in (1000 / 4 + 35)..(1000 / 4 + 50), "announced at sample $firstActive")
        // …but starting where the climb really starts.
        val c = d.climbs.single()
        assertEquals(1000.0, c.startDistanceM, 60.0)
        assertEquals(2000.0, c.distanceM, 80.0)
        assertEquals(120.0, c.gainM, 6.0)
        assertEquals(250.0, c.avgPower!!, 1e-9)
        assertEquals(160, c.avgHr)
        assertEquals(6.0 * 4 * 3.6 * 10, c.vamMph!!, 40.0) // 864 m/h
        assertTrue(c.finished)
        assertNotNull(states.last { it?.finished == true })
    }

    @Test
    fun shortBumpsAndNoiseAreNotClimbs() {
        val d = LiveClimbDetector()
        ride(d, 8.0, 2000.0 to 0.0, 100.0 to 6.0, 500.0 to -1.0, 120.0 to 5.0, 2000.0 to 0.0, noise = true)
        assertTrue(d.climbs.isEmpty(), "climbs ${d.climbs}")
        assertNull(ride(d, 8.0, 500.0 to 0.0).last())
    }

    @Test
    fun aClimbWithAShortFlatIsOneClimb() {
        val d = LiveClimbDetector()
        ride(d, 4.0, 500.0 to 0.0, 800.0 to 6.0, 120.0 to 1.0, 800.0 to 6.0, 600.0 to 0.0)
        assertEquals(1, d.climbs.size, "climbs ${d.climbs}")
        assertEquals(1720.0, d.climbs[0].distanceM, 100.0)
    }

    @Test
    fun routeClimbStartsAndEndsWhereTheRouteSays() {
        val d = LiveClimbDetector()
        ride(d, 5.0, 300.0 to 0.0)
        val rc = RouteClimb(0, 300.0, 1300.0, 300.0, 350.0)
        d.startRouteClimb(rc)
        val inClimb = ride(d, 5.0, 1000.0 to 5.0)
        assertTrue(inClimb.all { it?.fromRoute == rc })
        val end = d.endRouteClimb()!!
        assertEquals(50.0, end.gainM, 1.0)
        assertEquals(rc, end.fromRoute)
        assertEquals(1, d.climbs.size)
    }
}
