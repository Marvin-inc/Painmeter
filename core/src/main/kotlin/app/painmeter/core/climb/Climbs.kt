package app.painmeter.core.climb

import app.painmeter.core.route.Route
import kotlin.math.max
import kotlin.math.min

/** Thresholds of SPEC §7.1 and §7.3, editable in Parameters → Climbs. */
data class ClimbSettings(
    val minGradePercent: Double = 3.0,
    val minLengthM: Double = 500.0,
    val minGainM: Double = 25.0,
    /** Two climbs separated by less than this (flat or descent) are one. */
    val mergeGapM: Double = 300.0,
    /** Live: the gradient must stay above the threshold over this distance… */
    val confirmDistanceM: Double = 150.0,
    /** …with at least this gain, before a climb is announced. */
    val confirmGainM: Double = 10.0,
    /** Live: the climb ends after this distance below the threshold… */
    val endFlatM: Double = 200.0,
    /** …or this descent from its highest point. */
    val endDropM: Double = 10.0,
    val autoSwitch: Boolean = true,
)

/** A climb on a route: where it starts and ends along the route. */
data class RouteClimb(
    val index: Int,
    val startM: Double,
    val endM: Double,
    val startEleM: Double,
    val endEleM: Double,
) {
    val lengthM: Double get() = endM - startM
    val gainM: Double get() = endEleM - startEleM
    val avgGradePercent: Double get() = if (lengthM > 0) gainM / lengthM * 100 else 0.0
}

/**
 * The route's elevation every [STEP_M] metres, smoothed over 100 m (centred: allowed, the whole
 * route is known). Elevation comes from the GPX file, or from the elevation tiles via [demLookup]
 * where the file has none.
 */
class RouteProfile(val route: Route, demLookup: ((Double, Double) -> Double?)? = null) {
    val elevations: DoubleArray
    val hasElevation: Boolean

    init {
        val n = (route.lengthM / STEP_M).toInt() + 1
        val raw = arrayOfNulls<Double>(n)
        for (i in 0 until n) {
            val p = route.pointAt(i * STEP_M)
            raw[i] = demLookup?.invoke(p.lat, p.lon) ?: p.ele
        }
        hasElevation = raw.count { it != null } > n / 2
        // Fill gaps with the nearest known value, then smooth.
        var last = raw.firstOrNull { it != null } ?: 0.0
        val filled = DoubleArray(n) { i -> (raw[i] ?: last).also { last = it } }
        val half = (50 / STEP_M).toInt()
        elevations = DoubleArray(n) { i ->
            val a = max(0, i - half)
            val b = min(n - 1, i + half)
            var s = 0.0
            for (j in a..b) s += filled[j]
            s / (b - a + 1)
        }
    }

    fun elevationAt(distanceM: Double): Double {
        val x = (distanceM / STEP_M).coerceIn(0.0, (elevations.size - 1).toDouble())
        val i = x.toInt().coerceAtMost(elevations.size - 2).coerceAtLeast(0)
        if (elevations.size < 2) return elevations.firstOrNull() ?: 0.0
        return elevations[i] + (elevations[i + 1] - elevations[i]) * (x - i)
    }

    /** Gradient (%) around a distance, over 50 m either side. */
    fun gradeAt(distanceM: Double): Double {
        val a = (distanceM - 50).coerceAtLeast(0.0)
        val b = (distanceM + 50).coerceAtMost(route.lengthM)
        return if (b - a < 20) 0.0 else (elevationAt(b) - elevationAt(a)) / (b - a) * 100
    }

    companion object {
        const val STEP_M = 10.0
    }
}

