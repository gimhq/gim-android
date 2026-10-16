package im.g.apps.push

import android.util.Log
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.GimException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects the [provider] to the app's client: while signed in, registers the provider's current registration with
 * gim-core (again for every new token) and runs [PushProvider.receive], turning each payload into a notification
 * through [GimClient.processPush] on the same client, so no second client ever opens the data dir. Without a provider
 * (release builds) it does nothing.
 */
class PushController(
    private val manager: GimClientManager,
    private val signedIn: Flow<Boolean>,
    private val provider: PushProvider?,
    private val notifier: PushNotifier,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _active = MutableStateFlow(false)

    /** True while the signed-in client is registered for push; the app then asks for the notification permission. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile private var registered = false

    fun start() {
        val provider = provider ?: return
        scope.launch {
            combine(manager.client, signedIn, provider.registration, ::Triple).collectLatest { (client, signedIn, registration) ->
                _active.value = false
                if (client == null || !signedIn || registration == null) return@collectLatest
                try {
                    withContext(io) { client.registerPush(registration) }
                } catch (e: GimException) {
                    Log.w(TAG, "Cannot register $registration", e)
                    return@collectLatest
                }
                registered = true
                _active.value = true
                Log.i(TAG, "Registered $registration")
                provider.receive(registration) { handle(client, it) }
            }
        }
    }

    /** Processes one payload on [client]; gim-core calls run on [io], never on a gim-core callback thread. */
    internal suspend fun handle(client: GimClient, payload: String) {
        val content = try {
            withContext(io) { client.processPush(payload) }
        } catch (e: GimException) {
            Log.w(TAG, "Cannot process a push", e)
            return
        }
        if (content.shouldDisplay) notifier.show(content) else Log.d(TAG, "Push not displayed (${content.kind})")
    }

    /**
     * Call before signing [client] out (after sync stopped): removes the registration from the hubs and the shown
     * notifications. gim-core's sign-out removes the registration too (best effort), so a failure here is only logged.
     */
    suspend fun onSigningOut(client: GimClient?) {
        notifier.cancelAll()
        if (!registered || client == null) return
        registered = false
        try {
            withContext(io) { client.unregisterPush() }
        } catch (e: GimException) {
            Log.i(TAG, "Cannot unregister push before sign-out; sign-out retries", e)
        }
    }

    private companion object {
        const val TAG = "PushController"
    }
}
