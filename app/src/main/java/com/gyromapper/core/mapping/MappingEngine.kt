package com.gyromapper.core.mapping

import com.gyromapper.core.data.UnifiedInputState
import android.view.KeyEvent

class MappingEngine {
    enum class ActivationMode {
        ALWAYS_ON,
        HOLD_BUTTON,
        TOGGLE_BUTTON
    }

    // Configuration with custom setter to reset toggle state when mode changes
    var activationMode: ActivationMode = ActivationMode.ALWAYS_ON
        set(value) {
            field = value
            if (value != ActivationMode.TOGGLE_BUTTON) {
                toggledOn = false
            }
            lastPressed = false
        }

    var activationKeyCode: Int = KeyEvent.KEYCODE_BUTTON_L1

    private var toggledOn: Boolean = false
    private var lastPressed: Boolean = false

    fun isGyroActive(state: UnifiedInputState): Boolean {
        return when (activationMode) {
            ActivationMode.ALWAYS_ON -> true
            ActivationMode.HOLD_BUTTON -> state.gamepadButtons[activationKeyCode] == true
            ActivationMode.TOGGLE_BUTTON -> toggledOn
        }
    }

    fun onButtonStateChanged(keyCode: Int, pressed: Boolean) {
        if (keyCode != activationKeyCode) return

        if (activationMode == ActivationMode.TOGGLE_BUTTON && pressed && !lastPressed) {
            toggledOn = !toggledOn
        }
        lastPressed = pressed
    }
}