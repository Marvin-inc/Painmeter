package app.painmeter

import android.content.Context
import android.content.SharedPreferences
import app.painmeter.core.HrZones
import app.painmeter.core.RideSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the rider sets in Parameters. Phase 2 holds the HR part; bike and aero come in phase 3. */
data class UserSettings(
    val maxHr: Int = 185,
    val hrZoneBounds: List<Int> = HrZones.fromMaxHr(185).upperBounds,
    val hrDeviceAddress: String? = null,
    val hrDeviceName: String? = null,
    val autoPause: Boolean = true,
) {
    val hrZones: HrZones get() = HrZones(hrZoneBounds)
    fun toRideSettings() = RideSettings(autoPause = autoPause, hrZones = hrZones)
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
        )
    }
}
