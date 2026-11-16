package app.painmeter.core.calibration

import app.painmeter.core.Geo
import app.painmeter.core.power.PostRideModel
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Coast-down calibration of CdA and Crr: a line-by-line port of the Power-calcualtor web tool
 * (`findLapSegments` … `runCoastDown` in cyclingPowerGenerator.html), checked against it in
 * CoastDownTest.
 */
object CoastDown {
    private const val G = 9.8067
    private const val WHEEL_INERTIA_MASS = 1.5
    private const val MIN_V = 3.0

    /** Everything the calibration needs from a ride. */
    class Input(
        val calib: PostRideModel.CalibSeries,
        val lat: DoubleArray,
        val lon: DoubleArray,
        /** Cadence per point (0 = cranks stopped), null when unknown. */
        val cad: Array<Int?>,
        /** Lap marks as (start, end) seconds from the first point. */
        val laps: List<Pair<Double, Double>>,
        val hasEle: Boolean,
        val hasWind: Boolean,
    )

    class Segment(val a: Int, val b: Int) {
        var slope = 0.0
        var dir = 0
        var bearing = 0.0
    }

    class Window(
        val seg: Int, val q: Double, val f: Double, var used: Boolean, val q0: Double,
        val rho: Double, val vm: Double, val fi: Double, val dir: Int,
    ) {
        var px = 0.0
        var py = 0.0
    }

    data class Fit(
        val cda: Double, val crr: Double, val sigma: Double, val n: Int,
        val seCda: Double, val seCrr: Double?, val r2: Double,
        val wind: Double? = null, val slope: Double? = null,
        val seWind: Double? = null, val seSlope: Double? = null,
        val res: List<Double>,
    )

    class Result(
        val segs: List<Segment>,
        val hasCad: Boolean,
        /** "laps", "cadence" or "slowdown": how the coasting runs were found. */
        val source: String,
        val lapsAvailable: Boolean,
        val pairedAvailable: Boolean,
        val refBearing: Double?,
        val wins: List<Window>,
        val fit: Fit?,
        /** "free" (CdA and Crr fitted) or "fixed" (Crr kept). */
        val mode: String?,
        /** "paired" (both directions, wind and slope solved) or "single". */
        val method: String?,
        /** Why Crr was kept: "narrow", "odd", "paired-failed", or null. */
        val fallback: String?,
        /** "none" (no run found) or "few" (not enough data), or null. */
        val error: String?,
        val totalTimeS: Double = 0.0,
        val vMin: Double = 0.0,
        val vMax: Double = 0.0,
    )

    // ------------------------------------------------------------------ runs

    private fun findLapSegments(c: Input, hasCad: Boolean): List<Segment> {
        val t = c.calib.t
        val v = c.calib.v
        val n = t.size
        val segs = mutableListOf<Segment>()
        for ((t0, t1) in c.laps) {
            val dur = t1 - t0
            if (dur < 8 || dur > 300) continue
            var a = 0
            while (a < n && t[a] < t0 + 2) a++
            var b = n - 1
            while (b > a && t[b] > t1 - 1) b--
            while (b > a && v[b] < MIN_V) b--
            if (hasCad) {
                var best: Pair<Int, Int>? = null
                var st = -1
                for (i in a..b + 1) {
                    val on = i <= b && c.cad[i] == 0
                    if (on && st < 0) st = i
                    if (!on && st >= 0) {
                        if (best == null || i - 1 - st > best.second - best.first) best = st to i - 1
                        st = -1
                    }
                }
                if (best == null) continue
                a = best.first; b = best.second
            }
            if (b <= a || t[b] - t[a] < 6) continue
            if (v[a] - v[b] < 1) continue
            segs += Segment(a, b)
        }
        return segs
    }

    private class Found(val segs: List<Segment>, val hasCad: Boolean, val source: String, val lapsAvailable: Boolean)

