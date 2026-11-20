package app.painmeter.core.run

/** What the ride is: road cycling (estimated power) or running (pace, GAP, cadence). SPEC §16. */
enum class Sport { BIKE, RUN }

/** Running settings (SPEC §16.3). Speeds in m/s. */
data class RunSettings(
    /** A lap is closed automatically every this many metres; null = off. */
    val autoLapM: Double? = 1000.0,
    /**
     * Auto-pause for running: much lower than on the bike, so walking up a steep trail (3 km/h)
     * never pauses the timer, but standing at a crossing does.
     */
    val autoPauseBelowMps: Double = 1.0 / 3.6,
    val autoPauseAfterMs: Long = 5_000,
    val autoResumeAboveMps: Double = 2.0 / 3.6,
)

/**
 * Grade-adjusted pace (GAP): the flat speed that costs the same energy as running at speed v on
 * gradient i. Uses the energy cost of running measured by Minetti et al. (2002, J Appl Physiol
 * 93:1039), in J/kg/m:
 *
 *     C(i) = 155.4 i⁵ − 30.4 i⁴ − 43.3 i³ + 46.3 i² + 19.5 i + 3.6      (i = rise / run, −0.45..0.45)
 *
 * GAP speed = v · C(i) / C(0). Uphill it is faster than the real speed, downhill slower. The
 * polynomial is for steady running on a treadmill: on steep descents (below about −10 %) it is
 * more generous than Strava's own GAP, which is fitted on heart-rate data.
 */
object Gap {
    private const val FLAT = 3.6

    fun costJPerKgM(gradePercent: Double): Double {
        val i = (gradePercent / 100).coerceIn(-0.45, 0.45)
        return ((((155.4 * i - 30.4) * i - 43.3) * i + 46.3) * i + 19.5) * i + 3.6
    }

    /** Ratio C(i) / C(0): how much harder (> 1) or easier (< 1) than the flat. */
    fun factor(gradePercent: Double): Double = costJPerKgM(gradePercent) / FLAT
}

/**
 * Smoothed speed for the pace display: GPS Doppler speed averaged over the last [windowMs].
 * Running speed varies less than cycling speed but GPS noise is the same ±0.3 m/s, which is
 * ±20 s/km at 5:00/km: a 10 s average brings it to about ±6 s/km.
 */
class PaceFilter(private val windowMs: Long = 10_000) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    fun add(timeMs: Long, speedMps: Double?) {
        if (speedMps != null && !speedMps.isNaN()) samples.addLast(timeMs to speedMps)
        while (samples.isNotEmpty() && timeMs - samples.first().first > windowMs) samples.removeFirst()
    }

    fun reset() = samples.clear()

    /** Mean speed over the window, null without a reading in it. */
    fun speedMps(nowMs: Long): Double? {
        val recent = samples.filter { nowMs - it.first <= windowMs }
        return if (recent.isEmpty()) null else recent.sumOf { it.second } / recent.size
    }
}

/**
 * Cadence from the phone's step detector. The app records the step total once a second; the
 * cadence is the steps counted over the last [windowMs] (or less at the start), in steps/min.
 */
class CadenceMeter(private val windowMs: Long = 10_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun add(timeMs: Long, totalSteps: Long) {
        samples.addLast(timeMs to totalSteps)
        while (samples.size > 2 && timeMs - samples[1].first >= windowMs) samples.removeFirst()
    }

    fun reset() = samples.clear()

    /** Steps per minute; null when no steps were recorded in the last 5 s (stopped or no sensor). */
    fun spm(nowMs: Long): Double? {
        if (samples.size < 2) return null
        val last = samples.last()
        if (nowMs - last.first > 5_000) return null
        val first = samples.first()
        val dt = (last.first - first.first) / 1000.0
        if (dt < 3) return null
        return ((last.second - first.second).coerceAtLeast(0) / dt * 60).takeIf { it in 0.0..400.0 }
    }
}

/** Running totals for a ride or a lap: GAP and cadence averages. */
class RunStats {
    /** Flat-equivalent distance: Σ distance × GAP factor. */
    var equivalentM = 0.0
        private set
    var distanceM = 0.0
        private set
    var steps = 0L
        private set
    /** Time with steps counted, for the average cadence (not the time stood still). */
    var stepTimeMs = 0L
        private set

    fun addDistance(m: Double, gradePercent: Double?) {
        distanceM += m
        equivalentM += m * (gradePercent?.let(Gap::factor) ?: 1.0)
    }

    fun addSteps(n: Long, overMs: Long) {
        if (n <= 0 || overMs <= 0) return
        steps += n
        stepTimeMs += overMs
    }

    /** Average GAP speed over [timerMs], m/s. */
    fun gapSpeedMps(timerMs: Long): Double? = if (timerMs > 0 && distanceM > 0) equivalentM / (timerMs / 1000.0) else null

    val avgCadenceSpm: Double? get() = if (stepTimeMs >= 5_000) steps / (stepTimeMs / 60_000.0) else null
}
