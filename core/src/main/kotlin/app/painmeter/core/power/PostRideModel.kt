package app.painmeter.core.power

import app.painmeter.core.Geo
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Line-by-line port of `processRide()` from the Power-calcualtor web tool
 * (cyclingPowerGenerator.html). Post-ride only: it uses centred smoothing windows, i.e. data from
 * after each point. It exists to prove the Kotlin physics give the same watts as the web tool
 * (SPEC §12, test 1); the live engine is [LivePowerEngine].
 */
object PostRideModel {

    data class Point(
        val lat: Double,
        val lon: Double,
        val ele: Double?,
        /** Milliseconds since the epoch, null when the file has no time. */
        val timeMs: Long?,
        /** Device speed channel (m/s), null when absent. */
        val speed: Double?,
    )

    data class Params(
        val riderKg: Double,
        val bikeKg: Double,
        val cda: Double,
        val crr: Double,
        val rho: Double,
        val lossPercent: Double,
        val smoothSec: Double = 15.0,
    )

    /** Per-point fetched weather: headwind (m/s, already at rider height) and air density, or null. */
    data class Weather(val wind: List<Double>, val rho: List<Double?>)

    data class Result(
        val power: List<Double>,
        val gradePercent: List<Double>,
        val speed: List<Double>,
        val avgPower: Double,
        val maxPower: Double,
        val np: Double,
        val workKj: Double,
        val distanceKm: Double,
    )

    private const val G = 9.8067
    private const val WHEEL_INERTIA_MASS = 1.5

    fun process(points: List<Point>, p: Params, weather: Weather? = null): Result {
        val n = points.size
        val mass = p.riderKg + p.bikeKg
        val eff = 1 - p.lossPercent / 100

        val dist = DoubleArray(n)
        for (i in 1 until n) {
            dist[i] = dist[i - 1] + Geo.distanceM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }
        val hasTime = points.all { it.timeMs != null }
        val cumTime = DoubleArray(n)
        for (i in 1 until n) {
            cumTime[i] = if (hasTime) (points[i].timeMs!! - points[0].timeMs!!) / 1000.0
            else cumTime[i - 1] + (dist[i] - dist[i - 1]) / (20 / 3.6)
        }

        val eleCount = points.count { it.ele != null && !it.ele.isNaN() }
        val hasEle = eleCount > n * 0.5
        val eleRaw = DoubleArray(n)
        if (hasEle) {
            val arr = points.map { it.ele?.takeIf { e -> !e.isNaN() } }.toMutableList()
            var last = arr.firstOrNull { it != null } ?: 0.0
            for (i in 0 until n) { val v = arr[i]; if (v != null) last = v else arr[i] = last }
            for (i in 0 until n) eleRaw[i] = arr[i]!!
        }
        val ele = if (hasEle) smoothByTime(eleRaw, cumTime, 10.0) else eleRaw

        val rawGrade = DoubleArray(n)
        for (i in 1 until n) {
            val dx = dist[i] - dist[i - 1]
            if (dx < 0.1) { rawGrade[i] = rawGrade[i - 1]; continue }
            val dz = if (hasEle) ele[i] - ele[i - 1] else 0.0
            rawGrade[i] = dz / dx
        }
        val smoothGrade = smooth(rawGrade, 5)

        val fitSpeedCount = points.count { it.speed != null && !it.speed.isNaN() }
        val useFitSpeed = fitSpeedCount > n * 0.5
        val rawSpeed = DoubleArray(n)
        if (useFitSpeed) {
            var last = 0.0
            for (i in 0 until n) {
                val s = points[i].speed
                if (s != null && !s.isNaN()) { last = s; rawSpeed[i] = s } else rawSpeed[i] = last
            }
        } else {
            for (i in 1 until n) {
                val dx = dist[i] - dist[i - 1]
                var dt = if (hasTime) (points[i].timeMs!! - points[i - 1].timeMs!!) / 1000.0 else dx / (20 / 3.6)
                if (hasTime && dt <= 0) dt = 1.0
                rawSpeed[i] = dx / dt
            }
        }
        val smoothSpeed = smoothByTime(rawSpeed, cumTime, 3.0)

        val rawAccel = DoubleArray(n)
        for (i in 1 until n - 1) {
            val dt = cumTime[i + 1] - cumTime[i - 1]
            if (dt > 0) rawAccel[i] = (smoothSpeed[i + 1] - smoothSpeed[i - 1]) / dt
        }
        val accel = smoothByTime(rawAccel, cumTime, 5.0)

        val power = DoubleArray(n)
        val grade = DoubleArray(n)
        for (i in 0 until n) {
            val v = max(0.0, smoothSpeed[i])
            val g = smoothGrade[i]
            val w = weather?.wind?.getOrNull(i) ?: 0.0
            val a = accel[i]
            val rho = weather?.rho?.getOrNull(i)?.takeIf { !it.isNaN() } ?: p.rho
            val theta = atan(g)
            val fGrav = mass * G * sin(theta)
            val fRoll = mass * G * cos(theta) * p.crr
            val vEff = v + w
            val fAero = 0.5 * rho * p.cda * vEff * abs(vEff)
            val fKin = (mass + WHEEL_INERTIA_MASS) * a
            power[i] = max(0.0, (fGrav + fRoll + fAero + fKin) * v / eff)
            grade[i] = g * 100
        }
        val smoothed = if (p.smoothSec > 0) smoothByTime(power, cumTime, p.smoothSec) else power.copyOf()

        var work = 0.0
        for (i in 1 until n) work += smoothed[i] * (cumTime[i] - cumTime[i - 1])

        return Result(
            power = smoothed.toList(),
            gradePercent = grade.toList(),
            speed = smoothSpeed.toList(),
            avgPower = smoothed.average(),
            maxPower = smoothed.max(),
            np = normalisedPower(smoothed),
            workKj = work / 1000,
            distanceKm = dist[n - 1] / 1000,
        )
    }

    /** Index-based moving average, as `smooth(arr, w)` in the web tool. */
    fun smooth(arr: DoubleArray, w: Int): DoubleArray = DoubleArray(arr.size) { i ->
        val s = max(0, i - floor(w / 2.0).toInt())
        val e = min(arr.size, i + ceil(w / 2.0).toInt())
        var sum = 0.0
        for (j in s until e) sum += arr[j]
        sum / (e - s)
    }

    /** Centred time-aware moving average, as `smoothByTime()` in the web tool. */
    fun smoothByTime(arr: DoubleArray, t: DoubleArray, windowSec: Double): DoubleArray {
        val half = windowSec / 2
        return DoubleArray(arr.size) { i ->
            var sum = arr[i]
            var count = 1
            var j = i - 1
            while (j >= 0 && t[i] - t[j] <= half) { sum += arr[j]; count++; j-- }
            j = i + 1
            while (j < arr.size && t[j] - t[i] <= half) { sum += arr[j]; count++; j++ }
            sum / count
        }
    }

    /** 4th-power mean of the 30-sample trailing average, as `normalisedPower()` in the web tool. */
    fun normalisedPower(powers: DoubleArray): Double {
        if (powers.size < 30) return powers.average()
        var mean4 = 0.0
        for (i in powers.indices) {
            val s = max(0, i - 30 + 1)
            var sum = 0.0
            for (j in s..i) sum += powers[j]
            mean4 += (sum / (i - s + 1)).pow(4)
        }
        return (mean4 / powers.size).pow(0.25)
    }
}
