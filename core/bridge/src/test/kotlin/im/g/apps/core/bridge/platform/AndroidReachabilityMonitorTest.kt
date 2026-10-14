package im.g.apps.core.bridge.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
class AndroidReachabilityMonitorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val network = ShadowNetwork.newInstance(7)

    private fun capabilities(vararg caps: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { nc -> caps.forEach { shadowOf(nc).addCapability(it) } }

    @Test
    fun initial_value_reflects_the_active_network() {
        val active = connectivity.activeNetwork!!
        shadowOf(connectivity).setNetworkCapabilities(
            active,
            capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
        AndroidReachabilityMonitor(context).use { assertTrue(it.reachable.value) }

        shadowOf(connectivity).setNetworkCapabilities(active, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        AndroidReachabilityMonitor(context).use { assertFalse(it.reachable.value) }
    }

    @Test
    fun reacts_to_default_network_callbacks_and_unregisters_on_close() {
        val monitor = AndroidReachabilityMonitor(context)
        val callbacks = shadowOf(connectivity).networkCallbacks
        assertEquals(1, callbacks.size)
        val callback = callbacks.single()

        callback.onCapabilitiesChanged(
            network,
            capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
        assertTrue(monitor.reachable.value)

        callback.onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        assertFalse(monitor.reachable.value)

        callback.onCapabilitiesChanged(
            network,
            capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        )
        callback.onLost(network)
        assertFalse(monitor.reachable.value)

        monitor.close()
        assertTrue(shadowOf(connectivity).networkCallbacks.isEmpty())
    }
}