object ClimbFinder {
    /** Climbs of a route profile, with the rules of SPEC §7.1. */
    fun find(profile: RouteProfile, s: ClimbSettings = ClimbSettings()): List<RouteClimb> {
        if (!profile.hasElevation) return emptyList()
        val e = profile.elevations
        val n = e.size
        val step = RouteProfile.STEP_M
        // Gradient over 100 m around each sample; "up" a bit below the threshold so a climb with a
        // short easier section is not cut in two.
        val k = (50 / step).toInt()
        val up = BooleanArray(n) { i ->
            val a = max(0, i - k)
            val b = min(n - 1, i + k)
            b > a && (e[b] - e[a]) / ((b - a) * step) * 100 >= s.minGradePercent * 0.66
        }
        val runs = mutableListOf<IntRange>()
        var i = 0
        while (i < n) {
            if (!up[i]) { i++; continue }
            var j = i
            while (j + 1 < n && up[j + 1]) j++
            runs += i..j
            i = j + 1
        }
        // Merge runs separated by a short gap that does not lose much height.
        val merged = mutableListOf<IntRange>()
        for (r in runs) {
            val prev = merged.lastOrNull()
            if (prev != null) {
                val gapM = (r.first - prev.last) * step
                val drop = e[prev.last] - (prev.last..r.first).minOf { e[it] }
                if (gapM < s.mergeGapM && drop < 10) {
                    merged[merged.size - 1] = prev.first..r.last
                    continue
                }
            }
            merged += r
        }
        // Start at the lowest point near the start, end at the highest point near the end.
        val climbs = mutableListOf<RouteClimb>()
        for (r in merged) {
            val a = (max(0, r.first - k)..r.first + k).filter { it < n }.minBy { e[it] }
            val b = (max(0, r.last - k)..min(n - 1, r.last + k)).maxBy { e[it] }
            if (b <= a) continue
            val c = RouteClimb(0, a * step, min(b * step, profile.route.lengthM), e[a], e[b])
            if (c.lengthM >= s.minLengthM && c.gainM >= s.minGainM && c.avgGradePercent >= s.minGradePercent) climbs += c
        }
        return climbs.mapIndexed { idx, c -> c.copy(index = idx) }
    }
}

/** One recorded second, as the climb tracker sees it. */
data class ClimbSample(
    val timeMs: Long,
    val distanceM: Double,
    val elevationM: Double,
    val power: Double?,
    val hr: Int?,
)

/** A climb in progress or just finished, with its statistics so far. */
data class ClimbProgress(
    val fromRoute: RouteClimb?,
    val startTimeMs: Long,
    val startDistanceM: Double,
    /** Ridden profile so far: (distance from the climb start, elevation). */
    val ridden: List<Pair<Double, Double>>,
    val elapsedMs: Long,
    val distanceM: Double,
    val gainM: Double,
    val avgPower: Double?,
    val avgHr: Int?,
    val maxGradePercent: Double,
    val finished: Boolean = false,
) {
    val vamMph: Double? get() = if (elapsedMs >= 30_000) gainM / (elapsedMs / 3_600_000.0) else null
    val avgSpeedMps: Double get() = if (elapsedMs > 0) distanceM / (elapsedMs / 1000.0) else 0.0
    val avgGradePercent: Double get() = if (distanceM > 0) gainM / distanceM * 100 else 0.0
}

/**
 * Detects climbs while riding (SPEC §7.3) from the elevation of each recorded second, and builds
 * their statistics from the recorded samples, back to the moved start.
 *
 * With a route the caller uses [startRouteClimb] / [endRouteClimb] instead of the detection.
 */
class LiveClimbDetector(private val s: ClimbSettings = ClimbSettings()) {
    private val samples = ArrayList<ClimbSample>()
    private var current: Int? = null          // index of the climb's start in samples
    private var routeClimb: RouteClimb? = null
    private var topIndex = 0
    private var belowSinceM: Double? = null

    val active: Boolean get() = current != null

    /** Finished climbs, oldest first. */
    val climbs = mutableListOf<ClimbProgress>()

    /**
     * Adds one recorded second. Returns the climb in progress (null when not climbing). When a
     * climb ends, it is added to [climbs] and returned once with `finished = true`.
     */
    fun add(sample: ClimbSample, onRouteClimb: Boolean = false): ClimbProgress? {
        samples += sample
        trim()
        val i = samples.size - 1
        val start = current
        if (start == null) {
            if (!onRouteClimb && routeClimb == null) detectStart(i)
            return current?.let { progress(it, i, false) }
        }
        if (sample.elevationM > samples[topIndex].elevationM) topIndex = i
        if (routeClimb != null) return progress(start, i, false)

        // Live end: a long section below the threshold, or a clear descent from the top.
        val g = gradeBack(i, 50.0)
        if (g != null && g < s.minGradePercent) {
            if (belowSinceM == null) belowSinceM = sample.distanceM
        } else belowSinceM = null
        val flatLong = belowSinceM?.let { sample.distanceM - it >= s.endFlatM } ?: false
        val dropped = samples[topIndex].elevationM - sample.elevationM >= s.endDropM
        if (flatLong || dropped) return finish(start)
        return progress(start, i, false)
    }

