package com.gyromapper.core.data

enum class GyroSource {
    NONE,
    ODIN_IMU,
    EIGHTBITDO
}

/**
 * Single source of truth for all input state.
 * Thread-safe: all fields are immutable (data class copy).
 */
data class UnifiedInputState(
    // Gamepad buttons: keyCode -> isPressed
    val gamepadButtons: Map<Int, Boolean> = emptyMap(),
    // Left stick: x, y in [-1.0, 1.0]
    val leftStick: Pair<Float, Float> = 0f to 0f,
    // Right stick: x, y in [-1.0, 1.0]
    val rightStick: Pair<Float, Float> = 0f to 0f,
    // Gyro source selection
    val gyroSource: GyroSource = GyroSource.NONE,
    // Raw gyro delta in rad/s (from sensor)
    val gyroDelta: Pair<Float, Float> = 0f to 0f,
    // Timestamp in nanoseconds
    val timestamp: Long = 0L
) {
    fun isGyroActive(): Boolean = gyroSource != GyroSource.NONE
}

/**
 * Motion delta after filtering and sensitivity scaling.
 * Units: pixels (or arbitrary units for now)
 */
data class MotionDelta(
    val dx: Float,
    val dy: Float,
    val timestamp: Long
)

/**
 * Final camera delta after applying ADS modifiers and stick influence.
 */
data class CameraDelta(
    val dx: Float,
    val dy: Float,
    val timestamp: Long
)