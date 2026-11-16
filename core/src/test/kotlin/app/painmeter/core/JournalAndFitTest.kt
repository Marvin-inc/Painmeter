package app.painmeter.core

import app.painmeter.core.TestRides.Leg
import com.garmin.fit.Decode
import com.garmin.fit.LapMesg
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import java.io.File
import java.io.FileInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalAndFitTest {

    private fun tmp(name: String) = File.createTempFile("painmeter", name).also { it.deleteOnExit() }

    @Test
    fun journalRoundTripAndTruncatedLastLine() {
        val fixes = TestRides.fixes(Leg(30, 8.0), Leg(10, 0.0), Leg(30, 8.0))
        val (_, journal) = TestRides.ride(fixes, lapsAtSecond = setOf(20))
        val file = tmp(".txt")
        JournalWriter(file).use { w -> journal.forEach(w::append) }
        // Simulate a crash in the middle of writing one more line.
        file.appendText("S,17632800999")

        val read = JournalReader.read(file)
        assertEquals(journal.size, read.size)
        assertEquals(journal.map { it::class }, read.map { it::class })
        val t = fixes.last().timeMs
        val original = RideSession.replay(journal).snapshot(t)
        val reloaded = RideSession.replay(read).snapshot(t)
        // Positions are stored with 7 decimals (about 1 cm), so distances match to a few cm.
        assertEquals(original.distanceM, reloaded.distanceM, 0.1)
        assertEquals(original.copy(distanceM = 0.0, avgSpeedMps = 0.0, lapDistanceM = 0.0, lastLap = null),
            reloaded.copy(distanceM = 0.0, avgSpeedMps = 0.0, lapDistanceM = 0.0, lastLap = null))
    }

    @Test
    fun unknownLineIsSkipped() {
        assertNull(JournalCodec.decode("XYZ,1,2"))
        assertNull(JournalCodec.decode("S,1,2"))
    }

    @Test
    fun fitFileIsValidAndMatchesTheRide() {
        val fixes = TestRides.fixes(Leg(120, 9.0, 0.2), Leg(30, 0.0), Leg(120, 7.0, 0.4))
        val (s, journal) = TestRides.ride(fixes, lapsAtSecond = setOf(60))
        val file = tmp(".fit")
        FitExporter.export(journal, file)

        assertTrue(Decode().checkFileIntegrity(FileInputStream(file)), "FIT integrity check")

        val records = mutableListOf<RecordMesg>()
        val laps = mutableListOf<LapMesg>()
        val sessions = mutableListOf<SessionMesg>()
        val broadcaster = MesgBroadcaster(Decode())
        broadcaster.addListener(com.garmin.fit.RecordMesgListener { records += it })
        broadcaster.addListener(com.garmin.fit.LapMesgListener { laps += it })
        broadcaster.addListener(com.garmin.fit.SessionMesgListener { sessions += it })
        FileInputStream(file).use { broadcaster.run(it) }

        val snap = s.snapshot(fixes.last().timeMs)
        // Records only while recording: 3 s before auto-pause are recorded, the rest of the stop is not.
        assertTrue(records.size in 240..247, "records ${records.size}")
        assertEquals(s.completedLaps.size, laps.size)
        assertEquals(1, sessions.size)
        assertEquals(snap.distanceM, sessions[0].totalDistance.toDouble(), 0.5)
        assertEquals(snap.rideTimeMs / 1000.0, sessions[0].totalTimerTime.toDouble(), 0.01)
        assertEquals(snap.distanceM, records.last().distance.toDouble(), 0.5)
        assertEquals(fixes[0].lat, records[0].positionLat * 180.0 / 2147483648.0, 1e-6)
    }
}
