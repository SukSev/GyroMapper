package com.gyromapper.core.aggregator

import com.gyromapper.core.data.GyroSource
import com.gyromapper.core.data.UnifiedInputState
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe aggregator that holds the current UnifiedInputState.
 * Uses AtomicReference for lock-free updates.
 */
class InputAggregator {
    private val stateRef = AtomicReference(UnifiedInputState())

    /**
     * Fired after every updateGyro() call. Lets gyro sources (Odin IMU,
     * future 8BitDo HID) drive processing without needing a reference
     * back to whatever orchestrates it - they only ever talk to the
     * aggregator.
     */
    var onGyroUpdate: (() -> Unit)? = null

    /**
     * Which source is currently allowed to drive gyroDelta. Only one
     * gyro source is ever meant to be live at a time - GyroMapperService
     * enforces that by starting/stopping readers on switch, and this is
     * the belt-and-suspenders backstop: updateGyro() drops anything that
     * doesn't match, so a reader that's mid-stop (or that nobody ever
     * selected) can't clobber the active source's state.
     */
    @Volatile
    private var activeSource: GyroSource = GyroSource.ODIN_IMU

    fun setActiveGyroSource(source: GyroSource) {
        activeSource = source
    }

    fun getActiveGyroSource(): GyroSource = activeSource

    /**
     * Get a snapshot of the current state.
     */
    fun getState(): UnifiedInputState = stateRef.get()

    /**
     * Update the state atomically.
     */
    fun update(updater: (UnifiedInputState) -> UnifiedInputState) {
        var old: UnifiedInputState
        var new: UnifiedInputState
        do {
            old = stateRef.get()
            new = updater(old)
        } while (!stateRef.compareAndSet(old, new))
    }

    // Convenience methods

    fun updateGyro(dx: Float, dy: Float, timestamp: Long, source: GyroSource) {
        if (source != activeSource) {
            // Stale data from a source that isn't the selected one
            // (e.g. briefly still shutting down after a switch) - drop it
            // rather than let it clobber the active source's state.
            return
        }
        update { state ->
            state.copy(
                gyroDelta = dx to dy,
                gyroSource = source,
                timestamp = timestamp
            )
        }
        onGyroUpdate?.invoke()
    }

    fun updateButton(keyCode: Int, pressed: Boolean) {
        update { state ->
            val newButtons = state.gamepadButtons.toMutableMap()
            if (pressed) {
                newButtons[keyCode] = true
            } else {
                newButtons.remove(keyCode)
            }
            state.copy(gamepadButtons = newButtons)
        }
    }

    fun updateLeftStick(x: Float, y: Float) {
        update { it.copy(leftStick = x to y) }
    }

    fun updateRightStick(x: Float, y: Float) {
        update { it.copy(rightStick = x to y) }
    }

    fun clearAll() {
        update { UnifiedInputState() }
    }
}
