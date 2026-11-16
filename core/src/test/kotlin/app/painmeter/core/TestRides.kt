package app.painmeter.core

/** Synthetic rides heading north along a meridian, one fix per second. */
object TestRides {
    const val T0 = 1_763_280_000_000L // 2025-11-16T08:00:00Z
    private const val METRES_PER_DEG_LAT = 111_194.93

    data class Leg(val seconds: Int, val speedMps: Double, val climbMps: Double = 0.0)

    fun fixes(vararg legs: Leg, startLat: Double = 45.0, lon: Double = 5.7, startAlt: Double = 200.0): List<Fix> {
        val out = mutableListOf<Fix>()
        var t = T0
        var lat = startLat
        var alt = startAlt
        for (leg in legs) repeat(leg.seconds) {
            t += 1000
            lat += leg.speedMps / METRES_PER_DEG_LAT
            alt += leg.climbMps
            out += Fix(t, lat, lon, alt, leg.speedMps, 4.0)
        }
        return out
    }

    /** Runs fixes through a live session, returning it and its journal. */
    fun ride(fixes: List<Fix>, settings: RideSettings = RideSettings(), lapsAtSecond: Set<Int> = emptySet()): Pair<RideSession, List<JournalEntry>> {
        val s = RideSession(settings)
        val journal = mutableListOf<JournalEntry>()
        journal += s.start(T0)
        fixes.forEachIndexed { i, f ->
            journal += s.onFix(f)
            if ((i + 1) in lapsAtSecond) journal += s.lap(f.timeMs)
        }
        journal += s.stop(fixes.last().timeMs)
        return s to journal
    }
}
