package com.gyromapper.core.controller

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.gyromapper.core.aggregator.InputAggregator
import com.gyromapper.core.data.GyroSource

private const val TAG = "OdinIMUReader"

/**
 * Reads gyroscope data from the built-in IMU via SensorManager.
 * Runs on the sensor thread - callbacks are already on a dedicated thread.
 */
class OdinIMUReader(
    private val context: Context,
    private val aggregator: InputAggregator
) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var gyroSensor: Sensor? = null
    private var isListening = false

    fun start() {
        if (isListening) return

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (gyroSensor == null) {
            Log.e(TAG, "No gyroscope sensor found on this device!")
            return
        }

        val success = sensorManager?.registerListener(
            this,
            gyroSensor,
            SensorManager.SENSOR_DELAY_GAME  // ~20ms (50Hz) - good balance for gaming
        ) ?: false

        if (success) {
            isListening = true
            Log.d(TAG, "Gyro listener registered successfully")
        } else {
            Log.e(TAG, "Failed to register gyro listener")
        }
    }

    fun stop() {
        if (!isListening) return
        sensorManager?.unregisterListener(this)
        isListening = false
        Log.d(TAG, "Gyro listener unregistered")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        // Gyroscope returns angular velocity in rad/s around x, y, z axes
        // For aiming: pitch (x) maps to vertical, yaw (y) maps to horizontal
        // Note: Android's coordinate system: x = pitch (tilt forward/back),
        // y = roll (tilt left/right), z = yaw (rotation)
        // For FPS: we want yaw (z) for horizontal, pitch (x) for vertical
        val rawX = event.values[0]  // pitch
        val rawY = event.values[1]  // roll
        val rawZ = event.values[2]  // yaw

        // Map to camera controls: yaw -> horizontal (dx), pitch -> vertical (dy)
        // Negate pitch so tilting forward = look up
        val dx = rawZ   // yaw
        val dy = -rawX  // pitch (inverted)

        val timestamp = event.timestamp

        // Update aggregator
        aggregator.updateGyro(dx, dy, timestamp, GyroSource.ODIN_IMU)

        // Log occasionally
        if (System.currentTimeMillis() % 5000 < 50) {
            Log.d(TAG, "Gyro: dx=$dx, dy=$dy")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for gyro
    }

    fun isAvailable(): Boolean = gyroSensor != null
}