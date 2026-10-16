package im.g.apps.push

import im.g.apps.core.bridge.PushRegistration
import kotlinx.coroutines.flow.Flow

/**
 * A push platform. M2 ships no platform client (no FCM, UnifiedPush or Play services): release builds have no
 * provider, debug builds can use [createPushProvider]'s mock. [PushController] registers each [registration] with
 * gim-core and runs [receive] while signed in.
 */
interface PushProvider {
    /** The registration to send to the hubs; a new value whenever the platform hands out a new token, null while off. */
    val registration: Flow<PushRegistration?>

    /**
     * Receives the pushes addressed to [registration] and hands each data payload (the JSON object as delivered) to
     * [onPayload], until cancelled. A platform that delivers through a system service returns at once instead.
     */
    suspend fun receive(registration: PushRegistration, onPayload: suspend (payload: String) -> Unit)
}
