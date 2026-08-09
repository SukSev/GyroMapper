package com.gyromapper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

private const val TAG = "GyroMapperAccessibility"

class GyroMapperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: GyroMapperAccessibilityService? = null
            private set
    }

    private var windowManager: WindowManager? = null

    // Track current foreground package
    var currentForegroundPackage: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        Log.d(TAG, "Accessibility service connected")

        // Get WindowManager for future gesture injection
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Set up accessibility service info
        serviceInfo = accessibilityServiceInfo.apply {
            // We want to intercept key and motion events
            flags = flags or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE

            // Listen for window state changes to detect foreground app
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

            // We want to handle key and motion events
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_KEY_EVENTS
            }
        }

        // Start the main service
        val intent = android.content.Intent(this, GyroMapperService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Detect foreground app changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.let { pkg ->
                if (pkg.toString() != currentForegroundPackage) {
                    currentForegroundPackage = pkg.toString()
                    Log.d(TAG, "Foreground app changed: $currentForegroundPackage")
                    // Notify service of foreground change
                    GyroMapperService.currentForegroundPackage = currentForegroundPackage
                }
            }
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)

        // Only process gamepad events (source == SOURCE_GAMEPAD or SOURCE_JOYSTICK)
        val source = event.source
        if (source and android.view.InputDevice.SOURCE_GAMEPAD == 0 &&
            source and android.view.InputDevice.SOURCE_JOYSTICK == 0) {
            return super.onKeyEvent(event)
        }

        // Forward key events to the aggregator via the main service
        val service = GyroMapperService.instance
        if (service != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    service.onGamepadButtonPressed(event.keyCode)
                    Log.d(TAG, "Gamepad button DOWN: ${event.keyCode}")
                }
                KeyEvent.ACTION_UP -> {
                    service.onGamepadButtonReleased(event.keyCode)
                    Log.d(TAG, "Gamepad button UP: ${event.keyCode}")
                }
            }
        }

        // Don't consume the event - let it pass through to the game
        return super.onKeyEvent(event)
    }

    override fun onMotionEvent(event: MotionEvent?): Boolean {
        if (event == null) return super.onMotionEvent(event)

        // Handle joystick motion events
        val source = event.source
        if (source and android.view.InputDevice.SOURCE_JOYSTICK == 0) {
            return super.onMotionEvent(event)
        }

        // Get joystick axes
        val deviceId = event.deviceId
        val inputDevice = android.view.InputDevice.getDevice(deviceId)

        if (inputDevice != null) {
            // Left stick: AXIS_X, AXIS_Y
            val leftX = event.getAxisValue(MotionEvent.AXIS_X)
            val leftY = event.getAxisValue(MotionEvent.AXIS_Y)

            // Right stick: AXIS_Z (often X), AXIS_RZ (often Y)
            val rightX = event.getAxisValue(MotionEvent.AXIS_Z)
            val rightY = event.getAxisValue(MotionEvent.AXIS_RZ)

            // Also try alternative mappings for some controllers
            val rightXAlt = event.getAxisValue(MotionEvent.AXIS_RX)
            val rightYAlt = event.getAxisValue(MotionEvent.AXIS_RY)

            // Use the non-zero ones
            val rx = if (kotlin.math.abs(rightX) > 0.01f) rightX else rightXAlt
            val ry = if (kotlin.math.abs(rightY) > 0.01f) rightY else rightYAlt

            val service = GyroMapperService.instance
            if (service != null) {
                service.onJoystickUpdate(leftX, leftY, rx, ry)
            }

            if (kotlin.math.abs(leftX) > 0.1f || kotlin.math.abs(leftY) > 0.1f ||
                kotlin.math.abs(rx) > 0.1f || kotlin.math.abs(ry) > 0.1f) {
                Log.d(TAG, "Joystick: L($leftX, $leftY) R($rx, $ry)")
            }
        }

        return super.onMotionEvent(event)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        Log.d(TAG, "Accessibility service destroyed")
        instance = null
        GyroMapperService.instance?.stopSelf()
        super.onDestroy()
    }

    /**
     * Perform a touch gesture injection (future use for fallback backend).
     * Superseded by TouchInjectionBackend - safe to delete once you've
     * wired that in.
     */
    @Suppress("unused")
    fun injectTouch(x: Float, y: Float, durationMs: Long = 16) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
