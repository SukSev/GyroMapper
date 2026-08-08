package com.gyromapper.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.gyromapper.R
import com.gyromapper.service.GyroMapperAccessibilityService
import com.gyromapper.service.GyroMapperService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var foregroundText: TextView
    private lateinit var gyroStatusText: TextView
    private lateinit var calibrationText: TextView
    private lateinit var startStopButton: Button
    private lateinit var accessibilityButton: Button

    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        statusText = findViewById(R.id.statusText)
        foregroundText = findViewById(R.id.foregroundText)
        gyroStatusText = findViewById(R.id.gyroStatusText)
        calibrationText = findViewById(R.id.calibrationText)
        startStopButton = findViewById(R.id.startStopButton)
        accessibilityButton = findViewById(R.id.accessibilityButton)

        // Check accessibility service permission
        updateAccessibilityButton()

        accessibilityButton.setOnClickListener {
            openAccessibilitySettings()
        }

        startStopButton.setOnClickListener {
            if (isServiceRunning) {
                stopGyroMapper()
            } else {
                startGyroMapper()
            }
        }

        // Request necessary permissions
        requestPermissions()

        // Update UI periodically
        updateUi()
    }

    override fun onResume() {
        super.onResume()
        updateUi()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                // SYSTEM_ALERT_WINDOW is optional for later
            }
        }

        // Usage stats permission (for foreground detection)
        try {
            val usageStatsManager = getSystemService(USAGE_STATS_SERVICE)
            // Check if we have permission
            val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Just check if we can query
                true
            } else {
                true
            }
        } catch (e: Exception) {
            // Permission may be needed
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 1)
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
        Toast.makeText(this, "Enable 'Gyro Mapper' from the accessibility settings", Toast.LENGTH_LONG).show()
    }

    private fun updateAccessibilityButton() {
        val isEnabled = isAccessibilityServiceEnabled()
        accessibilityButton.text = if (isEnabled) {
            "✅ Accessibility Service Enabled"
        } else {
            "⚠️ Enable Accessibility Service"
        }
        accessibilityButton.setBackgroundColor(
            if (isEnabled) resources.getColor(android.R.color.holo_green_light, null)
            else resources.getColor(android.R.color.holo_red_light, null)
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = GyroMapperAccessibilityService.instance
        return service != null
    }

    private fun startGyroMapper() {
        val service = GyroMapperAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Please enable accessibility service first!", Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }

        // Service already started via accessibility service
        isServiceRunning = true
        updateUi()
        Toast.makeText(this, "Gyro Mapper started", Toast.LENGTH_SHORT).show()
    }

    private fun stopGyroMapper() {
        // Stop the service
        val intent = Intent(this, GyroMapperService::class.java)
        stopService(intent)

        // Also stop accessibility service if we can
        GyroMapperAccessibilityService.instance?.disableSelf()

        isServiceRunning = false
        updateUi()
        Toast.makeText(this, "Gyro Mapper stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateUi() {
        val service = GyroMapperService.instance
        val isRunning = service != null

        statusText.text = if (isRunning) "🟢 Service Running" else "🔴 Service Stopped"
        startStopButton.text = if (isRunning) "Stop Service" else "Start Service"

        // Update foreground app
        val foreground = GyroMapperService.currentForegroundPackage
        foregroundText.text = "Foreground: ${foreground ?: "None"}"

        // Update gyro status (placeholder)
        val imuAvailable = service?.imuReader?.isAvailable() ?: false
        gyroStatusText.text = "IMU: ${if (imuAvailable) "✅ Available" else "❌ Not Available"}"

        // Update calibration status
        val calState = service?.motionEngine?.getCalibrationState()
        calibrationText.text = when (calState) {
            com.gyromapper.core.motion.MotionEngine.CalibrationState.IDLE -> "Calibration: Idle"
            com.gyromapper.core.motion.MotionEngine.CalibrationState.COLLECTING -> "Calibration: Collecting..."
            com.gyromapper.core.motion.MotionEngine.CalibrationState.CALIBRATED -> "✅ Calibrated"
            null -> "Calibration: Unknown"
        }

        updateAccessibilityButton()

        // Update notification if service is running
        service?.updateNotification()

        // Schedule next update
        if (!isFinishing) {
            statusText.postDelayed({ updateUi() }, 1000)
        }
    }
}