package app.painmeter.core.calibration

import app.painmeter.core.JournalEntry
import app.painmeter.core.RideSession
import app.painmeter.core.RideSettings
import app.painmeter.core.RideStatus
import app.painmeter.core.power.Physics
import app.painmeter.core.power.PostRideModel
import app.painmeter.core.power.WeatherNow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Runs the coast-down calibration on a Painmeter ride: the recorded seconds (as in the FIT file),
 * the lap marks, the elevation-tile altitude and the weather received during the ride, prepared
 * the way the web tool prepares a FIT file.
 */
object RideCalibration {

    fun input(entries: List<JournalEntry>, settings: RideSettings): CoastDown.Input? {
        val session = RideSession(settings)
        val points = mutableListOf<PostRideModel.Point>()
        val weatherAt = mutableListOf<WeatherNow?>()
        var weather: WeatherNow? = null
        var lastSecond = Long.MIN_VALUE
        for (e in entries) {
            session.apply(e)
            when (e) {
                is JournalEntry.Weather -> weather = e.weather
                is JournalEntry.Sample -> {
                    val second = e.fix.timeMs / 1000
                    if (session.status == RideStatus.RECORDING && second > lastSecond) {
                        lastSecond = second
                        points += PostRideModel.Point(e.fix.lat, e.fix.lon, e.fix.demAltitudeM ?: e.fix.altitudeM, e.fix.timeMs, e.fix.speedMps)
                        weatherAt += weather
                    }
                }
                else -> Unit
            }
        }
        if (points.size < 30) return null
        if (session.status != RideStatus.FINISHED) session.apply(JournalEntry.Stop(entries.last().timeMs))

        val hasWind = weatherAt.any { it != null }
        val w = if (!hasWind) null else PostRideModel.Weather(
            wind = points.indices.map { i ->
                val wx = weatherAt[i] ?: return@map 0.0
                val j = if (i < points.size - 1) i + 1 else i - 1
                val b = if (j > i) bearing(points[i], points[j]) else bearing(points[j], points[i])
                settings.power.windFactor * Physics.headwind(wx.windSpeedMps, wx.windFromDeg, b)
            },
            rho = points.indices.map { i ->
                weatherAt[i]?.let { Physics.airDensity(it.tempC, it.pressureMslPa, it.rhPercent, points[i].ele ?: 0.0) }
            },
        )
        val p = settings.power
        val calib = PostRideModel.process(
            points, PostRideModel.Params(p.riderKg, p.bikeKg, p.cda, p.crr, p.rhoFallback, p.drivetrainLossPercent), w,
        ).calib
        val t0 = points.first().timeMs!!
        return CoastDown.Input(
            calib = calib,
            lat = points.map { it.lat }.toDoubleArray(),
            lon = points.map { it.lon }.toDoubleArray(),
            cad = arrayOfNulls(points.size),
            laps = session.completedLaps.map { (it.startTimeMs - t0) / 1000.0 to (it.endTimeMs - t0) / 1000.0 },
            hasEle = points.count { it.ele != null } > points.size / 2,
            hasWind = hasWind,
        )
    }

    private fun bearing(a: PostRideModel.Point, b: PostRideModel.Point): Double {
        val p1 = Math.toRadians(a.lat)
        val p2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        return (Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl))) + 360) % 360
    }

    /** Warnings of SPEC §9.7 for a weak result. */
    fun warnings(r: CoastDown.Result): List<String> {
        val out = mutableListOf<String>()
        val f = r.fit ?: return listOf(
            if (r.error == "none") "No coasting run found. Press volume − at the start and the end of each coast."
            else "Not enough coasting data to fit. Do more runs."
        )
        val out1 = r.segs.count { it.dir == 1 }
        val back = r.segs.count { it.dir == -1 }
        if (out1 < 3 || back < 3) out += "Only $out1 run(s) one way and $back the other: do 5–6 out-and-back pairs."
        if (f.seCda > 0.025) out += "CdA is uncertain (± ${"%.3f".format(java.util.Locale.ROOT, f.seCda)}): add runs, or keep Crr fixed."
        if (r.fallback == "narrow") out += "Speed range too narrow to separate rolling from air drag: Crr was kept. Coast from 35–40 km/h down to 12–15 km/h."
        if (r.fallback == "odd" || r.fallback == "paired-failed") out += "The fit looked odd, so Crr was kept at its current value. Check the runs (braking? pedalling? gusts?)."
        return out
    }
}