    private fun findCoastSegments(c: Input, useLaps: Boolean): Found {
        val t = c.calib.t
        val v = c.calib.v
        val n = t.size
        val hasCad = c.cad.count { it != null } > n * 0.5
        val minDur = if (hasCad) 8 else 10
        val lapSegs = findLapSegments(c, hasCad)
        val lapsAvailable = lapSegs.size >= 2
        if (useLaps && lapsAvailable) return Found(lapSegs, hasCad, "laps", lapsAvailable)

        val vSlow = PostRideModel.smoothByTime(v, t, 9.0)
        val aSlow = DoubleArray(n)
        for (i in 1 until n - 1) {
            val dt = t[i + 1] - t[i - 1]
            if (dt > 0) aSlow[i] = (vSlow[i + 1] - vSlow[i - 1]) / dt
        }
        val flag = BooleanArray(n) { i ->
            when {
                v[i] < MIN_V -> false
                hasCad -> c.cad[i] == 0
                else -> aSlow[i] < -0.03 && abs(c.calib.grade[i]) <= 0.03
            }
        }
        for (i in 1 until n - 1) {
            if (flag[i] || !flag[i - 1] || v[i] < MIN_V) continue
            var j = i
            while (j < n && !flag[j] && v[j] >= MIN_V && t[j] - t[i - 1] <= 2) j++
            if (j < n && flag[j]) for (k in i until j) flag[k] = true
        }
        val segs = mutableListOf<Segment>()
        var start = -1
        for (i in 0..n) {
            val on = i < n && flag[i]
            if (on && start < 0) start = i
            if (!on && start >= 0) {
                val trimA = if (hasCad) 2 else 6
                val trimB = if (hasCad) 1 else 4
                var a = start
                var b = i - 1
                while (a < b && t[a] - t[start] < trimA) a++
                while (b > a && t[i - 1] - t[b] < trimB) b--
                if (t[b] - t[a] >= minDur) segs += Segment(a, b)
                start = -1
            }
        }
        return Found(segs, hasCad, if (hasCad) "cadence" else "slowdown", lapsAvailable)
    }

    private fun lineSlope(x: DoubleArray, y: DoubleArray, a: Int, b: Int): Double {
        var n = 0; var sx = 0.0; var sy = 0.0
        for (i in a..b) { n++; sx += x[i]; sy += y[i] }
        val mx = sx / n
        val my = sy / n
        var sxx = 0.0; var sxy = 0.0
        for (i in a..b) { sxx += (x[i] - mx).pow(2); sxy += (x[i] - mx) * (y[i] - my) }
        return if (sxx > 0) sxy / sxx else 0.0
    }

    private fun coastWindows(c: Input, segs: List<Segment>, mass: Double): MutableList<Window> {
        val k = c.calib
        val mEff = mass + WHEEL_INERTIA_MASS
        val ele = k.ele
        val n = k.t.size
        val dist = DoubleArray(n)
        for (i in 1 until n) dist[i] = dist[i - 1] + 0.5 * (k.vRaw[i] + k.vRaw[i - 1]) * (k.t[i] - k.t[i - 1])
        val wins = mutableListOf<Window>()
        segs.forEachIndexed { si, s ->
            s.slope = if (ele != null) lineSlope(dist, ele, s.a, s.b) else 0.0
            var i0 = s.a
            while (i0 < s.b) {
                var j = i0 + 1
                while (j < s.b && k.t[j] - k.t[i0] < 5) j++
                if (k.t[j] - k.t[i0] < 4) break
                val acc = lineSlope(k.t, k.vRaw, i0, j)
                var tm = 0.0; var vm = 0.0
                for (x in i0..j) { tm += k.t[x]; vm += k.vRaw[x] }
                tm /= (j - i0 + 1); vm /= (j - i0 + 1)
                var q = 0.0; var q0 = 0.0; var rho = 0.0
                for (x in i0..j) {
                    val vf = vm + acc * (k.t[x] - tm)
                    val va = vf + k.wind[x]
                    q += 0.5 * k.rho[x] * va * abs(va)
                    q0 += 0.5 * k.rho[x] * vf * vf
                    rho += k.rho[x]
                }
                val cnt = j - i0 + 1
                q /= cnt; q0 /= cnt; rho /= cnt
                val f = -(mEff * acc + mass * G * s.slope)
                val ok = f / mass <= 0.7 && f / mass >= -0.1
                wins += Window(si, q, f, ok, q0, rho, vm, -mEff * acc, s.dir)
                i0 = j
            }
        }
        return wins
    }

