package im.g.apps.core.bridge.fake

import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.NotificationContent
import im.g.apps.core.bridge.NotificationKind
import im.g.apps.core.bridge.PushRegistration
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class FakeGimClientPushTest {
    private val fcm = PushRegistration(provider = "fcm", token = "token-1", appId = "im.g.apps")

    private fun TestScope.client() = FakeGimClient(backgroundScope, clock = { 42L }, outboxDelays = null)

    private inline fun <reified E : GimException> assertGimError(block: () -> Unit): E {
        try {
            block()
        } catch (e: GimException) {
            if (e is E) return e
            fail("expected ${E::class.simpleName} but got $e")
        }
        fail("expected ${E::class.simpleName}")
        error("unreachable")
    }

    @Test
    fun registerPushStoresTheLatestRegistrationAlsoBeforeOnboarding() = runTest {
        val client = client()
        client.registerPush(fcm)
        assertEquals(fcm, client.registeredPush)

        val renewed = fcm.copy(token = "token-2")
        client.createIdentity("Alice")
        client.registerPush(renewed)
        assertEquals(renewed, client.registeredPush)
    }

    @Test
    fun registerPushRejectsMalformedFieldsAndKeepsThePreviousOne() = runTest {
        val client = client()
        client.registerPush(fcm)
        val invalid = listOf(
            fcm.copy(provider = ""),
            fcm.copy(provider = "FCM"),
            fcm.copy(provider = "f".repeat(65)),
            fcm.copy(token = ""),
            fcm.copy(token = "a\nb"),
            fcm.copy(token = "é".repeat(2049)),
            fcm.copy(appId = ""),
            fcm.copy(appId = "im g apps"),
            fcm.copy(p256dh = "not+base64url"),
            fcm.copy(auth = "a".repeat(257)),
            fcm.copy(auth = "==="),
        )
        for (registration in invalid) {
            assertGimError<GimException.InvalidInput> { client.registerPush(registration) }
        }
        assertEquals(fcm, client.registeredPush)

        val webPush = PushRegistration("webpush", "t", p256dh = "BNc-_x0", auth = "YXV0aA==", sandbox = true)
        client.registerPush(webPush)
        assertEquals(webPush, client.registeredPush)
    }

    @Test
    fun unregisterPushAndSignOutDropTheRegistration() = runTest {
        val client = client()
        client.createIdentity("Alice")
        client.registerPush(fcm)
        client.unregisterPush()
        assertNull(client.registeredPush)

        client.registerPush(fcm)
        client.signOut()
        assertNull(client.registeredPush)
    }

    @Test
    fun processPushNeedsAJsonObjectAndFallsBackToGenericContent() = runTest {
        val client = client()
        for (payload in listOf("", "[]", "\"seq\"", "{")) {
            assertGimError<GimException.InvalidInput> { client.processPush(payload) }
        }

        val content = client.processPush("""{"type":"message","seq":7}""")
        assertEquals(
            NotificationContent(
                chatId = null,
                chatTitle = null,
                senderUserId = null,
                senderDisplayName = null,
                body = FakeGimClient.GENERIC_NOTIFICATION_BODY,
                messageId = null,
                collapseId = null,
                kind = NotificationKind.GENERIC,
                shouldDisplay = true,
                timestamp = 42L,
            ),
            content,
        )
    }

    @Test
    fun processPushReturnsScriptedContent() = runTest {
        val client = client()
        val scripted = NotificationContent(
            chatId = "c1",
            chatTitle = "Crew",
            senderUserId = "u2",
            senderDisplayName = "Bob",
            body = "Hello",
            messageId = "m1",
            collapseId = "k1",
            kind = NotificationKind.MESSAGE,
            shouldDisplay = true,
            timestamp = 7L,
        )
        var seen: String? = null
        client.pushContent = { payload -> scripted.also { seen = payload } }

        assertEquals(scripted, client.processPush(" {\"seq\":1} "))
        assertEquals(" {\"seq\":1} ", seen)
    }
}
