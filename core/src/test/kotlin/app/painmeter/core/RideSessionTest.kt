package app.painmeter.core

import app.painmeter.core.TestRides.Leg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RideSessionTest {

    @Test
    fun distanceTimeAndAverageSpeed() {
        val (s, _) = TestRides.ride(TestRides.fixes(Leg(600, 8.0)))
        val snap = s.snapshot(TestRides.T0 + 600_000)
        assertEquals(RideStatus.FINISHED, snap.status)
        // 600 fixes = 599 intervals: the first fix has no previous position.
        assertEquals(4792.0, snap.distanceM, 1.0)
        assertEquals(600_000L, snap.rideTimeMs)
        assertEquals(4792.0 / 600, snap.avgSpeedMps, 0.01)
        assertEquals(8.0, snap.maxSpeedMps, 1e-9)
    }

    @Test
    fun autoPauseStopsTheTimerAndResumes() {
        // 60 s riding, 120 s stopped, 60 s riding.
        val fixes = TestRides.fixes(Leg(60, 8.0), Leg(120, 0.0), Leg(60, 8.0))
        val (s, journal) = TestRides.ride(fixes)
        val pauses = journal.filterIsInstance<JournalEntry.Pause>()
        val resumes = journal.filterIsInstance<JournalEntry.Resume>()
        assertEquals(1, pauses.size)
        assertEquals(1, resumes.size)
        assertTrue(pauses[0].auto && resumes[0].auto)
        val ride = s.snapshot(fixes.last().timeMs).rideTimeMs / 1000
        // Paused 3 s after slowing down, resumed on the first fast fix.
        assertEquals(123L, ride)
        assertEquals(59 * 8.0 + 60 * 8.0, s.snapshot(fixes.last().timeMs).distanceM, 1.0)
    }

    @Test
    fun manualPauseIsNotResumedAutomatically() {
        val fixes = TestRides.fixes(Leg(10, 8.0), Leg(10, 8.0))
        val s = RideSession()
        s.start(TestRides.T0)
        fixes.take(10).forEach { s.onFix(it) }
        s.pause(fixes[9].timeMs)
        fixes.drop(10).forEach { s.onFix(it) }
        assertEquals(RideStatus.PAUSED, s.status)
        // Distance ridden while manually paused is not counted.
        assertEquals(72.0, s.snapshot(fixes.last().timeMs).distanceM, 1.0)
    }

    @Test
    fun lapsSplitTimeAndDistance() {
        val fixes = TestRides.fixes(Leg(300, 10.0))
        val (s, _) = TestRides.ride(fixes, lapsAtSecond = setOf(100, 200))
        val laps = s.completedLaps
        assertEquals(3, laps.size)
        assertEquals(listOf(100_000L, 100_000L, 100_000L), laps.map { it.timerMs })
        assertEquals(listOf(990.0, 1000.0, 1000.0), laps.map { Math.round(it.distanceM).toDouble() })
        assertEquals(listOf(false, false, true), laps.map { it.endedBySession })
    }

    @Test
    fun lapNumberAndLapTimeInSnapshot() {
        val fixes = TestRides.fixes(Leg(150, 10.0))
        val s = RideSession()
        s.start(TestRides.T0)
        fixes.forEachIndexed { i, f -> s.onFix(f); if (i == 99) s.lap(f.timeMs) }
        val snap = s.snapshot(fixes.last().timeMs)
        assertEquals(2, snap.lapNumber)
        assertEquals(50_000L, snap.lapTimeMs)
        assertEquals(500.0, snap.lapDistanceM, 1.0)
        assertEquals(990.0, snap.lastLap!!.distanceM, 1.0)
    }

    @Test
    fun replayGivesTheSameRide() {
        val fixes = TestRides.fixes(Leg(60, 8.0, 0.3), Leg(30, 0.0), Leg(90, 6.0, 0.5))
        val (live, journal) = TestRides.ride(fixes, lapsAtSecond = setOf(70))
        val replayed = RideSession.replay(journal)
        val t = fixes.last().timeMs
        assertEquals(live.snapshot(t), replayed.snapshot(t))
        assertEquals(live.completedLaps, replayed.completedLaps)
    }

    @Test
    fun inaccurateFixesAreIgnoredForDistance() {
        val good = TestRides.fixes(Leg(20, 8.0))
        val noisy = good.mapIndexed { i, f -> if (i == 10) f.copy(lat = f.lat + 0.01, accuracyM = 80.0) else f }
        val (s, _) = TestRides.ride(noisy)
        assertEquals(152.0, s.snapshot(noisy.last().timeMs).distanceM, 1.0)
    }

    @Test
    fun elevationGainIgnoresNoise() {
        val g = ElevationGain(4.0)
        // ±2 m noise around 100 m: no gain.
        repeat(50) { g.add(if (it % 2 == 0) 98.0 else 102.0) }
        assertEquals(0.0, g.totalM, 1e-9)
        // Steady climb from 100 to 150 m.
        for (h in 100..150) g.add(h.toDouble())
        assertEquals(52.0, g.totalM, 1e-9) // from the 98 m noise floor to 150 m
        // Descend then climb 20 m.
        for (h in 150 downTo 120) g.add(h.toDouble())
        val before = g.totalM
        for (h in 120..140) g.add(h.toDouble())
        assertEquals(20.0, g.totalM - before, 1e-9)
    }
}
