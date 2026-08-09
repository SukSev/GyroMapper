package com.gyromapper.core.backends

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import com.gyromapper.core.data.CameraDelta

private const val TAG = "TouchInjectionBackend"

/**
 * Injects CameraDelta as a continuous synthetic touch drag via
 * dispatchGesture()/StrokeDescription#continueStroke, targeting an
 * app's relative-touch-cursor input mode (e.g. Winlator-family apps).
 *
 * This is a fresh implementation of the continueStroke chaining
 * technique - not a port of your existing working implementation in
 * the other app. The edge-of-region handling here is a simple clamp,
 * not any seamless recenter-without-lifting behavior you may have
 * already solved elsewhere. Treat this as a starting point to
 * validate, or replace the internals with your proven code.
 *
 * @param service the connected AccessibilityService instance to dispatch through.
 * @param touchRegion the on-screen rectangle to drag within, in pixels.
 * @param segmentDurationMs how long each chained stroke segment lasts -
 *   shorter tracks deltas more responsively but dispatches more often.
 */
class TouchInjectionBackend(
    private val service: AccessibilityService,
    private val touchRegion: RectF,
    private val segmentDurationMs: Long = 16L
) : OutputBackend {

    private var currentX: Float = 0f
    private var currentY: Float = 0f
    private var lastStroke: GestureDescription.StrokeDescription? = null
    private var isDown = false

    override fun onStart() {
        currentX = (touchRegion.left + touchRegion.right) / 2f
        currentY = (touchRegion.top + touchRegion.bottom) / 2f
        lastStroke = null
        isDown = false
        Log.d(TAG, "Started, anchor=($currentX, $currentY)")
    }

    override fun onStop() {
        release()
    }

    override fun send(cameraDelta: CameraDelta) {
        currentX = (currentX + cameraDelta.dx).coerceIn(touchRegion.left, touchRegion.right)
        currentY = (currentY + cameraDelta.dy).coerceIn(touchRegion.top, touchRegion.bottom)

        val path = Path().apply { moveTo(currentX, currentY) }
        val stroke = lastStroke?.continueStroke(path, 0, segmentDurationMs, true)
            ?: GestureDescription.StrokeDescription(path, 0, segmentDurationMs, true)

        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = service.dispatchGesture(gesture, null, null)
        if (dispatched) {
            lastStroke = stroke
            isDown = true
        } else {
            Log.w(TAG, "dispatchGesture failed, dropping this delta")
        }
    }

    /** Force-lifts the synthetic touch. Called on gyro deactivation and on stop. */
    override fun release() {
        val stroke = lastStroke ?: return
        if (!isDown) return
        val path = Path().apply { moveTo(currentX, currentY) }
        val endStroke = stroke.continueStroke(path, 0, 1L, false)
        val gesture = GestureDescription.Builder().addStroke(endStroke).build()
        service.dispatchGesture(gesture, null, null)
        lastStroke = null
        isDown = false
        Log.d(TAG, "Released")
    }

    override fun isConnected(): Boolean = true
}
