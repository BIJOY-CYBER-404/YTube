package com.example.debug

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Accelerometer-based shake detector that opens the Debug & Bug Report screen
 * when the user gives their device a crisp shake. Requires zero runtime permissions.
 */
class ShakeDetector(
    context: Context,
    private val onShakeDetected: () -> Unit,
) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastSpikeMs: Long = 0L
    private var lastTriggerMs: Long = 0L
    private var spikeCount: Int = 0
    private var isListening: Boolean = false

    fun start() {
        if (isListening || accelerometer == null) return
        isListening = sensorManager?.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_UI,
        ) == true
    }

    fun stop() {
        if (!isListening) return
        sensorManager?.unregisterListener(this)
        isListening = false
        spikeCount = 0
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val values = event?.values ?: return
        if (values.size < 3) return

        val gForce = computeGForce(values[0], values[1], values[2])
        if (gForce >= SHAKE_THRESHOLD_G) {
            val now = System.currentTimeMillis()
            if (now - lastTriggerMs < COOLDOWN_AFTER_TRIGGER_MS) {
                return
            }
            if (now - lastSpikeMs > SPIKE_WINDOW_MS) {
                spikeCount = 0
            }
            lastSpikeMs = now
            spikeCount++

            if (spikeCount >= REQUIRED_SPIKES) {
                spikeCount = 0
                lastTriggerMs = now
                onShakeDetected()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No action required.
    }

    companion object {
        internal const val SHAKE_THRESHOLD_G = 2.35f
        internal const val SPIKE_WINDOW_MS = 650L
        internal const val REQUIRED_SPIKES = 2
        internal const val COOLDOWN_AFTER_TRIGGER_MS = 2_500L

        internal fun computeGForce(x: Float, y: Float, z: Float): Float {
            val gX = x / SensorManager.GRAVITY_EARTH
            val gY = y / SensorManager.GRAVITY_EARTH
            val gZ = z / SensorManager.GRAVITY_EARTH
            return sqrt(gX * gX + gY * gY + gZ * gZ)
        }
    }
}
