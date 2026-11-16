package app.painmeter.core.power

import app.painmeter.core.Geo
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** What the rider sets for the power model (SPEC §5 and §9.6). */
data class PowerParams(
    val riderKg: Double = 75.0,
    val bikeKg: Double = 8.0,
    val cda: Double = 0.32,
    val crr: Double = 0.004,
    val drivetrainLossPercent: Double = 2.0,
    /** Air density when no weather is available. */
    val rhoFallback: Double = 1.225,
    /** CdA multiplier when riding On a wheel. */
    val draftFactor: Double = 0.65,
    /** Forecast (10 m) wind to rider-height wind. */
    val windFactor: Double = Physics.DEFAULT_WIND_HEIGHT_FACTOR,
    val ftpW: Int = 250,
) {
    val massKg: Double get() = riderKg + bikeKg
    val efficiency: Double get() = 1 - drivetrainLossPercent / 100
}

/** Current weather from the forecast service. */
data class WeatherNow(
    val windSpeedMps: Double,
    val windFromDeg: Double,
    val tempC: Double,
    val rhPercent: Double,
    val pressureMslPa: Double,
)

/** Where the elevation of a sample came from, best first (SPEC §4). */
enum class ElevationSource { ROUTE, DEM, GPS, NONE }

/** One second of input for the engine. */
data class PowerInput(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    /** GPS Doppler speed, m/s. */
    val speedMps: Double?,
    val elevationM: Double?,
    val elevationSource: ElevationSource,
    val accuracyM: Double?,
    /** Gradient read on the route profile at the rider's position (no lag), when on route. */
    val gradeOverridePercent: Double? = null,
)

/** The engine's view after the latest second. Power values are watts at the pedals, ≥ 0. */
data class PowerState(
    val power10s: Double? = null,
    val power30s: Double? = null,
    /** The 1 Hz value written to the FIT file (5 s trailing window). */
    val power1Hz: Double? = null,
    val gradePercent: Double? = null,
    /** False when the estimate is less trustworthy: see SPEC §5.3. */
    val reliable: Boolean = false,
    val headwindMps: Double = 0.0,
    val rho: Double = 1.225,
    val drafting: Boolean = false,
)

/**
 * Live, causal version of the web tool's model (SPEC §5): every value only uses the past.
 *
 * Power over a window is computed from energy, not by averaging noisy instant values:
 * ```
 * P(T) = [ ½·m_eff·(v_end² − v_start²) + Σ (F_grav + F_roll + F_aero)·v·dt ] / (T·η)
 * ```
 * so speed noise is never differentiated.
 */
class LivePowerEngine(private val params: PowerParams) {

    private class Tick(val timeMs: Long, val ke: Double, val work: Double)

    private val ticks = ArrayDeque<Tick>()
    private var work = 0.0
    private var lastInput: PowerInput? = null
    private var filteredSpeed: Double? = null
    private var distanceM = 0.0
    private val profile = ArrayDeque<Pair<Double, Double>>() // (distance, elevation)
    private var grade = 0.0
    private var bearingDeg: Double? = null
    private var bearingFrom: PowerInput? = null
    private var weather: WeatherNow? = null
    var drafting = false
        private set

    var state = PowerState(rho = params.rhoFallback)
        private set

    fun setWeather(w: WeatherNow?) { weather = w }
    fun setDrafting(on: Boolean) { drafting = on; state = state.copy(drafting = on) }

    /** Forget the speed history (after a pause), so no window spans the stop. */
    fun reset() {
        gpsJumpSinceMs = null
        ticks.clear()
        lastInput = null
        filteredSpeed = null
        bearingFrom = null
        state = state.copy(power10s = null, power30s = null, power1Hz = null)
    }

