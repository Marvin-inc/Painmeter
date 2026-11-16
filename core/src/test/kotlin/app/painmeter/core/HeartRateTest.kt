package app.painmeter.core

import app.painmeter.core.TestRides.Leg
import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesg
import com.garmin.fit.SessionMesgListener
import java.io.File
import java.io.FileInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeartRateTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun parsesEightBitValue() {
        assertEquals(HrMeasurement(152, null), HrMeasurementParser.parse(bytes(0x00, 152)))
    }

    @Test
    fun parsesSixteenBitValueContactAndRr() {
        // flags: 16-bit value, contact detected (bits 1-2 = 3), RR present; 300 bpm; RR 1024 and 512.
        val m = HrMeasurementParser.parse(bytes(0x01 or 0x06 or 0x10, 0x2C, 0x01, 0x00, 0x04, 0x00, 0x02))!!
        assertEquals(300, m.bpm)
        assertEquals(true, m.contact)
        assertEquals(listOf(1000, 500), m.rrMs)
    }

    @Test
    fun skipsEnergyExpended() {
        val m = HrMeasurementParser.parse(bytes(0x08 or 0x10, 140, 0x10, 0x27, 0x00, 0x03))!!
        assertEquals(140, m.bpm)
        assertEquals(listOf(750), m.rrMs)
    }

    @Test
    fun rejectsShortValues() {
        assertNull(HrMeasurementParser.parse(bytes(0x01, 0x10)))
        assertNull(HrMeasurementParser.parse(bytes()))
    }

    @Test
    fun zones() {
        val z = HrZones(listOf(120, 140, 158, 172))
        assertEquals(listOf(1, 1, 2, 3, 4, 4, 5), listOf(90, 120, 121, 150, 159, 172, 173).map(z::zoneOf))
        assertEquals(listOf(111, 130, 148, 167), HrZones.fromMaxHr(185).upperBounds)
    }

    private val zones = HrZones(listOf(120, 140, 158, 172))

    /** A ride with one HR reading per second: [bpmAt] gives the bpm for second i. */
    private fun rideWithHr(fixes: List<Fix>, lapAt: Int? = null, bpmAt: (Int) -> Int): Pair<RideSession, List<JournalEntry>> {
        val s = RideSession(RideSettings(hrZones = zones))
        val j = mutableListOf<JournalEntry>()
        j += s.start(TestRides.T0)
        fixes.forEachIndexed { i, f ->
            j += s.onHeartRate(f.timeMs - 300, bpmAt(i))
            j += s.onFix(f)
            if (lapAt == i + 1) j += s.lap(f.timeMs)
        }
        j += s.stop(fixes.last().timeMs)
        return s to j
    }

    @Test
    fun averageMaxAndTimeInZones() {
        val fixes = TestRides.fixes(Leg(100, 8.0))
        val (s, _) = rideWithHr(fixes, lapAt = 50) { if (it < 50) 130 else 165 }
        val snap = s.snapshot(fixes.last().timeMs)
        assertEquals(165, snap.maxHr)
        assertEquals(148.0, snap.avgHr!!.toDouble(), 1.0)
        assertEquals(listOf(0L, 50_000L, 0L, 50_000L, 0L), snap.hrZoneMs)
        val laps = s.completedLaps
        assertEquals(listOf(130, 165), laps.map { it.avgHr })
    }

    @Test
    fun heartRateWhilePausedIsNotCounted() {
        val fixes = TestRides.fixes(Leg(30, 8.0), Leg(60, 0.0), Leg(30, 8.0))
        val (s, _) = rideWithHr(fixes) { if (it in 33..89) 100 else 150 }
        val snap = s.snapshot(fixes.last().timeMs)
        // Readings while auto-paused (about 57 s at 100 bpm) are left out of the average.
        assertTrue(snap.avgHr!! >= 145, "avg ${snap.avgHr}")
    }

    @Test
    fun staleHeartRateIsNotShown() {
        val fixes = TestRides.fixes(Leg(20, 8.0))
        val s = RideSession(RideSettings(hrZones = zones))
        s.start(TestRides.T0)
        s.onHeartRate(TestRides.T0 + 1000, 150)
        fixes.forEach { s.onFix(it) }
        assertNull(s.snapshot(fixes.last().timeMs).hrBpm)
        assertEquals(150, s.snapshot(TestRides.T0 + 4000).hrBpm)
        assertEquals(3, s.snapshot(TestRides.T0 + 4000).hrZone)
    }

    @Test
    fun speedIsDerivedWhenTheGpsGivesNone() {
        val fixes = TestRides.fixes(Leg(20, 8.0)).map { it.copy(speedMps = null) }
        val s = RideSession()
        s.start(TestRides.T0)
        fixes.forEach { s.onFix(it) }
        assertEquals(8.0, s.snapshot(fixes.last().timeMs).speedMps!!, 0.01)
    }

    @Test
    fun heartRateGoesIntoTheFitFile() {
        val fixes = TestRides.fixes(Leg(120, 8.0))
        val (s, journal) = rideWithHr(fixes) { 120 + it / 4 }
        val file = File.createTempFile("painmeter", ".fit").also { it.deleteOnExit() }
        FitExporter.export(journal, file, RideSettings(hrZones = zones))

        val records = mutableListOf<RecordMesg>()
        val sessions = mutableListOf<SessionMesg>()
        val b = MesgBroadcaster(Decode())
        b.addListener(RecordMesgListener { records += it })
        b.addListener(SessionMesgListener { sessions += it })
        FileInputStream(file).use { b.run(it) }

        assertTrue(records.all { it.heartRate != null })
        assertEquals(120, records.first().heartRate.toInt())
        val snap = s.snapshot(fixes.last().timeMs)
        assertEquals(snap.avgHr, sessions[0].avgHeartRate.toInt())
        assertEquals(snap.maxHr, sessions[0].maxHeartRate.toInt())
        assertEquals(snap.hrZoneMs.sum() / 1000.0, (0 until 5).sumOf { sessions[0].getTimeInHrZone(it).toDouble() }, 0.01)
    }
}
