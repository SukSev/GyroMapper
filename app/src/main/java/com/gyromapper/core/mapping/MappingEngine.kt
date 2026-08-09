package com.gyromapper.core.mapping

import com.gyromapper.core.data.UnifiedInputState
import android.view.KeyEvent

/**
 * MappingEngine: controls when gyro is active based on activation mode.
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
    private var lastPressed: Boolean = false

    /**
     * Determine if gyro should be active based on current input state.
     * @return true if gyro mapping is active
     */
    fun isGyroActive(state: UnifiedInputState): Boolean {
        return when (activationMode) {
            ActivationMode.ALWAYS_ON -> true
            ActivationMode.HOLD_BUTTON -> state.gamepadButtons[activationKeyCode] == true
            ActivationMode.TOGGLE_BUTTON -> toggledOn
        }
    }

    /**
     * Called when button state changes - used for toggle mode edge detection.
     * Only flips on the false -> true transition, so this is safe to call
     * on every button event (including repeats) without spamming the toggle.
     */
    fun onButtonStateChanged(keyCode: Int, pressed: Boolean) {
        if (keyCode != activationKeyCode) return

        if (activationMode == ActivationMode.TOGGLE_BUTTON && pressed && !lastPressed) {
            toggledOn = !toggledOn
        }
        lastPressed = pressed
    }

    fun setActivationMode(mode: ActivationMode) {
        this.activationMode = mode
        if (mode != ActivationMode.TOGGLE_BUTTON) {
            toggledOn = false
        }
        lastPressed = false
    }
}
