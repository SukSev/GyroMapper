package com.gyromapper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gyromapper.R
import com.gyromapper.core.aggregator.InputAggregator
import com.gyromapper.core.backends.LogBackend
import com.gyromapper.core.backends.OutputBackend
import com.gyromapper.core.camera.CameraAction
import com.gyromapper.core.controller.OdinIMUReader
import com.gyromapper.core.data.CameraDelta
import com.gyromapper.core.data.MotionDelta
import com.gyromapper.core.mapping.MappingEngine
import com.gyromapper.core.motion.MotionEngine
import com.gyromapper.ui.MainActivity

private const val TAG = "GyroMapperService"
private const val CHANNEL_ID = "gyro_mapper_channel"
private const val NOTIFICATION_ID = 1

class GyroMapperService : Service() {

    companion object {
        var instance: GyroMapperService? = null
        var currentForegroundPackage: String? = null
            set(value) {
                field = value
                instance?.onForegroundAppChanged(value)
            }
    }

    // Core components
    private lateinit var aggregator: InputAggregator
    private lateinit var motionEngine: MotionEngine
    private lateinit var mappingEngine: MappingEngine
    private lateinit var cameraAction: CameraAction
    private lateinit var outputBackend: OutputBackend
    private lateinit var imuReader: OdinIMUReader

    // Processing handler (to avoid blocking sensor thread)
    private val handler = Handler(Looper.getMainLooper())
    private var isProcessing = false
    private var wasGyroActive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Service created")

        // Initialize components
        aggregator = InputAggregator()
        motionEngine = MotionEngine(sensitivity = 0.5f)
        mappingEngine = MappingEngine()
        cameraAction = CameraAction()
        // Swap for TouchInjectionBackend once the Odin path is verified via
        // logcat - outputBackend is typed against the interface so that's
        // a one-line change, e.g.:
        // outputBackend = TouchInjectionBackend(
        //     GyroMapperAccessibilityService.instance!!,
        //     RectF(100f, 100f, 900f, 700f)
        // )
        outputBackend = LogBackend()
        imuReader = OdinIMUReader(this, aggregator)

        // Gyro samples drive the processing loop; wiring this on the
        // aggregator (rather than in each reader) means any future gyro
        // source gets the same triggering for free just by calling
        // aggregator.updateGyro().
        aggregator.onGyroUpdate = { triggerProcess() }

        // Start output backend
        outputBackend.onStart()

        // Start IMU reader
        imuReader.start()

        // Create notification channel
        createNotificationChannel()

        // Start foreground
        startForeground(NOTIFICATION_ID, createNotification())

        Log.d(TAG, "Service initialized and running")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroying")

        // Clean up
        imuReader.stop()
        outputBackend.onStop()

        instance = null
        super.onDestroy()
    }

    /**
     * Called when a gamepad button is pressed (from AccessibilityService).
     */
    fun onGamepadButtonPressed(keyCode: Int) {
        aggregator.updateButton(keyCode, true)
        mappingEngine.onButtonStateChanged(keyCode, true)
        processInput()
    }

    /**
     * Called when a gamepad button is released (from AccessibilityService).
     */
    fun onGamepadButtonReleased(keyCode: Int) {
        aggregator.updateButton(keyCode, false)
        mappingEngine.onButtonStateChanged(keyCode, false)
        processInput()
    }

    /**
     * Called when joystick values change (from AccessibilityService).
     */
    fun onJoystickUpdate(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
        aggregator.updateLeftStick(leftX, leftY)
        aggregator.updateRightStick(rightX, rightY)
        // Joystick updates don't trigger processing by themselves
        // (gyro drives the loop)
    }

    /**
     * Called when foreground app changes (from AccessibilityService).
     */
    private fun onForegroundAppChanged(packageName: String?) {
        Log.d(TAG, "Foreground app: $packageName")
        // Future: auto-switch profiles based on app
    }

    /**
     * Main processing loop - triggered by gyro updates (and by button
     * changes, so activation state is picked up immediately rather than
     * waiting for the next gyro sample).
     * Runs on the main thread to avoid sensor thread blocking.
     */
    private fun processInput() {
        if (isProcessing) return
        isProcessing = true

        handler.post {
            try {
                val state = aggregator.getState()
                val active = state.hasGyroSource() && mappingEngine.isGyroActive(state)

                if (!active) {
                    if (wasGyroActive) {
                        // Gyro just deactivated - force-lift whatever the
                        // backend has in progress rather than leaving it
                        // stuck mid-drag.
                        outputBackend.release()
                        wasGyroActive = false
                    }
                    isProcessing = false
                    return@post
                }
                wasGyroActive = true

                // Step 1: Motion pipeline
                val rawDx = state.gyroDelta.first
                val rawDy = state.gyroDelta.second
                val motion: MotionDelta = motionEngine.process(rawDx, rawDy, state.timestamp)

                // Step 2: Camera action
                val camera: CameraDelta = cameraAction.process(motion, state)

                // Step 3: Send to backend
                outputBackend.send(camera)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing input", e)
            } finally {
                isProcessing = false
            }
        }
    }

    /**
     * Public method to trigger processing. Called from the aggregator's
     * onGyroUpdate callback, and directly from the button handlers above.
     */
    fun triggerProcess() {
        processInput()
    }

    // ============ Notification ============

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gyro Mapper Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Gyro Mapper running in the background"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gyro Mapper")
            .setContentText("Active - ${currentForegroundPackage ?: "No app detected"}")
            .setSmallIcon(R.drawable.ic_notification) // You'll need to add this icon
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    fun updateNotification() {
        val notification = createNotification()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
