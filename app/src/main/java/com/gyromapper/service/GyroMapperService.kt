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
    lateinit var motionEngine: MotionEngine
    private lateinit var mappingEngine: MappingEngine
    private lateinit var cameraAction: CameraAction
    private lateinit var outputBackend: OutputBackend
    lateinit var imuReader: OdinIMUReader

    private val handler = Handler(Looper.getMainLooper())
    private var isProcessing = false
    private var wasGyroActive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Service created")

        aggregator = InputAggregator()
        motionEngine = MotionEngine(sensitivity = 0.5f)
        mappingEngine = MappingEngine()
        cameraAction = CameraAction()
        outputBackend = LogBackend()
        imuReader = OdinIMUReader(this, aggregator)

        aggregator.onGyroUpdate = { triggerProcess() }

        outputBackend.onStart()
        imuReader.start()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        Log.d(TAG, "Service initialized and running")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service destroying")
        imuReader.stop()
        outputBackend.onStop()
        instance = null
        super.onDestroy()
    }

    fun onGamepadButtonPressed(keyCode: Int) {
        aggregator.updateButton(keyCode, true)
        mappingEngine.onButtonStateChanged(keyCode, true)
        processInput()
    }

    fun onGamepadButtonReleased(keyCode: Int) {
        aggregator.updateButton(keyCode, false)
        mappingEngine.onButtonStateChanged(keyCode, false)
        processInput()
    }

    fun onJoystickUpdate(leftX: Float, leftY: Float, rightX: Float, rightY: Float) {
        aggregator.updateLeftStick(leftX, leftY)
        aggregator.updateRightStick(rightX, rightY)
    }

    private fun onForegroundAppChanged(packageName: String?) {
        Log.d(TAG, "Foreground app: $packageName")
    }

    private fun processInput() {
        if (isProcessing) return
        isProcessing = true

        handler.post {
            try {
                val state = aggregator.getState()
                val active = state.hasGyroSource() && mappingEngine.isGyroActive(state)

                if (!active) {
                    if (wasGyroActive) {
                        outputBackend.release()
                        wasGyroActive = false
                    }
                    isProcessing = false
                    return@post
                }
                wasGyroActive = true

                val rawDx = state.gyroDelta.first
                val rawDy = state.gyroDelta.second
                val motion: MotionDelta = motionEngine.process(rawDx, rawDy, state.timestamp)
                val camera: CameraDelta = cameraAction.process(motion, state)
                outputBackend.send(camera)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing input", e)
            } finally {
                isProcessing = false
            }
        }
    }

    fun triggerProcess() {
        processInput()
    }

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
            .setSmallIcon(R.drawable.ic_notification)
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
