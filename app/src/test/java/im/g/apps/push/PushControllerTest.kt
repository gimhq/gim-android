package im.g.apps.push

import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.NotificationContent
import im.g.apps.core.bridge.PushRegistration
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakePlatformHooks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PushControllerTest {
    private class FakeProvider : PushProvider {
        override val registration = MutableStateFlow<PushRegistration?>(null)
        val payloads = Channel<String>(Channel.UNLIMITED)
        val received = mutableListOf<PushRegistration>()

        override suspend fun receive(registration: PushRegistration, onPayload: suspend (payload: String) -> Unit) {
            received += registration
            for (payload in payloads) onPayload(payload)
        }
    }

    private class RecordingNotifier : PushNotifier {
        val shown = mutableListOf<NotificationContent>()
        var cancelled = 0

        override fun show(content: NotificationContent) {
            shown += content
        }

        override fun cancelAll() {
            cancelled++
        }
    }

    private class Setup(
        val controller: PushController,
        val manager: GimClientManager,
        val client: FakeGimClient,
        val provider: FakeProvider,
        val notifier: RecordingNotifier,
        val signedIn: MutableStateFlow<Boolean>,
    )

    private fun TestScope.setUp(provider: FakeProvider? = FakeProvider()): Setup {
        val client = FakeGimClient.withDemoData(backgroundScope, outboxDelays = null)
        val manager = GimClientManager(
            GimClientFactory { _, _ -> client },
            FakePlatformHooks(),
            InMemoryServerUrlStore(DemoData.SERVER_URL),
        )
        val signedIn = MutableStateFlow(false)
        val notifier = RecordingNotifier()
        val controller = PushController(
            manager,
            signedIn,
            provider,
            notifier,
            backgroundScope,
            io = UnconfinedTestDispatcher(testScheduler),
        )
        controller.start()
        return Setup(controller, manager, client, provider ?: FakeProvider(), notifier, signedIn)
    }

    @Test
    fun registers_once_signed_in_with_a_registration_and_again_for_a_new_token() = runTest(UnconfinedTestDispatcher()) {
        val s = setUp()
        s.manager.restore()
        val first = PushRegistration("mock-android", "token-1")

        s.provider.registration.value = first
        assertNull("registered before sign-in", s.client.registeredPush)

        s.signedIn.value = true
        assertEquals(first, s.client.registeredPush)
        assertTrue(s.controller.active.value)
        assertEquals(listOf(first), s.provider.received)

        val second = PushRegistration("mock-android", "token-2")
        s.provider.registration.value = second
        assertEquals(second, s.client.registeredPush)
        assertEquals(listOf(first, second), s.provider.received)

        s.provider.registration.value = null
        assertFalse(s.controller.active.value)
    }

    @Test
    fun shows_processed_pushes_only_when_core_says_so() = runTest(UnconfinedTestDispatcher()) {
        val s = setUp()
        s.manager.restore()
        s.client.pushContent = { payload -> notificationContent(body = payload, shouldDisplay = "own" !in payload) }
        s.provider.registration.value = PushRegistration("mock-android", "token-1")
        s.signedIn.value = true

        s.provider.payloads.send("""{"v":1,"type":"message","n":"peer"}""")
        s.provider.payloads.send("""{"v":1,"type":"message","n":"own"}""")
        s.provider.payloads.send("not json")

        assertEquals(listOf("""{"v":1,"type":"message","n":"peer"}"""), s.notifier.shown.map { it.body })
    }

    @Test
    fun signing_out_unregisters_and_clears_notifications() = runTest(UnconfinedTestDispatcher()) {
        val s = setUp()
        val client = s.manager.restore()
        s.provider.registration.value = PushRegistration("mock-android", "token-1")
        s.signedIn.value = true

        s.signedIn.value = false
        s.controller.onSigningOut(client)

        assertNull(s.client.registeredPush)
        assertEquals(1, s.notifier.cancelled)
        assertFalse(s.controller.active.value)
    }

    @Test
    fun without_a_provider_nothing_is_registered() = runTest(UnconfinedTestDispatcher()) {
        val s = setUp(provider = null)
        s.manager.restore()
        s.signedIn.value = true

        assertNull(s.client.registeredPush)
        assertFalse(s.controller.active.value)
    }
}
