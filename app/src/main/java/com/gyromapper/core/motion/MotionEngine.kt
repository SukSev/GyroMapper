package com.gyromapper.core.motion

import com.gyromapper.core.data.MotionDelta
import kotlin.math.sqrt

/**
 * MotionEngine: processes raw gyro data through:
 * 1. Auto-calibration (bias subtraction)
 * 2. 1€ filter
 * 3. Sensitivity scaling
 *
 * Thread-safe: uses @Synchronized for state protection.
 */
class MotionEngine(
    private var sensitivity: Float = 1.0f, // pixels per rad/s (placeholder)
    private val calibrationThreshold: Float = 0.05f, // rad/s
    private val calibrationDurationMs: Long = 2000L // 2 seconds
) {
    enum class CalibrationState {
        IDLE,
        COLLECTING,
        CALIBRATED
    }

    @Volatile
    private var state: CalibrationState = CalibrationState.IDLE

    private val calibrationSamples = mutableListOf<Pair<Float, Float>>()
    private var calibrationStartTime: Long = 0L
    private var biasX: Float = 0f
    private var biasY: Float = 0f

    // 1€ filter for x and y
    private val filter = OneEuroFilter2D(
        minCutoff = 1.0,
        beta = 0.007,
        dCutoff = 1.0
    )

    // Last known filtered value (for continuity)
    private var lastFilteredX: Float = 0f
    private var lastFilteredY: Float = 0f
    private var lastTimestampSec: Double = 0.0

    @Synchronized
    fun process(rawDx: Float, rawDy: Float, timestampNanos: Long): MotionDelta {
        val timestampSec = timestampNanos / 1_000_000_000.0

        // Step 1: Auto-calibration
        val calibrated = calibrate(rawDx, rawDy, timestampNanos)

        // Step 2: Apply 1€ filter
        val filtered = if (state == CalibrationState.CALIBRATED) {
            val (fx, fy) = filter.filter(calibrated.first.toDouble(), calibrated.second.toDouble(), timestampSec)
            lastFilteredX = fx.toFloat()
            lastFilteredY = fy.toFloat()
            lastTimestampSec = timestampSec
            lastFilteredX to lastFilteredY
        } else {
            // Before calibration, just pass through raw (or zero)
            0f to 0f
        }

        // Step 3: Apply sensitivity
        val scaledDx = filtered.first * sensitivity
        val scaledDy = filtered.second * sensitivity

        return MotionDelta(scaledDx, scaledDy, timestampNanos)
    }

    @Synchronized
    private fun calibrate(rawDx: Float, rawDy: Float, timestampNanos: Long): Pair<Float, Float> {
        val magnitude = sqrt(rawDx * rawDx + rawDy * rawDy)

        when (state) {
            CalibrationState.IDLE -> {
                if (magnitude < calibrationThreshold) {
                    // Device is still - start collecting
                    state = CalibrationState.COLLECTING
                    calibrationStartTime = timestampNanos
                    calibrationSamples.clear()
                    calibrationSamples.add(rawDx to rawDy)
                }
                return rawDx to rawDy
            }

            CalibrationState.COLLECTING -> {
                if (magnitude < calibrationThreshold) {
                    calibrationSamples.add(rawDx to rawDy)

                    // Check if we've collected enough samples
                    val elapsed = timestampNanos - calibrationStartTime
                    if (elapsed >= calibrationDurationMs * 1_000_000L) {
                        // Compute bias
                        val avgX = calibrationSamples.map { it.first }.average().toFloat()
                        val avgY = calibrationSamples.map { it.second }.average().toFloat()
                        biasX = avgX
                        biasY = avgY
                        state = CalibrationState.CALIBRATED

                        // Reset filter to avoid jump
                        filter.reset()
                        lastFilteredX = 0f
                        lastFilteredY = 0f

                        android.util.Log.d("MotionEngine", "Calibrated: biasX=$biasX, biasY=$biasY")
                    }
                } else {
                    // Movement detected - abort calibration
                    state = CalibrationState.IDLE
                    calibrationSamples.clear()
                }
                return rawDx to rawDy
            }

            CalibrationState.CALIBRATED -> {
                // Subtract bias
                return (rawDx - biasX) to (rawDy - biasY)
            }
        }
    }

    @Synchronized
    fun setSensitivity(newSensitivity: Float) {
        sensitivity = newSensitivity
    }

    @Synchronized
    fun getCalibrationState(): CalibrationState = state

    @Synchronized
    fun resetCalibration() {
        state = CalibrationState.IDLE
        calibrationSamples.clear()
        biasX = 0f
        biasY = 0f
        filter.reset()
        lastFilteredX = 0f
        lastFilteredY = 0f
        android.util.Log.d("MotionEngine", "Calibration reset")
    }
}