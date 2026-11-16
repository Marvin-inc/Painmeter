package app.painmeter

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The phone's compass, for the map heading when stopped or very slow (Parameters → Map).
 *
 * The phone sits on a handlebar mount, anywhere between lying flat and standing upright with the
 * screen towards the rider. The heading wanted is where the top of the screen points: lying flat
 * that is the usual azimuth; upright, the axes are remapped so the azimuth is that of the
 * screen's top edge rather than of its back.
 *
 * Readings are smoothed (a heading on a bike jitters) and corrected from magnetic to true north,
 * like the GPS course.
 */
object Compass : SensorEventListener {
    private var manager: SensorManager? = null
    private val r = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private var sx = 0.0
    private var sy = 0.0
    private var started = false

    /** Magnetic declination at the rider's position, degrees (east positive). */
    var declinationDeg = 0f

    private val _heading = MutableStateFlow<Float?>(null)
    /** Smoothed true heading of the top of the screen, degrees from north; null when not running. */
    val heading: StateFlow<Float?> = _heading

    fun start(context: Context) {
        if (started || !Settings.current.value.compassWhenStopped) return
        val m = context.applicationContext.getSystemService(SensorManager::class.java) ?: return
        val sensor = m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        manager = m
        started = m.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!started) return
        manager?.unregisterListener(this)
        started = false
        sx = 0.0; sy = 0.0
        _heading.value = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(r, event.values)
        // r[8]: how much the screen faces up. Mostly upright → remap so the azimuth is the top edge's.
        val m = if (abs(r[8]) < 0.7f) {
            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            remapped
        } else r
        SensorManager.getOrientation(m, orientation)
        val az = Math.toDegrees(orientation[0].toDouble()) + declinationDeg
        // Smooth on the unit circle, so 359° and 1° average to 0°, not 180°.
        val a = Math.toRadians(az)
        val k = 0.12
        if (sx == 0.0 && sy == 0.0) {
            sx = cos(a); sy = sin(a)
        } else {
            sx += k * (cos(a) - sx)
            sy += k * (sin(a) - sy)
        }
        _heading.value = ((Math.toDegrees(atan2(sy, sx)) + 360) % 360).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
