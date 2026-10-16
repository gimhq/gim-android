package im.g.apps.feature.contacts

import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.GimException
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactsErrorTest {
    @Test
    fun retryLater_carriesTheWaitInSeconds() {
        assertEquals(ContactsError.RetryLater(42), GimException.RetryLater(429, 42, "rate_limited").toContactsError())
        assertEquals(ContactsError.RetryLater(1), GimException.RetryLater(503, 0, "peer_unavailable").toContactsError())
    }

    @Test
    fun notFound_isUserNotFoundOnlyForInvites() {
        assertEquals(ContactsError.NotFound, GimException.NotFound("chat").toContactsError())
        assertEquals(ContactsError.UserNotFound, GimException.NotFound("users").toInviteError())
        assertEquals(ContactsError.Network, GimException.Network("offline").toInviteError())
        assertEquals(ContactsError.RetryLater(5), GimException.RetryLater(503, 5, "peer_unavailable").toInviteError())
    }

    @Test
    fun chatNotActive_isReadOnly() {
        assertEquals(ContactsError.ChatNotActive, GimException.ChatNotActive("c1", ChatState.LEAVING).toContactsError())
        assertEquals(ContactsError.ChatNotActive, GimException.ChatNotActive("c1", ChatState.LEFT).toInviteError())
    }

    @Test
    fun hub_errors_are_unknown_outside_hub_management() {
        val errors = listOf(
            GimException.HubNotVerified("relay"),
            GimException.HubOwnsGroups("hb", 2),
            GimException.LastHub("hb"),
            GimException.HomeHub("ha"),
            GimException.HubNotDrained("hb", "1 message"),
        )
        for (error in errors) assertEquals(ContactsError.Unknown, error.toContactsError())
    }

    @Test
    fun server_errors_stay_generic() {
        assertEquals(ContactsError.Server, GimException.Server(502, "peer_refused").toContactsError())
    }
}
