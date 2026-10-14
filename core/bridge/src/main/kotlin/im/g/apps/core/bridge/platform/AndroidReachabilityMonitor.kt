package im.g.apps.core.bridge.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks whether the default network has validated internet access. Registers a default-network
 * callback on creation; keep one instance per process and [close] it to unregister. [reachable]
 * may be collected from any thread.
 */
class AndroidReachabilityMonitor(context: Context) : AutoCloseable {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _reachable = MutableStateFlow(isUsable(connectivity.getNetworkCapabilities(connectivity.activeNetwork)))

    val reachable: StateFlow<Boolean> = _reachable.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _reachable.value = isUsable(capabilities)
        }

        override fun onLost(network: Network) {
            _reachable.value = false
        }
    }

    init {
        connectivity.registerDefaultNetworkCallback(callback)
    }

    override fun close() {
        connectivity.unregisterNetworkCallback(callback)
    }

    private fun isUsable(capabilities: NetworkCapabilities?): Boolean =
        capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
