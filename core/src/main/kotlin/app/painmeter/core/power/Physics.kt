package app.painmeter.core.power

import kotlin.math.cos
import kotlin.math.exp

/** Constants and formulas shared with the Power-calcualtor web tool. */
object Physics {
    const val G = 9.8067
    /** Rotating wheels add about 1.5 kg of inertia (same value as the coast-down calibration). */
    const val WHEEL_INERTIA_MASS = 1.5
    /** Wind at rider height (~1.2 m) relative to the 10 m forecast: log wind profile, open country. */
    const val DEFAULT_WIND_HEIGHT_FACTOR = 0.6

    /**
     * Air density (kg/m³) from temperature (°C), sea-level pressure (Pa), relative humidity (%)
     * and the rider's elevation (m): barometric formula plus Tetens vapour pressure.
     * Same as `airDensity()` in the web tool.
     */
    fun airDensity(tempC: Double, pressureMslPa: Double, rhPercent: Double, elevationM: Double): Double {
        val t = tempC + 273.15
        val g = 9.80665
        val m = 0.0289644
        val r = 8.31447
        val p = pressureMslPa * exp(-g * m * elevationM / (r * t))
        val pSat = 610.78 * exp(17.27 * tempC / (tempC + 237.3))
        val pv = (rhPercent / 100).coerceIn(0.0, 1.0) * pSat
        val pd = p - pv
        return pd / (287.058 * t) + pv / (461.495 * t)
    }

    /**
     * Headwind component (m/s, positive = into the rider) of a wind blowing FROM [windFromDeg]
     * at [windSpeedMps], for a rider heading towards [bearingDeg].
     */
    fun headwind(windSpeedMps: Double, windFromDeg: Double, bearingDeg: Double): Double =
        windSpeedMps * cos(Math.toRadians(windFromDeg - bearingDeg))
}
