package com.gyromapper.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.gyromapper.R
import com.gyromapper.core.controller.UsbHidProbe
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

        statusText = findViewById(R.id.statusText)
        foregroundText = findViewById(R.id.foregroundText)
        gyroStatusText = findViewById(R.id.gyroStatusText)
        calibrationText = findViewById(R.id.calibrationText)
        startStopButton = findViewById(R.id.startStopButton)
        accessibilityButton = findViewById(R.id.accessibilityButton)

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

        requestPermissions()
        updateUi()

        // Temporary: 8BitDo diagnostic probe
        UsbHidProbe(this).let { probe ->
            probe.findEightBitDo()?.let {
                probe.requestPermissionAndProbe(it)
            } ?: Log.w("MainActivity", "No 8BitDo device found - check it's connected")
        }
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

        try {
            val usageStatsManager = getSystemService(USAGE_STATS_SERVICE)
            // Usage stats permission check would go here
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
        Toast.makeText(
            this,
            "Enable 'Gyro Mapper' from the accessibility settings",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun updateAccessibilityButton() {
        val isEnabled = isAccessibilityServiceEnabled()
        accessibilityButton.text = if (isEnabled) {
            "✅ Accessibility Service Enabled"
        } else {
            "⚠️ Enable Accessibility Service"
        }
        accessibilityButton.setBackgroundColor(
            if (isEnabled) {
                resources.getColor(android.R.color.holo_green_light, null)
            } else {
                resources.getColor(android.R.color.holo_red_light, null)
            }
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return GyroMapperAccessibilityService.instance != null
    }

    private fun startGyroMapper() {
        val service = GyroMapperAccessibilityService.instance
        if (service == null) {
            Toast.makeText(
                this,
                "Please enable accessibility service first!",
                Toast.LENGTH_LONG
            ).show()
            openAccessibilitySettings()
            return
        }

        val intent = Intent(this, GyroMapperService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isServiceRunning = true
        updateUi()
        Toast.makeText(this, "Gyro Mapper started", Toast.LENGTH_SHORT).show()
    }

    private fun stopGyroMapper() {
        val intent = Intent(this, GyroMapperService::class.java)
        stopService(intent)
        isServiceRunning = false
        updateUi()
        Toast.makeText(this, "Gyro Mapper stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateUi() {
        val service = GyroMapperService.instance
        val isRunning = service != null
        isServiceRunning = isRunning

        statusText.text = if (isRunning) "🟢 Service Running" else "🔴 Service Stopped"
        startStopButton.text = if (isRunning) "Stop Service" else "Start Service"

        val foreground = GyroMapperService.currentForegroundPackage
        foregroundText.text = "Foreground: ${foreground ?: "Unknown"}"

        // Gyro status: OdinIMUReader doesn't expose isRunning, but the service starts it.
        // We can simply show "Active" when the service is running.
        gyroStatusText.text = if (isRunning) "🟢 Gyro Active" else "⏳ Gyro Inactive"

        calibrationText.text = "Calibration: Ready"
        updateAccessibilityButton()
    }
}