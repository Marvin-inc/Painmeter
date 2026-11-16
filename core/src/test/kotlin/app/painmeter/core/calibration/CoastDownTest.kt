package app.painmeter.core.calibration

import app.painmeter.core.power.PostRideModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * SPEC §12 test 3: the Kotlin calibration gives the same CdA / Crr as the web tool on the same
 * ride. The ride is a simulated coast-down test (truth: CdA 0.30, Crr 0.005, 0.5 % slope, 2 m/s
 * wind); expected values come from the web tool run with Node (make_coastdown_golden.js).
 */
class CoastDownTest {
    private val lines = javaClass.getResource("/golden/coastdown.in.csv")!!.readText().lines().filter { it.isNotBlank() }
    private val rows = lines.filter { !it.startsWith("#") && !it.startsWith("lat") }.map { it.split(',') }
    private val points = rows.map { PostRideModel.Point(it[0].toDouble(), it[1].toDouble(), it[2].toDouble(), it[3].toLong(), it[4].toDouble()) }
    private val laps = lines.filter { it.startsWith("#lap") }.map { it.split(' ').let { p -> p[1].toDouble() to p[2].toDouble() } }
    private val calib = PostRideModel.process(points, PostRideModel.Params(72.0, 8.0, 0.30, 0.005, 1.2, 2.0, 15.0)).calib
    private val input = CoastDown.Input(
        calib, points.map { it.lat }.toDoubleArray(), points.map { it.lon }.toDoubleArray(),
        arrayOfNulls(points.size), laps, hasEle = true, hasWind = false,
    )
    private val expected = javaClass.getResource("/golden/coastdown.expected.csv")!!.readText().lines()
        .drop(1).filter { it.isNotBlank() }.associate { l -> l.split(',').let { it[0] to it } }

    private fun check(name: String, mass: Double, crr: Double, fixed: Boolean, paired: Boolean, laps: Boolean) {
        val e = expected.getValue(name)
        val r = CoastDown.run(input, mass, crr, fixed, paired, laps)
        assertEquals(e[1], r.error ?: "", "$name error")
        assertEquals(e[2], r.source, "$name source")
        assertEquals(e[3].toInt(), r.segs.size, "$name runs")
        assertEquals(e[4], r.method ?: "", "$name method")
        assertEquals(e[5], r.mode ?: "", "$name mode")
        assertEquals(e[6], r.fallback ?: "", "$name fallback")
        assertEquals(e[7].toBoolean(), r.pairedAvailable, "$name paired available")
        val f = r.fit!!
        assertEquals(e[8].toDouble(), f.cda, 1e-9, "$name CdA")
        assertEquals(e[9].toDouble(), f.crr, 1e-11, "$name Crr")
        assertEquals(e[10].toDouble(), f.seCda, 1e-9, "$name ±CdA")
        if (e[11].isNotEmpty()) assertEquals(e[11].toDouble(), f.seCrr!!, 1e-11, "$name ±Crr")
        assertEquals(e[12].toDouble(), f.r2, 1e-9, "$name R²")
        assertEquals(e[13].toInt(), f.n, "$name windows")
        if (e[14].isNotEmpty()) assertEquals(e[14].toDouble(), f.wind!!, 1e-9, "$name wind")
        if (e[15].isNotEmpty()) assertEquals(e[15].toDouble(), f.slope!!, 1e-9, "$name slope")
        assertEquals(e[16].toInt(), r.wins.count { it.used }, "$name used windows")
    }

    @Test fun pairedWithLapMarks() = check("paired", 80.0, 0.004, fixed = false, paired = true, laps = true)
    @Test fun singleDirection() = check("single", 80.0, 0.004, fixed = false, paired = false, laps = true)
    @Test fun runsFoundFromTheSlowDown() = check("slowdown", 80.0, 0.004, fixed = false, paired = true, laps = false)
    @Test fun crrKeptFixed() = check("fixed", 80.0, 0.0045, fixed = true, paired = true, laps = true)

    @Test
    fun recoversTheTruth() {
        val f = CoastDown.run(input, 80.0, 0.004, forceFixed = false).fit!!
        assertEquals(0.30, f.cda, 0.01)
        assertEquals(0.005, f.crr, 0.0005)
        assertEquals(2.0, kotlin.math.abs(f.wind!!), 0.2)
        assertEquals(0.005, kotlin.math.abs(f.slope!!), 0.001)
    }
}

class RideCalibrationTest {
    @Test
    fun aPainmeterRideGivesTheSameResultAsTheFile() {
        // Rebuild the simulated test as a Painmeter journal: samples, and a lap mark at every press.
        val lines = javaClass.getResource("/golden/coastdown.in.csv")!!.readText().lines().filter { it.isNotBlank() }
        val rows = lines.filter { !it.startsWith("#") && !it.startsWith("lat") }.map { it.split(',') }
        val laps = lines.filter { it.startsWith("#lap") }.map { it.split(' ')[2].toDouble() }
        val t0 = rows.first()[3].toLong()
        val settings = app.painmeter.core.RideSettings(
            autoPause = false,
            power = app.painmeter.core.power.PowerParams(72.0, 8.0, 0.30, 0.005, 2.0, rhoFallback = 1.2),
        )
        val s = app.painmeter.core.RideSession(settings)
        val journal = mutableListOf<app.painmeter.core.JournalEntry>()
        journal += s.start(t0)
        var lap = 0
        rows.forEach { r ->
            val t = r[3].toLong()
            journal += s.onFix(app.painmeter.core.Fix(t, r[0].toDouble(), r[1].toDouble(), r[2].toDouble(), r[4].toDouble(), 3.0))
            while (lap < laps.size && t0 + (laps[lap] * 1000).toLong() == t) { journal += s.lap(t); lap++ }
        }
        journal += s.stop(rows.last()[3].toLong())

        val input = RideCalibration.input(journal, settings)!!
        val r = CoastDown.run(input, 80.0, 0.004, forceFixed = false)
        assertEquals("laps", r.source)
        assertEquals("paired", r.method)
        val f = r.fit!!
        assertEquals(0.30, f.cda, 0.01)
        assertEquals(0.005, f.crr, 0.0005)
        kotlin.test.assertTrue(RideCalibration.warnings(r).isEmpty(), "warnings ${RideCalibration.warnings(r)}")
    }
}
