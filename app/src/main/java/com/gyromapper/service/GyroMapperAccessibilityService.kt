package com.gyromapper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
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
    var currentForegroundPackage: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        serviceInfo = serviceInfo.apply {
            flags = flags or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE

            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            }
        }

        val intent = android.content.Intent(this, GyroMapperService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.let { pkg ->
                if (pkg.toString() != currentForegroundPackage) {
                    currentForegroundPackage = pkg.toString()
                    Log.d(TAG, "Foreground app changed: $currentForegroundPackage")
                    GyroMapperService.currentForegroundPackage = currentForegroundPackage
                }
            }
        }
    }

    // onKeyEvent returns Boolean (super does too)
    override fun onKeyEvent(event: KeyEvent): Boolean {
        val source = event.source
        if (source and android.view.InputDevice.SOURCE_GAMEPAD == 0 &&
            source and android.view.InputDevice.SOURCE_JOYSTICK == 0) {
            return super.onKeyEvent(event)
        }

        val service = GyroMapperService.instance
        service?.let {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    it.onGamepadButtonPressed(event.keyCode)
                    Log.d(TAG, "Gamepad button DOWN: ${event.keyCode}")
                }
                KeyEvent.ACTION_UP -> {
                    it.onGamepadButtonReleased(event.keyCode)
                    Log.d(TAG, "Gamepad button UP: ${event.keyCode}")
                }
            }
        }

        return super.onKeyEvent(event)
    }

    // onMotionEvent returns Unit (void) – do NOT return Boolean
    override fun onMotionEvent(event: MotionEvent) {
        val source = event.source
        if (source and android.view.InputDevice.SOURCE_JOYSTICK == 0) {
            super.onMotionEvent(event)
            return
        }

        val deviceId = event.deviceId
        val inputDevice = android.view.InputDevice.getDevice(deviceId)

        if (inputDevice != null) {
            val leftX = event.getAxisValue(MotionEvent.AXIS_X)
            val leftY = event.getAxisValue(MotionEvent.AXIS_Y)

            val rightX = event.getAxisValue(MotionEvent.AXIS_Z)
            val rightY = event.getAxisValue(MotionEvent.AXIS_RZ)

            val rightXAlt = event.getAxisValue(MotionEvent.AXIS_RX)
            val rightYAlt = event.getAxisValue(MotionEvent.AXIS_RY)

            val rx = if (kotlin.math.abs(rightX) > 0.01f) rightX else rightXAlt
            val ry = if (kotlin.math.abs(rightY) > 0.01f) rightY else rightYAlt

            val service = GyroMapperService.instance
            service?.onJoystickUpdate(leftX, leftY, rx, ry)

            if (kotlin.math.abs(leftX) > 0.1f || kotlin.math.abs(leftY) > 0.1f ||
                kotlin.math.abs(rx) > 0.1f || kotlin.math.abs(ry) > 0.1f) {
                Log.d(TAG, "Joystick: L($leftX, $leftY) R($rx, $ry)")
            }
        }

        // Do NOT call super.onMotionEvent(event) because it would cause an infinite loop?
        // The super implementation is empty, so we can omit it, or call it after processing.
        // Actually, AccessibilityService.onMotionEvent is a no-op, so we can call it or not.
        // I'll call it to be safe.
        super.onMotionEvent(event)
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

    @Suppress("unused")
    fun injectTouch(x: Float, y: Float, durationMs: Long = 16) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }
}