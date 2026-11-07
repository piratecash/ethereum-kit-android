package io.horizontalsystems.ethereumkit.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.horizontalsystems.ethereumkit.PlatformContext
import java.util.concurrent.CopyOnWriteArraySet

actual class ConnectionManager private constructor(context: Context) {

    actual interface Listener {
        actual fun onConnectionChange()
    }

    actual companion object {
        @Volatile
        private var instance: ConnectionManager? = null

        actual fun getInstance(context: PlatformContext): ConnectionManager =
            instance ?: synchronized(this) {
                instance ?: ConnectionManager(context.applicationContext).also { instance = it }
            }
    }

    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val listeners = CopyOnWriteArraySet<Listener>()

    actual var isConnected = getInitialConnectionStatus()
    private var hasValidInternet = false
    private var hasConnection = false
    private var callback = ConnectionStatusCallback()

    init {
        connectivityManager.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
    }

    private fun getInitialConnectionStatus(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false

        hasConnection = true
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        hasValidInternet = capabilities?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: false

        return hasValidInternet
    }

    inner class ConnectionStatusCallback : ConnectivityManager.NetworkCallback() {

        private val activeNetworks: MutableList<Network> = mutableListOf()

        override fun onLost(network: Network) {
            super.onLost(network)
            activeNetworks.removeAll { activeNetwork -> activeNetwork == network }
            hasConnection = activeNetworks.isNotEmpty()
            updatedConnectionState()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            hasValidInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            updatedConnectionState()
        }

        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            if (activeNetworks.none { activeNetwork -> activeNetwork == network }) {
                activeNetworks.add(network)
            }
            hasConnection = activeNetworks.isNotEmpty()
            updatedConnectionState()
        }
    }

    actual fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    actual fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun updatedConnectionState() {
        val oldValue = isConnected
        isConnected = hasConnection && hasValidInternet
        if (oldValue != isConnected) {
            listeners.forEach { it.onConnectionChange() }
        }
    }

}
