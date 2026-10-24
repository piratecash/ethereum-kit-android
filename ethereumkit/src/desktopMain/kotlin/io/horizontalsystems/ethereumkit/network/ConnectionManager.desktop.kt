package io.horizontalsystems.ethereumkit.network

import io.horizontalsystems.ethereumkit.PlatformContext

// Desktop has no connectivity service to subscribe to, so the kit always syncs;
// listeners are never called because the state never changes.
actual class ConnectionManager private constructor() {

    actual interface Listener {
        actual fun onConnectionChange()
    }

    actual companion object {
        private val instance = ConnectionManager()

        actual fun getInstance(context: PlatformContext): ConnectionManager = instance
    }

    actual var isConnected: Boolean = true

    actual fun addListener(listener: Listener) = Unit

    actual fun removeListener(listener: Listener) = Unit
}
