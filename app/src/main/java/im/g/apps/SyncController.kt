package im.g.apps

import android.util.Log
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.GimException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Runs sync while the process is in the foreground and a session exists, and forwards network
 * reachability to the current client.
 */
class SyncController(
    private val manager: GimClientManager,
    private val reachable: Flow<Boolean>,
    private val scope: CoroutineScope,
) {
    private val foreground = MutableStateFlow(false)
    private val _signedIn = MutableStateFlow(false)

    /** Whether a session is known to exist, between [onSignedIn] and [onSignedOut]. */
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    fun start() {
        scope.launch {
            combine(manager.client, reachable, ::Pair).collect { (client, reachable) ->
                client?.setNetworkReachable(reachable)
            }
        }
        scope.launch {
            combine(manager.client, foreground, _signedIn, ::Triple).collectLatest { (client, foreground, signedIn) ->
                if (client != null && signedIn) client.setSyncing(foreground)
            }
        }
    }

    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    /** Call once a session is known to exist (restored at launch or created by onboarding). */
    fun onSignedIn() {
        _signedIn.value = true
    }

    /** Call before signing out: stops sync on the current client until the next [onSignedIn]. */
    suspend fun onSignedOut() {
        _signedIn.value = false
        manager.client.value?.setSyncing(false)
    }

    private suspend fun GimClient.setSyncing(on: Boolean) {
        try {
            if (on) startSync() else stopSync()
        } catch (e: GimException) {
            Log.w(TAG, "Cannot ${if (on) "start" else "stop"} sync", e)
        }
    }

    private companion object {
        const val TAG = "SyncController"
    }
}