    fun startRouteClimb(c: RouteClimb) {
        if (current != null) return
        routeClimb = c
        current = samples.size - 1
        topIndex = samples.size - 1
    }

    fun endRouteClimb(): ClimbProgress? {
        val start = current ?: return null
        return finish(start, toEnd = true).also { routeClimb = null }
    }

    private fun finish(start: Int, toEnd: Boolean = false): ClimbProgress? {
        // The top is moved back to the highest point (SPEC §7.3).
        val end = if (toEnd) samples.size - 1 else topIndex
        val p = progress(start, end, true)
        current = null
        belowSinceM = null
        val keep = routeClimb != null || (p.distanceM >= s.minLengthM && p.gainM >= s.minGainM)
        if (keep) climbs += p
        return if (keep) p else null
    }

    private fun detectStart(i: Int) {
        val g = gradeBack(i, s.confirmDistanceM) ?: return
        val back = indexBack(i, s.confirmDistanceM) ?: return
        if (g < s.minGradePercent) return
        // Move the start back to where the gradient first went above the threshold…
        var j = back
        while (j > 0) {
            val gj = gradeBack(j, 50.0) ?: break
            if (gj < s.minGradePercent) break
            j--
        }
        val low = (j..i).minBy { samples[it].elevationM }
        // …and confirm once enough height has been gained since then.
        if (samples[i].elevationM - samples[low].elevationM < s.confirmGainM) return
        current = low
        topIndex = (low..i).maxBy { samples[it].elevationM }
        belowSinceM = null
    }

    private fun progress(start: Int, end: Int, finished: Boolean): ClimbProgress {
        val a = samples[start]
        val b = samples[end]
        var pSum = 0.0; var pN = 0
        var hSum = 0.0; var hN = 0
        var maxGrade = 0.0
        val ridden = ArrayList<Pair<Double, Double>>()
        var lastD = Double.NEGATIVE_INFINITY
        for (k in start..end) {
            val x = samples[k]
            x.power?.let { pSum += it; pN++ }
            x.hr?.let { hSum += it; hN++ }
            if (x.distanceM - lastD >= 10) { ridden += (x.distanceM - a.distanceM) to x.elevationM; lastD = x.distanceM }
            gradeBack(k, 100.0)?.takeIf { indexBack(k, 100.0)!! >= start }?.let { maxGrade = max(maxGrade, it) }
        }
        return ClimbProgress(
            fromRoute = routeClimb,
            startTimeMs = a.timeMs,
            startDistanceM = a.distanceM,
            ridden = ridden,
            elapsedMs = b.timeMs - a.timeMs,
            distanceM = b.distanceM - a.distanceM,
            gainM = b.elevationM - a.elevationM,
            avgPower = if (pN > 0) pSum / pN else null,
            avgHr = if (hN > 0) Math.round(hSum / hN).toInt() else null,
            maxGradePercent = maxGrade,
            finished = finished,
        )
    }

    /** Index of the last sample at least [m] metres behind sample i. */
    private fun indexBack(i: Int, m: Double): Int? {
        val target = samples[i].distanceM - m
        var j = i
        while (j > 0 && samples[j].distanceM > target) j--
        return if (samples[i].distanceM - samples[j].distanceM >= m * 0.9) j else null
    }

    private fun gradeBack(i: Int, m: Double): Double? {
        val j = indexBack(i, m) ?: return null
        val d = samples[i].distanceM - samples[j].distanceM
        return (samples[i].elevationM - samples[j].elevationM) / d * 100
    }

    /** Keeps memory bounded: only what a climb in progress, or the next detection, needs. */
    private fun trim() {
        val keepFrom = current ?: (samples.size - 2000)
        if (keepFrom > 4000) {
            val drop = keepFrom - 2000
            samples.subList(0, drop).clear()
            current = current?.minus(drop)
            topIndex = (topIndex - drop).coerceAtLeast(0)
        }
    }
}
