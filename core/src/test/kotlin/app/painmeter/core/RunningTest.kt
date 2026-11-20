package app.painmeter.core

import app.painmeter.core.TestRides.Leg
import app.painmeter.core.run.CadenceMeter
import app.painmeter.core.run.Gap
import app.painmeter.core.run.Sport
import com.garmin.fit.Decode
import com.garmin.fit.LapMesg
import com.garmin.fit.LapTrigger
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import java.io.File
import java.io.FileInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunningTest {

    /** Runs fixes through a running session, with [stepsPerS] steps a second from the step detector. */
    private fun run(
        fixes: List<Fix>, stepsPerS: Double = 0.0, settings: RideSettings = RideSettings(),
    ): Pair<RideSession, List<JournalEntry>> {
        val s = RideSession(settings)
        val journal = mutableListOf<JournalEntry>()
        journal += s.start(TestRides.T0, Sport.RUN)
        var steps = 0.0
        fixes.forEach { f ->
            journal += s.onFix(f)
            if (stepsPerS > 0 && (f.speedMps ?: 0.0) > 0) {
                steps += stepsPerS
                journal += s.onSteps(f.timeMs, steps.toLong())
            }
        }
        journal += s.stop(fixes.last().timeMs)
        return s to journal
    }

    @Test
    fun gapFactorFollowsMinetti() {
        assertEquals(1.0, Gap.factor(0.0), 1e-9)
        // C(0.10) = 5.97 J/kg/m, C(-0.10) = 2.15 J/kg/m (Minetti 2002).
        assertEquals(5.97 / 3.6, Gap.factor(10.0), 0.01)
        assertEquals(2.15 / 3.6, Gap.factor(-10.0), 0.01)
        assertTrue(Gap.factor(20.0) > Gap.factor(10.0))
        // Clamped beyond ±45 %.
        assertEquals(Gap.factor(45.0), Gap.factor(80.0), 1e-9)
    }

    @Test
    fun autoLapEveryKilometre() {
        // 10 min at 3.5 m/s (4:46/km) = 2100 m: laps at 1 km and 2 km, then the end.
        val (s, journal) = run(TestRides.fixes(Leg(600, 3.5)))
        val laps = s.completedLaps
        assertEquals(3, laps.size)
        assertEquals(listOf(true, true, false), laps.map { it.auto })
        assertTrue(laps.take(2).all { it.distanceM in 1000.0..1004.0 }, laps.map { it.distanceM }.toString())
        assertEquals(2, journal.count { it is JournalEntry.Lap && it.auto })
    }

    @Test
    fun noAutoLapOnTheBike() {
        val (s, _) = TestRides.ride(TestRides.fixes(Leg(600, 3.5)))
        assertEquals(1, s.completedLaps.size)
        assertEquals(Sport.BIKE, s.snapshot(TestRides.T0).sport)
    }

    @Test
    fun walkingUpATrailDoesNotPause() {
        // 2 min at 0.8 m/s (2.9 km/h), walking up a steep trail, must not pause a run (it would
        // not resume on the bike, below 4 km/h); standing still for 10 s does.
        val fixes = TestRides.fixes(Leg(60, 3.0), Leg(120, 0.8), Leg(10, 0.0), Leg(60, 3.0))
        val (_, journal) = run(fixes)
        val pauses = journal.filterIsInstance<JournalEntry.Pause>()
        assertEquals(1, pauses.size)
        assertEquals(fixes[180 + 5].timeMs, pauses[0].timeMs) // 5 s after stopping
    }

    @Test
    fun paceGapAndCadence() {
        // 5 min at 3.33 m/s (5:00/km) on a 10 % slope, 2.9 steps a second (174 spm).
        val fixes = TestRides.fixes(Leg(300, 10 / 3.0)).map { it.copy(terrainGradePercent = 10.0) }
        val s = RideSession()
        s.start(TestRides.T0, Sport.RUN)
        var steps = 0.0
        fixes.forEach { f -> s.onFix(f); steps += 2.9; s.onSteps(f.timeMs, steps.toLong()) }
        val snap = s.snapshot(fixes.last().timeMs)
        assertEquals(10 / 3.0, snap.paceSpeedMps!!, 0.01)
        assertEquals(10 / 3.0 * Gap.factor(10.0), snap.gapSpeedMps!!, 0.05)
        assertEquals(10 / 3.0 * Gap.factor(10.0), snap.avgGapSpeedMps!!, 0.05)
        assertEquals(174.0, snap.cadenceSpm!!, 7.0)
        assertEquals(174.0, snap.avgCadenceSpm!!, 3.0)
        // No bike power when running.
        assertNull(snap.avgPower)
        assertNull(snap.lapAvgPower)
    }

    @Test
    fun cadenceIsNullWithoutSteps() {
        val m = CadenceMeter()
        assertNull(m.spm(0))
        m.add(0, 0); m.add(10_000, 30)
        assertNull(m.spm(16_000)) // nothing for more than 5 s
        assertEquals(180.0, m.spm(10_000)!!, 1e-9)
    }

    @Test
    fun descentIsCounted() {
        val (s, _) = run(TestRides.fixes(Leg(200, 3.0, 0.5), Leg(200, 3.0, -0.5)))
        val snap = s.snapshot(TestRides.T0 + 400_000)
        assertEquals(100.0, snap.elevationGainM, 2.0)
        assertEquals(100.0, snap.elevationLossM, 2.0)
    }

    @Test
    fun journalKeepsSportAutoLapsAndSteps() {
        val (_, journal) = run(TestRides.fixes(Leg(400, 3.0)), stepsPerS = 2.8)
        val text = journal.map(JournalCodec::encode)
        assertTrue(text.first().endsWith(",RUN"))
        assertTrue(text.any { it.startsWith("LAP,") && it.endsWith(",A") })
        assertTrue(text.any { it.startsWith("STEPS,") })
        val back = text.mapNotNull(JournalCodec::decode)
        assertEquals(journal, back.map { if (it is JournalEntry.Sample) journal.first { j -> j.timeMs == it.timeMs && j is JournalEntry.Sample } else it })
        val replayed = RideSession.replay(back)
        assertEquals(Sport.RUN, replayed.sport)
        assertEquals(2, replayed.completedLaps.size)
        // Rides recorded before running existed have no sport: they are bike rides.
        assertEquals(Sport.BIKE, (JournalCodec.decode("START,1") as JournalEntry.Start).sport)
    }

    @Test
    fun fitFileIsARun() {
        val fixes = TestRides.fixes(Leg(400, 3.0))
        val (s, journal) = run(fixes, stepsPerS = 2.8)
        val file = File.createTempFile("painmeter", ".fit").also { it.deleteOnExit() }
        FitExporter.export(journal, file)
        assertTrue(Decode().checkFileIntegrity(FileInputStream(file)))

        val records = mutableListOf<RecordMesg>()
        val laps = mutableListOf<LapMesg>()
        val sessions = mutableListOf<SessionMesg>()
        val b = MesgBroadcaster(Decode())
        b.addListener(com.garmin.fit.RecordMesgListener { records += it })
        b.addListener(com.garmin.fit.LapMesgListener { laps += it })
        b.addListener(com.garmin.fit.SessionMesgListener { sessions += it })
        FileInputStream(file).use { b.run(it) }

        assertEquals(com.garmin.fit.Sport.RUNNING, sessions[0].sport)
        assertEquals(s.completedLaps.size, laps.size)
        assertEquals(LapTrigger.DISTANCE, laps[0].lapTrigger)
        assertTrue(records.all { it.power == null }, "no power in a run")
        // 168 steps/min = 84 strides/min.
        assertEquals(84.0, sessions[0].avgCadence + sessions[0].avgFractionalCadence.toDouble(), 1.0)
        assertNotNull(records.last().cadence)
    }
}