    // ------------------------------------------------------------------ least squares

    private class Ls(val beta: DoubleArray, val inv: Array<DoubleArray>, val sse: Double)

    private fun solveLs(x: List<DoubleArray>, y: List<Double>): Ls? {
        val k = x[0].size
        val a = Array(k) { DoubleArray(2 * k) }
        val g = DoubleArray(k)
        for (r in x.indices) for (i in 0 until k) {
            g[i] += x[r][i] * y[r]
            for (j in 0 until k) a[i][j] += x[r][i] * x[r][j]
        }
        val sc = DoubleArray(k) { i -> sqrt(a[i][i]).let { if (it == 0.0 || it.isNaN()) 1.0 else it } }
        for (i in 0 until k) { for (j in 0 until k) a[i][j] /= sc[i] * sc[j]; a[i][k + i] = 1.0 }
        for (col in 0 until k) {
            var piv = col
            for (r in col + 1 until k) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (abs(a[piv][col]) < 1e-9) return null
            val tmp = a[col]; a[col] = a[piv]; a[piv] = tmp
            val d = a[col][col]
            for (j in 0 until 2 * k) a[col][j] /= d
            for (r in 0 until k) if (r != col) {
                val f = a[r][col]
                if (f != 0.0) for (j in 0 until 2 * k) a[r][j] -= f * a[col][j]
            }
        }
        val inv = Array(k) { i -> DoubleArray(k) { j -> a[i][k + j] / (sc[i] * sc[j]) } }
        val beta = DoubleArray(k) { i -> (0 until k).sumOf { j -> inv[i][j] * g[j] } }
        var sse = 0.0
        for (r in x.indices) sse += (y[r] - (0 until k).sumOf { i -> x[r][i] * beta[i] }).pow(2)
        return Ls(beta, inv, sse)
    }

    private fun r2Of(values: List<Double>, sse: Double): Double {
        val m = values.average()
        val sst = values.sumOf { (it - m).pow(2) }
        return if (sst > 0) 1 - sse / sst else 0.0
    }

    private fun robustFit(wins: List<Window>, minPts: Int, fitOnce: (List<Window>) -> Fit?): Fit? {
        var pts = wins.filter { it.used }
        var out: Fit? = null
        for (pass in 0 until 3) {
            if (pts.size < minPts) return out
            val f = fitOnce(pts) ?: return out
            out = f
            val bad = pts.filterIndexed { i, _ -> abs(f.res[i]) > 2.5 * f.sigma }
            if (bad.isEmpty() || pts.size - bad.size < minPts) break
            bad.forEach { it.used = false }
            pts = pts.filter { it.used }
        }
        return out
    }

    private fun fitSingle(wins: List<Window>, mass: Double, fixedCrr: Double?): Fit? {
        val mg = mass * G
        val out = robustFit(wins, 6) { pts ->
            val n = pts.size
            val x = pts.map { if (fixedCrr != null) doubleArrayOf(it.q) else doubleArrayOf(it.q, 1.0) }
            val y = pts.map { if (fixedCrr != null) it.f - fixedCrr * mg else it.f }
            val ls = solveLs(x, y) ?: return@robustFit null
            val cda = ls.beta[0]
            val crr = fixedCrr ?: (ls.beta[1] / mg)
            val sigma = sqrt(ls.sse / max(1, n - x[0].size))
            Fit(
                cda = cda, crr = crr, sigma = sigma, n = n,
                seCda = sigma * sqrt(ls.inv[0][0]),
                seCrr = if (fixedCrr != null) null else sigma * sqrt(ls.inv[1][1]) / mg,
                r2 = r2Of(pts.map { it.f }, ls.sse),
                res = pts.map { it.f - (crr * mg + cda * it.q) },
            )
        }
        if (out != null) wins.forEach { it.px = it.q; it.py = it.f }
        return out
    }

