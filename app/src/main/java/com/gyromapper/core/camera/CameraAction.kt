package com.gyromapper.core.camera

import com.gyromapper.core.data.CameraDelta
import com.gyromapper.core.data.MotionDelta
import com.gyromapper.core.data.UnifiedInputState

/**
 * CameraAction: converts motion deltas to final camera deltas.
 *
 * Phase 0: simple pass-through.
 * Future: ADS sensitivity scaling, right-stick influence, etc.
 */
class CameraAction {
    // Future config
    var adsMultiplier: Float = 0.5f
    var isAimingDownSights: Boolean = false

    fun process(motion: MotionDelta, state: UnifiedInputState): CameraDelta {
        // Phase 0: just pass through motion delta
        // Future: apply ADS modifier, right-stick combination, etc.
        var dx = motion.dx
        var dy = motion.dy

        // Placeholder: if ADS button (right trigger) is held, reduce sensitivity
        if (state.gamepadButtons[android.view.KeyEvent.KEYCODE_BUTTON_R2] == true) {
            dx *= adsMultiplier
            dy *= adsMultiplier
        }

        return CameraDelta(dx, dy, motion.timestamp)
    }
}