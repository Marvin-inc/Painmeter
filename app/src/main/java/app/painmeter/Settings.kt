package app.painmeter

import android.content.Context
import android.content.SharedPreferences
import app.painmeter.core.HrZones
import app.painmeter.core.RideSettings
import app.painmeter.core.power.PowerParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the rider sets in Parameters (SPEC §9.6). */
data class UserSettings(
    val riderKg: Double = 75.0,
    val ftpW: Int = 250,
    val bikeKg: Double = 8.0,
    val cda: Double = 0.32,
    val crr: Double = 0.004,
    val lossPercent: Double = 2.0,
    val draftFactor: Double = 0.65,
    val draftReminder: Boolean = true,
    val weatherOn: Boolean = true,
    val windFactor: Double = 0.6,
    val rhoFallback: Double = 1.225,
    val maxHr: Int = 185,
    val hrZoneBounds: List<Int> = HrZones.fromMaxHr(185).upperBounds,
    val hrDeviceAddress: String? = null,
    val hrDeviceName: String? = null,
    val autoPause: Boolean = true,
) {
    val hrZones: HrZones get() = HrZones(hrZoneBounds)
    val power: PowerParams
        get() = PowerParams(riderKg, bikeKg, cda, crr, lossPercent, rhoFallback, draftFactor, windFactor, ftpW)

    fun toRideSettings() = RideSettings(autoPause = autoPause, hrZones = hrZones, power = power)
}

object Settings {
    private lateinit var prefs: SharedPreferences
    private val _current = MutableStateFlow(UserSettings())
    val current: StateFlow<UserSettings> = _current

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _current.value = load()
    }

    fun update(change: (UserSettings) -> UserSettings) {
        val next = change(_current.value)
        _current.value = next
        prefs.edit()
            .putInt("maxHr", next.maxHr)
            .putString("hrZones", next.hrZoneBounds.joinToString(","))
            .putString("hrAddress", next.hrDeviceAddress)
            .putString("hrName", next.hrDeviceName)
            .putBoolean("autoPause", next.autoPause)
            .putString("riderKg", next.riderKg.toString())
            .putInt("ftp", next.ftpW)
            .putString("bikeKg", next.bikeKg.toString())
            .putString("cda", next.cda.toString())
            .putString("crr", next.crr.toString())
            .putString("loss", next.lossPercent.toString())
            .putString("draftFactor", next.draftFactor.toString())
            .putBoolean("draftReminder", next.draftReminder)
            .putBoolean("weather", next.weatherOn)
            .putString("windFactor", next.windFactor.toString())
            .putString("rho", next.rhoFallback.toString())
            .apply()
    }

    private fun load(): UserSettings {
        val d = UserSettings()
        val bounds = prefs.getString("hrZones", null)
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 4 && it.zipWithNext().all { (a, b) -> a < b } }
        return UserSettings(
            maxHr = prefs.getInt("maxHr", d.maxHr),
            hrZoneBounds = bounds ?: d.hrZoneBounds,
            hrDeviceAddress = prefs.getString("hrAddress", null),
            hrDeviceName = prefs.getString("hrName", null),
            autoPause = prefs.getBoolean("autoPause", true),
            riderKg = dbl("riderKg", d.riderKg),
            ftpW = prefs.getInt("ftp", d.ftpW),
            bikeKg = dbl("bikeKg", d.bikeKg),
            cda = dbl("cda", d.cda),
            crr = dbl("crr", d.crr),
            lossPercent = dbl("loss", d.lossPercent),
            draftFactor = dbl("draftFactor", d.draftFactor),
            draftReminder = prefs.getBoolean("draftReminder", d.draftReminder),
            weatherOn = prefs.getBoolean("weather", d.weatherOn),
            windFactor = dbl("windFactor", d.windFactor),
            rhoFallback = dbl("rho", d.rhoFallback),
        )
    }

    private fun dbl(key: String, default: Double) = prefs.getString(key, null)?.toDoubleOrNull() ?: default
}