    fun onInput(input: PowerInput): PowerState {
        val prev = lastInput
        lastInput = input
        val dtS = prev?.let { (input.timeMs - it.timeMs) / 1000.0 } ?: 0.0
        if (prev != null && (dtS <= 0 || dtS > 5)) {
            // A gap or a clock jump: start the windows again.
            reset()
            lastInput = input
            return state
        }

        // Speed: Doppler, lightly smoothed (causal).
        val raw = input.speedMps ?: prev?.let { Geo.distanceM(it.lat, it.lon, input.lat, input.lon) / dtS } ?: 0.0
        val v = filteredSpeed?.let { it + 0.6 * (raw - it) } ?: raw
        filteredSpeed = v

        // Distance and gradient over the last 100 m travelled.
        if (prev != null) distanceM += v * dtS
        val elevation = if (input.elevationSource == ElevationSource.GPS) gpsFilter(input.timeMs, input.elevationM) else input.elevationM
        elevation?.let { e ->
            profile.addLast(distanceM to e)
            while (profile.size > 2 && profile.first().first < distanceM - GRADE_WINDOW_M && profile.elementAt(1).first <= distanceM - GRADE_WINDOW_M) {
                profile.removeFirst()
            }
            val (d0, e0) = profile.first()
            if (distanceM - d0 >= MIN_GRADE_DISTANCE_M) grade = ((e - e0) / (distanceM - d0)).coerceIn(-0.3, 0.3)
        }
        input.gradeOverridePercent?.let { grade = (it / 100).coerceIn(-0.3, 0.3) }
        updateBearing(input)

        // Air: density from the weather, wind along the heading.
        val w = weather
        val rho = if (w != null && input.elevationM != null) {
            Physics.airDensity(w.tempC, w.pressureMslPa, w.rhPercent, input.elevationM)
        } else if (w != null) {
            Physics.airDensity(w.tempC, w.pressureMslPa, w.rhPercent, 0.0)
        } else params.rhoFallback
        val head = if (w != null && bearingDeg != null) params.windFactor * Physics.headwind(w.windSpeedMps, w.windFromDeg, bearingDeg!!) else 0.0

        // Resistive work during this second.
        if (prev != null) {
            val theta = atan(grade)
            val m = params.massKg
            val cda = params.cda * (if (drafting) params.draftFactor else 1.0)
            val vAir = v + head
            val f = m * Physics.G * sin(theta) + m * Physics.G * cos(theta) * params.crr + 0.5 * rho * cda * vAir * kotlin.math.abs(vAir)
            work += f * v * dtS
        }
        val ke = 0.5 * (params.massKg + Physics.WHEEL_INERTIA_MASS) * v * v
        ticks.addLast(Tick(input.timeMs, ke, work))
        while (ticks.size > 2 && input.timeMs - ticks.elementAt(1).timeMs >= 30_000) ticks.removeFirst()

        val reliable = (input.accuracyM ?: 99.0) <= 12.0 && !drafting &&
            (input.elevationSource == ElevationSource.DEM || input.elevationSource == ElevationSource.ROUTE) &&
            v >= 5 / 3.6
        state = PowerState(
            power10s = window(10_000),
            power30s = window(30_000),
            power1Hz = window(5_000),
            gradePercent = grade * 100,
            reliable = reliable,
            headwindMps = head,
            rho = rho,
            drafting = drafting,
        )
        return state
    }

    // Phone GPS altitude jumps by 10–20 m at times (a new satellite fix, a switch of method).
    // No cyclist climbs faster than about 2 m/s, so a bigger jump is held back; if the new level
    // lasts 15 s it is taken, and the gradient history starts again from it (no false climb).
    private var gpsLast: Double? = null
    private var gpsLastMs = 0L
    private var gpsJumpSinceMs: Long? = null

    private fun gpsFilter(timeMs: Long, alt: Double?): Double? {
        alt ?: return null
        val last = gpsLast ?: run { gpsLast = alt; gpsLastMs = timeMs; return alt }
        // Allowed change since the previous sample (not since the last accepted one, or a held-back
        // jump would end up inside the tolerance as time passes).
        val dt = ((timeMs - gpsLastMs) / 1000.0).coerceIn(1.0, 5.0)
        gpsLastMs = timeMs
        if (kotlin.math.abs(alt - last) <= 3 + 2.0 * dt) {
            gpsLast = alt; gpsJumpSinceMs = null
            return alt
        }
        val since = gpsJumpSinceMs ?: timeMs.also { gpsJumpSinceMs = it }
        if (timeMs - since >= 15_000) {
            gpsLast = alt; gpsJumpSinceMs = null
            profile.clear()
            grade = 0.0
            return alt
        }
        return null
    }

    /** Average power over the last [ms], or null until that much history exists. */
    private fun window(ms: Long): Double? {
        val now = ticks.lastOrNull() ?: return null
        val start = ticks.lastOrNull { now.timeMs - it.timeMs >= ms } ?: return null
        val dt = (now.timeMs - start.timeMs) / 1000.0
        if (dt <= 0) return null
        return (((now.ke - start.ke) + (now.work - start.work)) / (dt * params.efficiency)).coerceAtLeast(0.0)
    }

    /** Heading from positions at least 10 m apart (Doppler bearing is not always given). */
    private fun updateBearing(input: PowerInput) {
        val from = bearingFrom ?: run { bearingFrom = input; return }
        if (Geo.distanceM(from.lat, from.lon, input.lat, input.lon) < 10) return
        val p1 = Math.toRadians(from.lat)
        val p2 = Math.toRadians(input.lat)
        val dl = Math.toRadians(input.lon - from.lon)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        bearingDeg = (Math.toDegrees(atan2(y, x)) + 360) % 360
        bearingFrom = input
    }

    companion object {
        const val GRADE_WINDOW_M = 100.0
        const val MIN_GRADE_DISTANCE_M = 30.0
    }
}

/** Average power and NP over recorded seconds (SPEC §5.5). */
class PowerStats {
    private var sum = 0.0
    private var count = 0
    private var sum4 = 0.0
    private var count4 = 0
    var max = 0.0
        private set

    /** One recorded second: the 1 Hz power and the 30 s power (for NP). */
    fun add(power1Hz: Double, power30s: Double?) {
        sum += power1Hz
        count++
        if (power1Hz > max) max = power1Hz
        if (power30s != null) { sum4 += power30s.pow(4); count4++ }
    }

    val average: Double? get() = if (count > 0) sum / count else null
    val normalized: Double? get() = if (count4 > 0) (sum4 / count4).pow(0.25) else average
}
