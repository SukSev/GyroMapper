package com.gyromapper.core.mapping

import com.gyromapper.core.data.UnifiedInputState
import android.view.KeyEvent

/**
 * MappingEngine: controls when gyro is active based on activation mode.
 *
 * Phase 0: always active (no button hold required).
 * Future: toggle mode, hold mode with configurable buttons.
 */
class MappingEngine {
    enum class ActivationMode {
        ALWAYS_ON,
        HOLD_BUTTON,
        TOGGLE_BUTTON
    }

    // Configuration
    var activationMode: ActivationMode = ActivationMode.ALWAYS_ON
    var activationKeyCode: Int = KeyEvent.KEYCODE_BUTTON_L1 // Default: left bumper

    // Toggle state
    private var toggledOn: Boolean = false

    /**
     * Determine if gyro should be active based on current input state.
     * @return true if gyro mapping is active
     */
    fun isGyroActive(state: UnifiedInputState): Boolean {
        return when (activationMode) {
            ActivationMode.ALWAYS_ON -> true

            ActivationMode.HOLD_BUTTON -> {
                state.gamepadButtons[activationKeyCode] == true
            }

            ActivationMode.TOGGLE_BUTTON -> {
                val isPressed = state.gamepadButtons[activationKeyCode] == true
                // This is a simplified toggle - in production we'd need edge detection
                // For Phase 0, just return the current toggle state
                toggledOn
            }
        }
    }

    /**
     * Called when button state changes - used for toggle mode edge detection.
     * In Phase 0, this is a placeholder.
     */
    fun onButtonStateChanged(keyCode: Int, pressed: Boolean) {
        if (activationMode == ActivationMode.TOGGLE_BUTTON && keyCode == activationKeyCode) {
            if (pressed) {
                // Edge detection would go here
                // For Phase 0, simple toggle on each press
                toggledOn = !toggledOn
            }
        }
    }

    fun setActivationMode(mode: ActivationMode) {
        this.activationMode = mode
        if (mode != ActivationMode.TOGGLE_BUTTON) {
            toggledOn = false
        }
    }
}