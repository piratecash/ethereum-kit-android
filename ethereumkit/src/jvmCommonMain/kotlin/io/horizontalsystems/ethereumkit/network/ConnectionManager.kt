package io.horizontalsystems.ethereumkit.network

import io.horizontalsystems.ethereumkit.PlatformContext

expect class ConnectionManager {

    interface Listener {
        fun onConnectionChange()
    }

    var isConnected: Boolean

    fun addListener(listener: Listener)

    fun removeListener(listener: Listener)

    companion object {
        fun getInstance(context: PlatformContext): ConnectionManager
    }
}
