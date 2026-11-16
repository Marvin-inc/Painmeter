package app.painmeter.core.route

import app.painmeter.core.Geo
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTest {

    private val mLat = 111_195.0
    private fun mLon(lat: Double) = 111_195.0 * cos(Math.toRadians(lat))

    /** Builds a route from legs of (heading in degrees, length in metres), one point every 10 m. */
    private fun route(vararg legs: Pair<Double, Double>, lat0: Double = 45.0, lon0: Double = 5.7): Route {
        val pts = mutableListOf(RoutePoint(lat0, lon0, 200.0))
        var lat = lat0
        var lon = lon0
        for ((heading, length) in legs) {
            val h = Math.toRadians(heading)
            repeat((length / 10).toInt()) {
                lat += 10 * cos(h) / mLat
                lon += 10 * kotlin.math.sin(h) / mLon(lat)
                pts += RoutePoint(lat, lon, 200.0)
            }
        }
        return Route("test", pts)
    }

    /** A point [ahead] metres along and [side] metres to the right of a north-going route. */
    private fun offsetNorth(r: Route, ahead: Double, side: Double): Pair<Double, Double> {
        val p = r.pointAt(ahead)
        return p.lat to p.lon + side / mLon(p.lat)
    }

    @Test
    fun parsesTracksWithElevationAndName() {
        val gpx = """<?xml version="1.0"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1"><metadata><name>Ignored?</name></metadata>
            <trk><name><![CDATA[Vercors loop]]></name><trkseg>
              <trkpt lat="45.0" lon="5.7"><ele>200.5</ele><time>2026-01-01T00:00:00Z</time></trkpt>
              <trkpt lon="5.701" lat="45.001"><ele>210</ele></trkpt>
              <trkpt lat="45.001" lon="5.701"><ele>210</ele></trkpt>
              <trkpt lat='45.002' lon='5.702'/>
            </trkseg></trk></gpx>"""
        val r = GpxParser.parse(gpx)
        assertEquals("Ignored?", r.name) // first name in the file
        assertEquals(3, r.points.size)   // duplicate dropped
        assertEquals(RoutePoint(45.001, 5.701, 210.0), r.points[1])
        assertNull(r.points[2].ele)
        assertEquals(Geo.distanceM(45.0, 5.7, 45.001, 5.701) + Geo.distanceM(45.001, 5.701, 45.002, 5.702), r.lengthM, 1e-6)
    }

    @Test
    fun usesRoutePointsWhenThereIsNoTrack() {
        val gpx = """<gpx><rte><rtept lat="45" lon="5"><ele>1</ele></rtept><rtept lat="45.01" lon="5"/></rte></gpx>"""
        val r = GpxParser.parse(gpx, fallbackName = "file.gpx")
        assertEquals("file.gpx", r.name)
        assertEquals(2, r.points.size)
        assertFailsWith<IllegalArgumentException> { GpxParser.parse("<gpx></gpx>") }
    }

    @Test
    fun detectsTurnsAndTheirSide() {
        // North 500 m, right turn to east 500 m, sharp left to north-west 500 m, U-turn back.
        val r = route(0.0 to 500.0, 90.0 to 500.0, 315.0 to 500.0, 135.0 to 300.0)
        val turns = TurnDetector.find(r)
        assertEquals(3, turns.size, "turns $turns")
        assertEquals(500.0, turns[0].distanceM, 15.0)
        assertEquals(TurnKind.RIGHT, turns[0].kind)
        assertEquals(TurnKind.SHARP_LEFT, turns[1].kind)
        assertEquals(TurnKind.U_TURN, turns[2].kind)
    }

    @Test
    fun gentleBendsAreNotTurns() {
        val r = route(0.0 to 300.0, 20.0 to 300.0, 0.0 to 300.0)
        assertTrue(TurnDetector.find(r).isEmpty())
    }

    @Test
    fun followsProgressAndNextTurn() {
        val r = route(0.0 to 1000.0, 90.0 to 1000.0)
        val f = RouteFollower(r)
        val (lat, lon) = offsetNorth(r, 400.0, 8.0)
        val s = f.update(0, lat, lon)
        assertEquals(400.0, s.distanceAlongM, 1.0)
        assertEquals(8.0, s.offsetM, 0.5)
        assertEquals(r.lengthM - 400, s.remainingM, 1.0)
        assertFalse(s.offRoute)
        assertNotNull(s.nextTurn)
        assertEquals(600.0, s.distanceToTurnM!!, 15.0)
        assertEquals(TurnKind.RIGHT, s.nextTurn!!.kind)
    }

    @Test
    fun offRouteAfterTenSecondsAndBackOn() {
        val r = route(0.0 to 2000.0)
        val f = RouteFollower(r)
        f.update(0, r.pointAt(300.0).lat, r.pointAt(300.0).lon)
        var t = 0L
        val far = offsetNorth(r, 320.0, 120.0)
        repeat(9) { t += 1000; assertFalse(f.update(t, far.first, far.second).offRoute) }
        t += 2000
        val s = f.update(t, far.first, far.second)
        assertTrue(s.offRoute)
        assertEquals(120.0, s.offsetM, 2.0)
        // Coming back within 40 m is not enough; within 30 m is.
        val near = offsetNorth(r, 340.0, 40.0)
        assertTrue(f.update(t + 1000, near.first, near.second).offRoute)
        val on = offsetNorth(r, 350.0, 10.0)
        assertFalse(f.update(t + 2000, on.first, on.second).offRoute)
    }

    @Test
    fun outAndBackDoesNotJumpToTheOtherDirection() {
        // 1 km north, U-turn, back south on a parallel line 15 m away (other side of the road).
        val out = route(0.0 to 1000.0)
        val back = out.points.reversed().map { RoutePoint(it.lat, it.lon + 15 / mLon(it.lat), it.ele) }
        val r = Route("out and back", out.points + back)
        val f = RouteFollower(r)
        // Ride the way out: positions on the "out" line should stay in the first kilometre.
        listOf(100.0, 300.0, 500.0, 700.0).forEachIndexed { i, d ->
            val p = out.pointAt(d)
            val s = f.update(i * 1000L, p.lat, p.lon)
            assertTrue(s.distanceAlongM < 1000, "at $d m: ${s.distanceAlongM}")
            assertEquals(d, s.distanceAlongM, 3.0)
        }
    }
}
