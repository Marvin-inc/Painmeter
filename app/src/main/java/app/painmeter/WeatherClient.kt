package app.painmeter

import app.painmeter.core.power.WeatherNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Current weather from Open-Meteo (free, no key): wind at 10 m, temperature, humidity, pressure. */
object WeatherClient {
    suspend fun current(lat: Double, lon: Double): WeatherNow? = withContext(Dispatchers.IO) {
        try {
            val url = String.format(
                Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,relative_humidity_2m,pressure_msl,wind_speed_10m,wind_direction_10m" +
                    "&wind_speed_unit=ms",
                lat, lon,
            )
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode != 200) return@withContext null
            val c = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
            WeatherNow(
                windSpeedMps = c.getDouble("wind_speed_10m"),
                windFromDeg = c.getDouble("wind_direction_10m"),
                tempC = c.getDouble("temperature_2m"),
                rhPercent = c.getDouble("relative_humidity_2m"),
                pressureMslPa = c.getDouble("pressure_msl") * 100, // hPa → Pa
            )
        } catch (_: Exception) {
            null
        }
    }
}