    private fun fitPaired(wins: List<Window>, mass: Double, fixedCrr: Double?): Fit? {
        val mg = mass * G
        val out = robustFit(wins.filter { it.dir != 0 }, 10) { pts ->
            val n = pts.size
            val rhoM = pts.map { it.rho }.average()
            val k: Int
            var ls: Ls
            var cda: Double; var wind: Double; var slope: Double; val crr: Double
            if (fixedCrr == null) {
                k = 4
                ls = solveLs(pts.map { doubleArrayOf(it.q0, it.rho * it.vm * it.dir, it.dir.toDouble(), 1.0) }, pts.map { it.fi })
                    ?: return@robustFit null
                if (ls.beta[0] <= 0) return@robustFit null
                cda = ls.beta[0]
                wind = ls.beta[1] / cda
                slope = ls.beta[2] / mg
                crr = (ls.beta[3] - 0.5 * rhoM * cda * wind * wind) / mg
            } else {
                k = 3; cda = 0.3; wind = 0.0; slope = 0.0
                ls = Ls(DoubleArray(0), emptyArray(), 0.0)
                for (it in 0 until 5) {
                    val c0 = fixedCrr * mg + 0.5 * rhoM * cda * wind * wind
                    ls = solveLs(pts.map { p -> doubleArrayOf(p.q0, p.rho * p.vm * p.dir, p.dir.toDouble()) }, pts.map { p -> p.fi - c0 })
                        ?: return@robustFit null
                    if (ls.beta[0] <= 0) return@robustFit null
                    cda = ls.beta[0]; wind = ls.beta[1] / cda; slope = ls.beta[2] / mg
                }
                crr = fixedCrr
            }
            val sigma = sqrt(ls.sse / max(1, n - k))
            Fit(
                cda = cda, crr = crr, wind = wind, slope = slope, sigma = sigma, n = n,
                seCda = sigma * sqrt(ls.inv[0][0]),
                seWind = sigma * sqrt(ls.inv[1][1]) / cda,
                seSlope = sigma * sqrt(ls.inv[2][2]) / mg,
                seCrr = if (fixedCrr != null) null else sigma * sqrt(ls.inv[3][3]) / mg,
                r2 = r2Of(pts.map { it.fi }, ls.sse),
                res = pts.map { p ->
                    p.fi - (crr * mg + p.dir * mg * slope +
                        0.5 * p.rho * cda * (p.vm * p.vm + 2 * p.vm * p.dir * wind + wind * wind) +
                        cda * (p.q0 - 0.5 * p.rho * p.vm * p.vm))
                },
            )
        }
        if (out != null) wins.forEach { w ->
            w.px = w.q0
            w.py = w.fi - w.dir * mg * out.slope!! - out.cda * w.rho * w.vm * w.dir * out.wind!! - 0.5 * w.rho * out.cda * out.wind.pow(2)
            if (w.dir == 0) w.used = false
        }
        return out
    }

    // ------------------------------------------------------------------ directions

