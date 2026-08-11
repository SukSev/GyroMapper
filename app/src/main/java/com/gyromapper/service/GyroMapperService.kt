package com.gyromapper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.RectF
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
import com.gyromapper.core.backends.TouchInjectionBackend
import com.gyromapper.core.camera.CameraAction
import com.gyromapper.core.controller.EightBitDoHidReader
import com.gyromapper.core.controller.OdinIMUReader
import com.gyromapper.core.data.CameraDelta
import com.gyromapper.core.data.GyroSource
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

    private lateinit var aggregator: InputAggregator
    lateinit var motionEngine: MotionEngine
    private lateinit var mappingEngine: MappingEngine
    private lateinit var cameraAction: CameraAction
    private lateinit var outputBackend: OutputBackend
    lateinit var imuReader: OdinIMUReader
    private lateinit var eightBitDoReader: EightBitDoHidReader

    private val handler = Handler(Looper.getMainLooper())
    private var isProcessing = false
    private var wasGyroActive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "Service created")

        aggregator = InputAggregator()
        motionEngine = MotionEngine(sensitivity = 10000f)
        mappingEngine = MappingEngine()
        cameraAction = CameraAction()
        val accessService = GyroMapperAccessibilityService.instance
        // Inside onCreate()
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels.toFloat()
        val screenHeight = displayMetrics.heightPixels.toFloat()
        val region = RectF(
            screenWidth * 0.05f,
            screenHeight * 0.05f,
            screenWidth * 0.95f,
            screenHeight * 0.95f
        )

        outputBackend = if (accessService != null) {
            TouchInjectionBackend(accessService, region, segmentDurationMs = 10L)
        } else {
            Log.w(TAG, "Accessibility service not available – falling back to LogBackend")
            LogBackend()
        }
        imuReader = OdinIMUReader(this, aggregator)
        eightBitDoReader = EightBitDoHidReader(this, aggregator)

        aggregator.onGyroUpdate = { triggerProcess() }
        aggregator.setActiveGyroSource(GyroSource.ODIN_IMU)
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
        eightBitDoReader.stop()
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

    /**
     * Switches which physical device supplies gyro data. Independent of
     * the gyro->touch on/off toggle in MappingEngine - this only changes
     * where gyroDelta comes from, not whether it reaches the touch
     * backend.
     *
     * Stops whichever reader is currently active, releases any in-flight
     * synthetic touch, and resets MotionEngine's calibration/timing (Odin
     * and the 8BitDo don't share a rest bias or a timestamp clock domain,
     * so carrying either over into the new source would read as one
     * spurious jump), then starts the newly selected reader.
     */
    fun selectGyroSource(source: GyroSource) {
        val current = aggregator.getActiveGyroSource()
        if (source == current) return

        Log.d(TAG, "Switching gyro source: $current -> $source")

        when (current) {
            GyroSource.ODIN_IMU -> imuReader.stop()
            GyroSource.EIGHTBITDO -> eightBitDoReader.stop()
            GyroSource.NONE -> {}
        }

        outputBackend.release()
        motionEngine.resetCalibration()
        aggregator.setActiveGyroSource(source)

        when (source) {
            GyroSource.ODIN_IMU -> imuReader.start()
            GyroSource.EIGHTBITDO -> eightBitDoReader.start()
            GyroSource.NONE -> {}
        }
    }

    fun getActiveGyroSource(): GyroSource = aggregator.getActiveGyroSource()

    private fun onForegroundAppChanged(packageName: String?) {
        Log.d(TAG, "Foreground app: $packageName")
    }

    private fun processInput() {
        if (isProcessing) return

        isProcessing = true

        handler.post {
            try {
                val state = aggregator.getState()
                val active = state.hasGyroSource() &&
                        mappingEngine.isGyroActive(state)

                if (!active) {
                    if (wasGyroActive) {
                        Log.d(TAG, "Gyro deactivated -> releasing touch")
                        outputBackend.release()
                    }

                    wasGyroActive = false
                    return@post
                }

                wasGyroActive = true

                val (rawDx, rawDy) = state.gyroDelta
                val timestampNanos = state.timestamp

                val motionDelta = motionEngine.process(
                    rawDx,
                    rawDy,
                    timestampNanos
                )

                val cameraDelta = CameraDelta(
                    dx = motionDelta.dx,
                    dy = motionDelta.dy,
                    timestamp = motionDelta.timestamp
                )

                outputBackend.send(cameraDelta)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing input", e)
            } finally {
                isProcessing = false
            }
        }
    }

    private fun triggerProcess() {
        processInput()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gyro Mapper Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Gyro Mapper background service"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gyro Mapper")
            .setContentText("Service is running")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}