package com.gyromapper.core.backends

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.gyromapper.core.data.CameraDelta

private const val TAG = "TouchInjectionBackend"

/**
 * AccessibilityService-based touch injection backend.
 *
 * Converts relative CameraDelta movement into a continuous Android touch drag.
 *
 * Successive gesture segments are chained with StrokeDescription.continueStroke()
 * so Android sees one continuous finger rather than a sequence of independent
 * taps/drags.
 *
 * Camera deltas are accumulated while a gesture segment is in flight. This
 * allows the gyro/input side to run at a higher frequency than the Android
 * accessibility gesture dispatcher.
 *
 * When the virtual finger reaches the edge of the configured touch region,
 * the current stroke is properly released. Only after Android confirms the
 * release has completed is the virtual finger re-anchored at the centre and
 * a new stroke started.
 *
 * Movement that extends beyond the edge is preserved as overflow rather than
 * replaying the entire original delta.
 */
class TouchInjectionBackend(
    private val service: AccessibilityService,
    private val touchRegion: RectF,
    private val segmentDurationMs: Long = 10L,
    private val edgeMarginPx: Float = 120f
) : OutputBackend {

    private val handlerThread = HandlerThread("TouchInjectionBackend").apply {
        start()
    }

    private val handler = Handler(handlerThread.looper)

    /*
     * All state below is accessed from handlerThread except pendingDx/pendingDy,
     * which are protected by pendingLock because send() may be called from
     * another thread.
     */

    private var currentX = 0f
    private var currentY = 0f

    private var anchorX = 0f
    private var anchorY = 0f

    private var lastStroke: GestureDescription.StrokeDescription? = null

    /**
     * True while Android is executing a gesture segment.
     */
    private var strokeInFlight = false

    /**
     * True while we have dispatched the final continueStroke(..., false)
     * and are waiting for Android to confirm that the finger has actually
     * lifted.
     */
    private var releaseInFlight = false

    /**
     * Overflow movement waiting to be applied after an edge re-anchor.
     */
    private var pendingReanchorDx = 0f
    private var pendingReanchorDy = 0f

    private var pendingDx = 0f
    private var pendingDy = 0f

    private val pendingLock = Any()

    @Volatile
    private var started = false

    @Volatile
    private var connected = true

    override fun onStart() {
        handler.post {
            anchorX = (touchRegion.left + touchRegion.right) / 2f
            anchorY = (touchRegion.top + touchRegion.bottom) / 2f

            currentX = anchorX
            currentY = anchorY

            lastStroke = null

            strokeInFlight = false
            releaseInFlight = false

            pendingReanchorDx = 0f
            pendingReanchorDy = 0f

            synchronized(pendingLock) {
                pendingDx = 0f
                pendingDy = 0f
            }

            started = true
            connected = true

            Log.d(
                TAG,
                "Started, anchor=($anchorX, $anchorY), " +
                        "region=$touchRegion"
            )
        }
    }

    override fun onStop() {
        handler.post {
            started = false

            synchronized(pendingLock) {
                pendingDx = 0f
                pendingDy = 0f
            }

            pendingReanchorDx = 0f
            pendingReanchorDy = 0f

            /*
             * If a gesture is currently executing, we cannot safely inject
             * the release yet. The completion callback will call
             * releaseInternal().
             */
            if (!strokeInFlight && !releaseInFlight) {
                releaseInternal()
            }

            Log.d(TAG, "Stopped")
        }
    }

    /**
     * Submit relative camera movement.
     *
     * Movement is accumulated instead of immediately dispatching a gesture.
     * This allows gyro sampling and gesture injection to operate at different
     * rates.
     */
    override fun send(cameraDelta: CameraDelta) {
        if (!started || !connected) return

        synchronized(pendingLock) {
            pendingDx += cameraDelta.dx
            pendingDy += cameraDelta.dy
        }

        handler.post {
            tryDispatchNext()
        }
    }

    /**
     * Request that the virtual finger be released.
     *
     * The actual release happens asynchronously through Android's gesture
     * callback.
     */
    override fun release() {
        handler.post {
            releaseInternal()
        }
    }

    /**
     * Terminates the currently active continuous stroke.
     *
     * IMPORTANT:
     *
     * We do not consider the finger released merely because dispatchGesture()
     * accepted the release gesture.
     *
     * releaseInFlight remains true until Android invokes onCompleted().
     *
     * This prevents a new stroke from being started while the previous finger
     * is still physically/logically down.
     */
    private fun releaseInternal(
        afterRelease: (() -> Unit)? = null
    ) {
        /*
         * Nothing is currently down.
         */
        val previous = lastStroke ?: run {
            strokeInFlight = false
            releaseInFlight = false

            afterRelease?.invoke()
            return
        }

        /*
         * A normal movement segment is still executing.
         *
         * Its completion callback will retry the release.
         */
        if (strokeInFlight) {
            return
        }

        /*
         * A release has already been dispatched. Do not dispatch another one.
         */
        if (releaseInFlight) {
            return
        }

        val path = Path().apply {
            moveTo(currentX, currentY)
        }

        /*
         * Continue the existing stroke, but with willContinue=false.
         * This is the actual finger lift.
         */
        val releaseStroke = previous.continueStroke(
            path,
            0,
            1L,
            false
        )

        val gesture = GestureDescription.Builder()
            .addStroke(releaseStroke)
            .build()

        releaseInFlight = true

        val dispatched = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {

                override fun onCompleted(
                    gestureDescription: GestureDescription?
                ) {
                    /*
                     * Android has now actually completed the finger lift.
                     *
                     * Only now is it safe to forget the old stroke and
                     * start a new one.
                     */
                    lastStroke = null
                    releaseInFlight = false
                    strokeInFlight = false

                    Log.d(TAG, "Touch release completed")

                    afterRelease?.invoke()
                        ?: tryDispatchNext()
                }

                override fun onCancelled(
                    gestureDescription: GestureDescription?
                ) {
                    Log.w(
                        TAG,
                        "Touch release cancelled"
                    )

                    /*
                     * The old stroke can no longer safely be continued.
                     * Treat it as lost and re-anchor.
                     */
                    lastStroke = null
                    releaseInFlight = false
                    strokeInFlight = false

                    currentX = anchorX
                    currentY = anchorY

                    afterRelease?.invoke()
                        ?: tryDispatchNext()
                }
            },
            handler
        )

        if (!dispatched) {
            /*
             * Android rejected the release request. We cannot safely assume
             * the finger was lifted.
             *
             * Clear the in-flight state so a later attempt can retry.
             */
            releaseInFlight = false

            Log.w(
                TAG,
                "dispatchGesture rejected touch release"
            )

            /*
             * Don't spin continuously. Give Android a moment before retrying.
             */
            handler.postDelayed(
                {
                    releaseInternal(afterRelease)
                },
                segmentDurationMs
            )
        }
    }

    /**
     * Dispatches the next accumulated movement.
     *
     * This method always executes on handlerThread.
     */
    private fun tryDispatchNext() {
        if (!connected) {
            return
        }

        /*
         * If the backend was stopped, don't create any new touch input.
         */
        if (!started) {
            if (!strokeInFlight && !releaseInFlight) {
                releaseInternal()
            }
            return
        }

        /*
         * Never dispatch while a normal gesture segment is executing.
         */
        if (strokeInFlight) {
            return
        }

        /*
         * Never dispatch while Android is still lifting the previous finger.
         */
        if (releaseInFlight) {
            return
        }

        val (dx, dy) = synchronized(pendingLock) {
            val result = pendingDx to pendingDy
            pendingDx = 0f
            pendingDy = 0f
            result
        }

        if (dx == 0f && dy == 0f) {
            return
        }

        val nextX = currentX + dx
        val nextY = currentY + dy

        val usableLeft = touchRegion.left + edgeMarginPx
        val usableRight = touchRegion.right - edgeMarginPx
        val usableTop = touchRegion.top + edgeMarginPx
        val usableBottom = touchRegion.bottom - edgeMarginPx

        val crossesHorizontalEdge =
            nextX < usableLeft || nextX > usableRight

        val crossesVerticalEdge =
            nextY < usableTop || nextY > usableBottom

        if (crossesHorizontalEdge || crossesVerticalEdge) {

            /*
             * Clamp to the usable boundary.
             */
            val clampedX = nextX.coerceIn(
                usableLeft,
                usableRight
            )

            val clampedY = nextY.coerceIn(
                usableTop,
                usableBottom
            )

            /*
             * Movement that actually reaches the boundary.
             */
            val consumedDx = clampedX - currentX
            val consumedDy = clampedY - currentY

            /*
             * Movement remaining beyond the boundary.
             */
            val overflowDx = dx - consumedDx
            val overflowDy = dy - consumedDy

            Log.d(
                TAG,
                "Edge reached: " +
                        "consumed=($consumedDx,$consumedDy), " +
                        "overflow=($overflowDx,$overflowDy)"
            )

            /*
             * If the movement toward the edge is non-zero, first move the
             * finger to the boundary.
             *
             * Once that segment completes, the callback will initiate the
             * release/re-anchor sequence.
             */
            if (consumedDx != 0f || consumedDy != 0f) {
                pendingReanchorDx = overflowDx
                pendingReanchorDy = overflowDy

                dispatchSegment(
                    nextX = clampedX,
                    nextY = clampedY,
                    dx = consumedDx,
                    dy = consumedDy,
                    onCompleted = {
                        beginEdgeReanchor()
                    }
                )
            } else {
                /*
                 * We are already at the boundary and the delta points farther
                 * outside. There is nothing to inject before re-anchoring.
                 */
                pendingReanchorDx = overflowDx
                pendingReanchorDy = overflowDy

                beginEdgeReanchor()
            }

            return
        }

        /*
         * Normal movement: the entire delta fits inside the touch region.
         */
        dispatchSegment(
            nextX = nextX,
            nextY = nextY,
            dx = dx,
            dy = dy,
            onCompleted = null
        )
    }

    /**
     * Starts the edge transition.
     *
     * This method does NOT immediately start a new stroke.
     *
     * It first asks Android to lift the current finger. The next stroke will
     * only be created from the release completion callback.
     */
    private fun beginEdgeReanchor() {
        if (releaseInFlight) {
            return
        }

        val overflowDx = pendingReanchorDx
        val overflowDy = pendingReanchorDy

        pendingReanchorDx = 0f
        pendingReanchorDy = 0f

        releaseInternal(
            afterRelease = {
                /*
                 * Android has confirmed the old finger is gone.
                 *
                 * Now it is safe to establish the new virtual position.
                 */
                currentX = anchorX
                currentY = anchorY

                Log.d(
                    TAG,
                    "Re-anchored at ($anchorX, $anchorY), " +
                            "overflow=($overflowDx, $overflowDy)"
                )

                /*
                 * Apply only the overflow from the original movement.
                 */
                synchronized(pendingLock) {
                    pendingDx += overflowDx
                    pendingDy += overflowDy
                }

                tryDispatchNext()
            }
        )
    }

    /**
     * Dispatches one segment of the continuous touch stroke.
     *
     * nextX/nextY are the absolute destination coordinates.
     * dx/dy are the movement represented by this segment.
     */
    private fun dispatchSegment(
        nextX: Float,
        nextY: Float,
        dx: Float,
        dy: Float,
        onCompleted: (() -> Unit)?
    ) {
        /*
         * Avoid zero-length gestures.
         */
        if (dx == 0f && dy == 0f) {
            currentX = nextX
            currentY = nextY

            onCompleted?.invoke()
                ?: tryDispatchNext()

            return
        }

        val path = Path().apply {
            moveTo(currentX, currentY)
            lineTo(nextX, nextY)
        }

        /*
         * First segment starts a new finger.
         *
         * Subsequent segments continue the same finger.
         */
        val stroke = lastStroke?.continueStroke(
            path,
            0,
            segmentDurationMs,
            true
        ) ?: GestureDescription.StrokeDescription(
            path,
            0,
            segmentDurationMs,
            true
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        strokeInFlight = true

        val accepted = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {

                override fun onCompleted(
                    gestureDescription: GestureDescription?
                ) {
                    /*
                     * Android has completed this segment.
                     *
                     * Only now commit the virtual finger position.
                     */
                    lastStroke = stroke
                    currentX = nextX
                    currentY = nextY

                    strokeInFlight = false

                    onCompleted?.invoke()
                        ?: tryDispatchNext()
                }

                override fun onCancelled(
                    gestureDescription: GestureDescription?
                ) {
                    Log.w(
                        TAG,
                        "Gesture cancelled by platform; " +
                                "re-anchoring"
                    )

                    lastStroke = null
                    strokeInFlight = false
                    releaseInFlight = false

                    currentX = anchorX
                    currentY = anchorY

                    /*
                     * The movement represented by this segment never reached
                     * the target, so preserve it.
                     */
                    synchronized(pendingLock) {
                        pendingDx += dx
                        pendingDy += dy
                    }

                    if (started && connected) {
                        tryDispatchNext()
                    }
                }
            },
            handler
        )

        if (!accepted) {
            Log.w(TAG, "dispatchGesture rejected")

            strokeInFlight = false

            /*
             * Nothing was injected, so preserve the movement.
             */
            synchronized(pendingLock) {
                pendingDx += dx
                pendingDy += dy
            }

            handler.postDelayed(
                {
                    tryDispatchNext()
                },
                segmentDurationMs
            )
        }
    }

    override fun isConnected(): Boolean = connected

    /**
     * Call when the owning AccessibilityService is being destroyed.
     */
    fun destroy() {
        connected = false
        started = false

        synchronized(pendingLock) {
            pendingDx = 0f
            pendingDy = 0f
        }

        pendingReanchorDx = 0f
        pendingReanchorDy = 0f

        handler.post {
            if (!strokeInFlight && !releaseInFlight) {
                releaseInternal()
            }
        }

        handlerThread.quitSafely()

        Log.d(TAG, "Destroyed")
    }
}