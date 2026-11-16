package app.painmeter.core.power

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SPEC §12 test 1: the Kotlin port gives the same power as the web tool, within ±1 W per point.
 * Expected values come from running processRide() of the real cyclingPowerGenerator.html with
 * Node (tools/golden/make_golden.js).
 */
class PostRideModelTest {

    private class Golden(val points: List<PostRideModel.Point>, val params: PostRideModel.Params, val weather: PostRideModel.Weather?)

    private fun lines(name: String): List<String> =
        javaClass.getResource("/golden/$name")!!.readText().lines().filter { it.isNotBlank() }

    private fun load(name: String): Golden {
        val l = lines("$name.in.csv")
        val p = l[0].removePrefix("#params ").split(' ').map { it.toDouble() }
        val params = PostRideModel.Params(p[0], p[1], p[2], p[3], p[4], p[5], p[6])
        val rows = l.drop(2).map { it.split(',') }
        val points = rows.map { r ->
            PostRideModel.Point(r[0].toDouble(), r[1].toDouble(), r[2].toDoubleOrNull(), r[3].toLongOrNull(), r[4].toDoubleOrNull())
        }
        val hasWeather = rows.any { it[5].isNotEmpty() }
        val weather = if (hasWeather) PostRideModel.Weather(rows.map { it[5].toDouble() }, rows.map { it[6].toDoubleOrNull() }) else null
        return Golden(points, params, weather)
    }

    private fun check(name: String) {
        val g = load(name)
        val expected = lines("$name.expected.csv")
        val summary = expected[0].removePrefix("#summary ").split(' ').map { it.toDouble() }
        val rows = expected.drop(2).map { r -> r.split(',').map { it.toDouble() } }

        val result = PostRideModel.process(g.points, g.params, g.weather)

        assertEquals(rows.size, result.power.size)
        val worst = rows.indices.maxOf { abs(rows[it][0] - result.power[it]) }
        assertTrue(worst <= 1.0, "$name: worst point differs by $worst W")
        rows.indices.forEach {
            assertEquals(rows[it][1], result.gradePercent[it], 1e-6, "$name grade at $it")
            assertEquals(rows[it][2], result.speed[it], 1e-6, "$name speed at $it")
        }
        assertEquals(summary[0], result.avgPower, 0.01, "$name avg")
        assertEquals(summary[1], result.maxPower, 1.0, "$name max")
        assertEquals(summary[2], result.np, 0.01, "$name NP")
        assertEquals(summary[3], result.workKj, 0.01, "$name work")
        assertEquals(summary[4], result.distanceKm, 1e-6, "$name distance")
    }

    @Test fun hillyRideWithGpsSpeed() = check("hilly_gps_speed")
    @Test fun deviceSpeedAndWeather() = check("device_speed_weather")
    @Test fun fileWithoutTimestamps() = check("no_time")
}