    private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun assignDirections(c: Input, segs: List<Segment>): Double {
        fun len(g: Segment) = Geo.distanceM(c.lat[g.a], c.lon[g.a], c.lat[g.b], c.lon[g.b])
        // JavaScript Math.round: halves round up (Kotlin round() would round them to even).
        fun mid(g: Segment) = kotlin.math.floor((g.a + g.b) / 2.0 + 0.5).toInt()
        val ref = segs.reduce { best, g -> if (len(g) > len(best)) g else best }
        val refBearing = bearingDeg(c.lat[ref.a], c.lon[ref.a], c.lat[ref.b], c.lon[ref.b])
        val refMid = mid(ref)
        segs.forEach { g ->
            g.bearing = bearingDeg(c.lat[g.a], c.lon[g.a], c.lat[g.b], c.lon[g.b])
            val cs = cos(Math.toRadians(g.bearing - refBearing))
            val m = mid(g)
            val near = Geo.distanceM(c.lat[m], c.lon[m], c.lat[refMid], c.lon[refMid]) <= max(500.0, 2 * len(ref))
            g.dir = if (abs(cs) >= 0.8 && near) sign(cs).toInt() else 0
        }
        return refBearing
    }

    // ------------------------------------------------------------------ run

    fun run(
        c: Input,
        mass: Double,
        currentCrr: Double,
        forceFixed: Boolean,
        wantPaired: Boolean = true,
        useLaps: Boolean = true,
    ): Result {
        val found = findCoastSegments(c, useLaps)
        val segs = found.segs
        if (segs.isEmpty()) {
            return Result(segs, found.hasCad, found.source, found.lapsAvailable, false, null, emptyList(), null, null, null, null, "none")
        }
        val refBearing = assignDirections(c, segs)
        fun fresh() = coastWindows(c, segs, mass)
        var wins = fresh()
        fun count(dir: Int) = wins.count { it.used && it.dir == dir }
        val pairedAvailable = count(1) >= 4 && count(-1) >= 4

        fun plausible(f: Fit?) = f != null && f.crr in 0.0015..0.015 && f.cda in 0.12..0.9 &&
            (f.wind == null || (abs(f.wind) <= 12 && abs(f.slope!!) <= 0.06))
        fun narrow(q0: Boolean): Boolean {
            val qs = wins.filter { it.used }.map { if (q0) it.q0 else it.q }
            return qs.max() / max(1e-6, qs.min()) < 2
        }

        var fit: Fit? = null
        var method = "single"
        var mode = if (forceFixed) "fixed" else "free"
        var fallback: String? = null

        if (wantPaired && pairedAvailable) {
            method = "paired"
            fit = fitPaired(wins, mass, if (forceFixed) currentCrr else null)
            if (!forceFixed && (!plausible(fit) || narrow(true))) {
                fallback = if (fit != null && narrow(true)) "narrow" else "odd"
                wins = fresh()
                fit = fitPaired(wins, mass, currentCrr)
                mode = "fixed"
            }
            if (!plausible(fit)) {
                fit = null; method = "single"; fallback = "paired-failed"; wins = fresh(); mode = if (forceFixed) "fixed" else "free"
            }
        }
        if (fit == null) {
            fit = fitSingle(wins, mass, if (forceFixed) currentCrr else null)
            if (fit != null && !forceFixed && (narrow(false) || !plausible(fit))) {
                if (fallback != "paired-failed") fallback = if (narrow(false)) "narrow" else "odd"
                wins = fresh()
                fit = fitSingle(wins, mass, currentCrr)
                mode = "fixed"
            }
        }
        if (fit == null) {
            return Result(segs, found.hasCad, found.source, found.lapsAvailable, pairedAvailable, refBearing, wins, null, null, null, null, "few")
        }
        val t = c.calib.t
        val v = c.calib.v
        val totalTime = segs.sumOf { t[it.b] - t[it.a] }
        var vMin = Double.MAX_VALUE
        var vMax = 0.0
        segs.forEach { g -> for (i in g.a..g.b) { vMin = min(vMin, v[i]); vMax = max(vMax, v[i]) } }
        return Result(segs, found.hasCad, found.source, found.lapsAvailable, pairedAvailable, refBearing, wins, fit, mode, method, fallback, null, totalTime, vMin, vMax)
    }
}
