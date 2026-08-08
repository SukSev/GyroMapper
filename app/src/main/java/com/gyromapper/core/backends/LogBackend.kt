package com.gyromapper.core.backends

import com.gyromapper.core.data.CameraDelta
import android.util.Log

private const val TAG = "LogBackend"

class LogBackend : OutputBackend {
    private var counter = 0

    override fun send(cameraDelta: CameraDelta) {
        counter++
        // Log every 10th frame to avoid spam
        if (counter % 10 == 0) {
            Log.d(TAG, "CameraDelta: dx=${cameraDelta.dx}, dy=${cameraDelta.dy}, ts=${cameraDelta.timestamp}")
        }
    }

    override fun onStart() {
        Log.d(TAG, "LogBackend started")
        counter = 0
    }

    override fun onStop() {
        Log.d(TAG, "LogBackend stopped")
    }

    override fun isConnected(): Boolean = true
}