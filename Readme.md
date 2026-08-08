# Gyro Mapper - Phase 0

## Overview
Gyro Mapper is an Android app that reads gyroscope data from the Odin 3 built-in IMU and processes it for PC emulation (Winlator) gyro aiming.

## Current Status (Phase 0)
- ✅ Odin 3 built-in IMU reading via SensorManager
- ✅ 1€ filter implementation
- ✅ Auto-calibration on stillness
- ✅ Gamepad button/joystick capture via AccessibilityService
- ✅ Foreground app detection
- ✅ Log backend for debugging
- ⏳ 8BitDo Ultimate 2 support (Phase 1)
- ⏳ Winlator TCP socket integration (Phase 1)

## Building
1. Open the project in Android Studio
2. Build → Make Project
3. Run on device (AYN Odin 3)

## Installation
1. Install the APK
2. Go to Settings → Accessibility → Gyro Mapper → Enable
3. Open the Gyro Mapper app
4. Click "Start Service"

## Testing
- Hold the device still for 2 seconds to auto-calibrate
- Move the device to see logcat output: