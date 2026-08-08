package com.gyromapper.core.backends

import com.gyromapper.core.data.CameraDelta

interface OutputBackend {
    fun send(cameraDelta: CameraDelta)
    fun onStart()
    fun onStop()
    fun isConnected(): Boolean
}