package com.zhuo.c1cam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.abs

class DeviceOrientationManager(
    context: Context,
    private val onRotationChanged: (Int) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val activeSensor = gravitySensor ?: accelerometer
    private val filteredValues = FloatArray(3)

    private var hasFilteredValue = false
    private var currentRotation: Int? = null
    private var candidateRotation = Surface.ROTATION_0
    private var candidateSamples = 0
    private var isStarted = false

    val isAvailable: Boolean
        get() = activeSensor != null

    fun start() {
        if (isStarted) return
        val sensor = activeSensor ?: return
        // The activity may have reset its controls while this listener was stopped.
        // Force the first stable sample of every foreground session to be delivered
        // instead of keeping the controls at the previous session's angle.
        currentRotation = null
        candidateRotation = Surface.ROTATION_0
        candidateSamples = 0
        hasFilteredValue = false
        isStarted = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!isStarted) return
        sensorManager.unregisterListener(this)
        isStarted = false
        hasFilteredValue = false
        candidateSamples = 0
        currentRotation = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.values.size < 3) return

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            if (!hasFilteredValue) {
                event.values.copyInto(filteredValues, endIndex = 3)
                hasFilteredValue = true
            } else {
                for (index in 0..2) {
                    filteredValues[index] =
                        ACCELEROMETER_FILTER_ALPHA * filteredValues[index] +
                            (1f - ACCELEROMETER_FILTER_ALPHA) * event.values[index]
                }
            }
        } else {
            event.values.copyInto(filteredValues, endIndex = 3)
            hasFilteredValue = true
        }

        val x = filteredValues[0]
        val y = filteredValues[1]
        val z = filteredValues[2]
        val horizontalGravity = maxOf(abs(x), abs(y))

        // Keep the previous orientation while the phone is close to lying flat.
        if (horizontalGravity < MIN_HORIZONTAL_GRAVITY || abs(z) > horizontalGravity * FLATNESS_RATIO) {
            candidateSamples = 0
            return
        }

        val detectedRotation = if (abs(x) > abs(y)) {
            // Sensor orientation and Surface target rotation use opposite directions.
            if (x < 0f) Surface.ROTATION_270 else Surface.ROTATION_90
        } else {
            if (y < 0f) Surface.ROTATION_180 else Surface.ROTATION_0
        }

        if (detectedRotation != candidateRotation) {
            candidateRotation = detectedRotation
            candidateSamples = 1
            return
        }

        candidateSamples += 1
        if (candidateSamples >= REQUIRED_STABLE_SAMPLES && detectedRotation != currentRotation) {
            currentRotation = detectedRotation
            candidateSamples = 0
            onRotationChanged(detectedRotation)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val ACCELEROMETER_FILTER_ALPHA = 0.82f
        private const val MIN_HORIZONTAL_GRAVITY = 4.5f
        private const val FLATNESS_RATIO = 1.25f
        private const val REQUIRED_STABLE_SAMPLES = 4
    }
}
