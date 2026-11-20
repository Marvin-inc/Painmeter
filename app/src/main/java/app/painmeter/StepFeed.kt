package app.painmeter

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Running cadence from the phone's step detector (SPEC §16.2): one event per step, counted here
 * and recorded once a second by [RideController]. Works in a pocket, an armband or a belt; needs
 * the "physical activity" permission. Phones without the sensor simply give no cadence.
 */
class StepFeed(context: Context, private val onStep: (count: Int) -> Unit) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val detector: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    val available: Boolean get() = detector != null

    fun start() {
        detector?.let { sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    fun stop() {
        sensors?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Each event is one step (values[0] = 1.0); some phones batch several into one event.
        onStep(event.values.firstOrNull()?.toInt()?.coerceAtLeast(1) ?: 1)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
