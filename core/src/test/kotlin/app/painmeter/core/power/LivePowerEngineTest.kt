package app.painmeter.core.power

import app.painmeter.core.FitExporter
import app.painmeter.core.Fix
import app.painmeter.core.JournalCodec
import app.painmeter.core.JournalEntry
import app.painmeter.core.RideSession
import app.painmeter.core.RideSettings
import app.painmeter.core.elevation.ElevationModel
import app.painmeter.core.elevation.HgtTile
import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionMesgListener
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LivePowerEngineTest {

    private val params = PowerParams(riderKg = 72.0, bikeKg = 8.0, cda = 0.30, crr = 0.004, drivetrainLossPercent = 2.0, rhoFallback = 1.2)
    private val m = 80.0

    /** Steady-state power from the formula, for checking. */
    private fun expected(v: Double, grade: Double, cda: Double = 0.30, rho: Double = 1.2, head: Double = 0.0): Double {
        val th = atan(grade)
        val f = m * Physics.G * sin(th) + m * Physics.G * cos(th) * 0.004 + 0.5 * rho * cda * (v + head) * abs(v + head)
        return f * v / 0.98
    }

    /** Rides north at speed(i) m/s on a slope [grade], DEM elevation, 1 Hz. */
    private fun run(
        engine: LivePowerEngine,
        seconds: Int,
        grade: Double = 0.0,
        accuracy: Double = 4.0,
        speed: (Int) -> Double,
    ): List<PowerState> {
        var lat = 45.0
        var d = 0.0
        return (0 until seconds).map { i ->
            val v = speed(i)
            d += v
            lat += v / 111_195.0
            engine.onInput(PowerInput(1_000_000L + i * 1000L, lat, 5.7, v, 200 + grade * d, ElevationSource.DEM, accuracy))
        }
    }

    @Test
    fun steadyFlatMatchesTheFormula() {
        val s = run(LivePowerEngine(params), 60) { 9.0 }.last()
        assertEquals(expected(9.0, 0.0), s.power10s!!, 0.5)
        assertEquals(expected(9.0, 0.0), s.power30s!!, 0.5)
        assertTrue(s.reliable)
    }

    @Test
    fun steadyClimbUsesTheGradient() {
        val s = run(LivePowerEngine(params), 120, grade = 0.06) { 4.0 }.last()
        assertEquals(6.0, s.gradePercent!!, 0.05)
        assertEquals(expected(4.0, 0.06), s.power10s!!, 2.0)
    }

    @Test
    fun accelerationAddsKineticEnergy() {
        // 20 s at 5 m/s, then 10 s accelerating to 10 m/s.
        val states = run(LivePowerEngine(params), 30) { if (it < 20) 5.0 else 5.0 + (it - 19) * 0.5 }
        val p10 = states.last().power10s!!
        assertTrue(p10 > expected(10.0, 0.0), "accelerating costs more than riding at 10 m/s: $p10")
    }

    @Test
    fun noValueUntilTheWindowIsFull() {
        val states = run(LivePowerEngine(params), 12) { 8.0 }
        assertNull(states[8].power10s)
        assertTrue(states[11].power10s != null)
        assertNull(states[11].power30s)
    }

    @Test
    fun onAWheelReducesAeroAndMarksUnreliable() {
        val e = LivePowerEngine(params)
        e.setDrafting(true)
        val s = run(e, 40) { 11.0 }.last()
        assertEquals(expected(11.0, 0.0, cda = 0.30 * 0.65), s.power10s!!, 0.5)
        assertFalse(s.reliable)
    }

    @Test
    fun headwindAtRiderHeight() {
        val e = LivePowerEngine(params)
        // 5 m/s from the north, rider heading north: 0.6 × 5 = 3 m/s headwind.
        e.setWeather(WeatherNow(5.0, 0.0, 15.0, 0.0, 101_325.0))
        val s = run(e, 60) { 8.0 }.last()
        assertEquals(3.0, s.headwindMps, 0.05)
        val rho = Physics.airDensity(15.0, 101_325.0, 0.0, 200.0)
        assertEquals(rho, s.rho, 1e-9)
        assertEquals(expected(8.0, 0.0, rho = rho, head = 3.0), s.power10s!!, 1.0)
    }

    @Test
    fun poorGpsIsUnreliable() {
        assertFalse(run(LivePowerEngine(params), 20, accuracy = 20.0) { 8.0 }.last().reliable)
    }

    @Test
    fun airDensityMatchesTheWebTool() {
        // Value from the web tool's README example: 25 °C, 40 % RH, 1800 m → about 0.96.
        assertEquals(0.96, Physics.airDensity(25.0, 101_325.0, 40.0, 1800.0), 0.01)
    }

    @Test
    fun liveAverageIsCloseToThePostRideModelOnTheGoldenRide() {
        // SPEC §12 test 2 on a synthetic ride: live (causal) vs web tool (centred), average power.
        val lines = javaClass.getResource("/golden/device_speed_weather.in.csv")!!.readText().lines().filter { it.isNotBlank() }
        val rows = lines.drop(2).map { it.split(',') }
        val pts = rows.map { PostRideModel.Point(it[0].toDouble(), it[1].toDouble(), it[2].toDoubleOrNull(), it[3].toLong(), it[4].toDoubleOrNull()) }
        val post = PostRideModel.process(pts, PostRideModel.Params(72.5, 8.2, 0.31, 0.0042, 1.2, 2.0, 15.0))

        val e = LivePowerEngine(PowerParams(72.5, 8.2, 0.31, 0.0042, 2.0, rhoFallback = 1.2))
        val stats = PowerStats()
        var ele = pts.first().ele ?: 0.0
        pts.forEach { p ->
            ele = p.ele ?: ele
            val s = e.onInput(PowerInput(p.timeMs!!, p.lat, p.lon, p.speed, ele, ElevationSource.DEM, 4.0))
            s.power1Hz?.let { stats.add(it, s.power30s) }
        }
        val diff = abs(stats.average!! - post.avgPower) / post.avgPower
        assertTrue(diff < 0.10, "live ${stats.average} vs post-ride ${post.avgPower}")
    }

    // ---------------------------------------------------------------- elevation tiles

    private fun tile(size: Int, value: (row: Int, col: Int) -> Int): HgtTile {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d -> for (r in 0 until size) for (c in 0 until size) d.writeShort(value(r, c)) }
        return HgtTile.read(out.toByteArray().inputStream(), "N45E005")
    }

    @Test
    fun hgtBilinearAndVoids() {
        // Elevation rises 1 m per column eastwards, 2 m per row southwards.
        val t = tile(11) { r, c -> 100 + c + 2 * r }
        assertEquals(100.0, t.elevation(46.0, 5.0)!!, 1e-9)       // north-west corner
        assertEquals(130.0, t.elevation(45.0, 6.0)!!, 1e-9)       // south-east corner
        assertEquals(100 + 2.5 + 2 * 5.0, t.elevation(45.5, 5.25)!!, 1e-9)
        assertNull(t.elevation(44.9, 5.5))
        val holes = tile(11) { r, c -> if (r == 5 && c == 5) HgtTile.VOID else 100 }
        assertEquals(100.0, holes.elevation(45.5, 5.5)!!, 1e-9)
    }

    @Test
    fun tileNamesAndUrls() {
        assertEquals("N45E005", HgtTile.nameFor(45.2, 5.7))
        assertEquals("S34W071", HgtTile.nameFor(-33.4, -70.6))
        assertEquals(-34 to -71, HgtTile.cornerOf("S34W071"))
        assertEquals("https://s3.amazonaws.com/elevation-tiles-prod/skadi/N45/N45E005.hgt.gz", HgtTile.url("N45E005"))
        assertEquals(listOf("N44E005", "N44E006", "N45E005", "N45E006"), HgtTile.tilesCovering(44.5, 5.5, 45.5, 6.5))
        var loads = 0
        val model = ElevationModel({ loads++; tile(3) { _, _ -> 250 } })
        repeat(5) { model.elevation(45.5, 5.5) }
        assertEquals(1, loads)
        assertEquals(250.0, model.elevation(45.5, 5.5)!!, 1e-9)
    }

    // ---------------------------------------------------------------- session, journal, FIT

    @Test
    fun journalKeepsDemDraftAndWeather() {
        val entries = listOf(
            JournalEntry.Sample(Fix(1, 45.0, 5.0, 210.0, 8.0, 4.0, demAltitudeM = 205.5)),
            JournalEntry.Draft(2, true),
            JournalEntry.Weather(3, WeatherNow(4.5, 270.0, 18.5, 60.0, 101_200.0)),
        )
        entries.forEach { assertEquals(it, JournalCodec.decode(JournalCodec.encode(it))) }
        // Phase 1 lines (7 fields) still read.
        assertNull((JournalCodec.decode("S,1,45.0,5.0,210.0,8.0,4.0") as JournalEntry.Sample).fix.demAltitudeM)
    }

    @Test
    fun rideRecordsPowerIntoTheFitFile() {
        val s = RideSession(RideSettings(power = params.copy(ftpW = 260)))
        val journal = mutableListOf<JournalEntry>()
        journal += s.start(0)
        var lat = 45.0
        for (i in 1..300) {
            lat += 9.0 / 111_195.0
            if (i == 150) journal += s.lap(i * 1000L)
            if (i == 200) journal += s.setDraft(i * 1000L, true)
            journal += s.onFix(Fix(i * 1000L, lat, 5.7, 300.0, 9.0, 4.0, demAltitudeM = 200.0))
        }
        journal += s.stop(300_000)
        val snap = s.snapshot(300_000)
        // 163 W solo at 9 m/s on the flat, less for the last 100 s on a wheel.
        assertTrue(snap.avgPower!! in 130.0..163.0, "avg ${snap.avgPower}")
        assertEquals(2, s.completedLaps.size)
        assertTrue(s.completedLaps[1].avgPower!! < s.completedLaps[0].avgPower!!, "drafting lowers lap 2")

        val file = File.createTempFile("painmeter", ".fit").also { it.deleteOnExit() }
        FitExporter.export(journal, file, RideSettings(power = params.copy(ftpW = 260)))
        assertTrue(Decode().checkFileIntegrity(FileInputStream(file)))
        val records = mutableListOf<RecordMesg>()
        val sessions = mutableListOf<SessionMesg>()
        val b = MesgBroadcaster(Decode())
        b.addListener(RecordMesgListener { records += it })
        b.addListener(SessionMesgListener { sessions += it })
        FileInputStream(file).use { b.run(it) }
        val withPower = records.filter { it.power != null }
        assertTrue(withPower.size > 280, "records with power: ${withPower.size}")
        assertEquals(snap.avgPower!!, sessions[0].avgPower.toDouble(), 1.0)
        assertEquals(260, sessions[0].thresholdPower.toInt())
        val draft = records.last().developerFields.first { it.name == "draft_state" }
        assertEquals(1, draft.getIntegerValue(0))
        assertEquals(200.0, records.last().developerFields.first { it.name == "dem_altitude" }.getFloatValue(0).toDouble(), 0.01)
    }
}
