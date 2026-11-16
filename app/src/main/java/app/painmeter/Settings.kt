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
    val climbMinGrade: Double = 3.0,
    val climbMinLength: Double = 500.0,
    val climbMinGain: Double = 25.0,
    val climbAutoSwitch: Boolean = true,
    /** Last calibration applied: when, and the uncertainty of CdA and Crr. */
    /** Download a missing elevation tile during the ride (about 10–25 MB, may use mobile data). */
    val autoElevation: Boolean = true,
    /** Power averaging on screen, seconds. */
    val powerWindowS: Int = 10,
    /** Raw GPS chip (less lag, slower first fix) instead of Google's location service. */
    val rawGps: Boolean = false,
    /** Turn the map with the phone's compass when stopped or very slow. */
    val compassWhenStopped: Boolean = true,
    val calibDateMs: Long? = null,
    val calibSeCda: Double? = null,
    val calibSeCrr: Double? = null,
) {
    val climbs: app.painmeter.core.climb.ClimbSettings
        get() = app.painmeter.core.climb.ClimbSettings(
            minGradePercent = climbMinGrade, minLengthM = climbMinLength, minGainM = climbMinGain, autoSwitch = climbAutoSwitch,
        )

    val hrZones: HrZones get() = HrZones(hrZoneBounds)
    val power: PowerParams
        get() = PowerParams(riderKg, bikeKg, cda, crr, lossPercent, rhoFallback, draftFactor, windFactor, ftpW, powerWindowS)

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
            .putString("climbGrade", next.climbMinGrade.toString())
            .putString("climbLength", next.climbMinLength.toString())
            .putString("climbGain", next.climbMinGain.toString())
            .putBoolean("climbSwitch", next.climbAutoSwitch)
            .putBoolean("autoElevation", next.autoElevation)
            .putInt("powerWindow", next.powerWindowS)
            .putBoolean("rawGps", next.rawGps)
            .putBoolean("compass", next.compassWhenStopped)
            .putLong("calibDate", next.calibDateMs ?: -1)
            .putString("calibSeCda", next.calibSeCda?.toString())
            .putString("calibSeCrr", next.calibSeCrr?.toString())
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
            climbMinGrade = dbl("climbGrade", d.climbMinGrade),
            climbMinLength = dbl("climbLength", d.climbMinLength),
            climbMinGain = dbl("climbGain", d.climbMinGain),
            climbAutoSwitch = prefs.getBoolean("climbSwitch", d.climbAutoSwitch),
            autoElevation = prefs.getBoolean("autoElevation", d.autoElevation),
            powerWindowS = prefs.getInt("powerWindow", d.powerWindowS).takeIf { it in listOf(3, 5, 10, 20, 30) } ?: d.powerWindowS,
            rawGps = prefs.getBoolean("rawGps", d.rawGps),
            compassWhenStopped = prefs.getBoolean("compass", d.compassWhenStopped),
            calibDateMs = prefs.getLong("calibDate", -1).takeIf { it > 0 },
            calibSeCda = prefs.getString("calibSeCda", null)?.toDoubleOrNull(),
            calibSeCrr = prefs.getString("calibSeCrr", null)?.toDoubleOrNull(),
        )
    }

    private fun dbl(key: String, default: Double) = prefs.getString(key, null)?.toDoubleOrNull() ?: default
}
