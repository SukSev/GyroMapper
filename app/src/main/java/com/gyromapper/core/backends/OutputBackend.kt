package com.gyromapper.core.backends

import com.gyromapper.core.data.CameraDelta

interface OutputBackend {
    fun send(cameraDelta: CameraDelta)
    fun onStart()
    fun onStop()

    /**
     * Force-releases any in-progress output (e.g. lifts a synthetic touch
     * mid-drag) without fully tearing the backend down. Call this whenever
     * gyro deactivates - a toggle/hold releasing, a source switch, etc -
     * so nothing gets left in a stuck state. onStop() should call this too.
     */
    fun release()

    fun isConnected(): Boolean
}
