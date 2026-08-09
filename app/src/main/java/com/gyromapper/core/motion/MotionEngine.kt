package com.gyromapper.core.motion

import com.gyromapper.core.data.MotionDelta
import kotlin.math.sqrt

class MotionEngine(
    private var sensitivity: Float = 1.0f,
    private val calibrationThreshold: Float = 0.05f,
    private val calibrationDurationMs: Long = 2000L
) {
    enum class CalibrationState { IDLE, COLLECTING, CALIBRATED }

    @Volatile
    private var state: CalibrationState = CalibrationState.IDLE
    private val calibrationSamples = mutableListOf<Pair<Float, Float>>()
    private var calibrationStartTime: Long = 0L
    private var biasX: Float = 0f
    private var biasY: Float = 0f

    private val filter = OneEuroFilter2D(
        minCutoff = 1.0,
        beta = 0.007,
        dCutoff = 1.0
    )

    private var lastFilteredX: Float = 0f
    private var lastFilteredY: Float = 0f
    private var lastTimestampSec: Double = 0.0

    // Tracks the previous sample's timestamp so raw gyro rate (rad/s) can
    // be converted into an actual angular delta for this sample. Without
    // this, apparent motion speed scales with how often samples arrive,
    // not just with physical rotation speed - which matters a lot once a
    // second source with a different sample rate exists.
    private var lastSampleTimeNanos: Long = 0L

    @Synchronized
    fun process(rawDx: Float, rawDy: Float, timestampNanos: Long): MotionDelta {
        val dt = if (lastSampleTimeNanos == 0L) {
            0f
        } else {
            (timestampNanos - lastSampleTimeNanos) / 1_000_000_000f
        }
        lastSampleTimeNanos = timestampNanos

        val timestampSec = timestampNanos / 1_000_000_000.0
        val calibrated = calibrate(rawDx, rawDy, timestampNanos)

        val filtered = if (state == CalibrationState.CALIBRATED) {
            val (fx, fy) = filter.filter(calibrated.first.toDouble(), calibrated.second.toDouble(), timestampSec)
            lastFilteredX = fx.toFloat()
            lastFilteredY = fy.toFloat()
            lastTimestampSec = timestampSec
            lastFilteredX to lastFilteredY
        } else {
            0f to 0f
        }

        val scaledDx = filtered.first * dt * sensitivity
        val scaledDy = filtered.second * dt * sensitivity
        return MotionDelta(scaledDx, scaledDy, timestampNanos)
    }

    @Synchronized
    private fun calibrate(rawDx: Float, rawDy: Float, timestampNanos: Long): Pair<Float, Float> {
        val magnitude = sqrt(rawDx * rawDx + rawDy * rawDy)

        when (state) {
            CalibrationState.IDLE -> {
                if (magnitude < calibrationThreshold) {
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
                    val elapsed = timestampNanos - calibrationStartTime
                    if (elapsed >= calibrationDurationMs * 1_000_000L) {
                        val avgX = calibrationSamples.map { it.first }.average().toFloat()
                        val avgY = calibrationSamples.map { it.second }.average().toFloat()
                        biasX = avgX
                        biasY = avgY
                        state = CalibrationState.CALIBRATED
                        filter.reset()
                        lastFilteredX = 0f
                        lastFilteredY = 0f
                        android.util.Log.d("MotionEngine", "Calibrated: biasX=$biasX, biasY=$biasY")
                    }
                } else {
                    state = CalibrationState.IDLE
                    calibrationSamples.clear()
                }
                return rawDx to rawDy
            }

            CalibrationState.CALIBRATED -> {
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
        lastSampleTimeNanos = 0L
    }
}
