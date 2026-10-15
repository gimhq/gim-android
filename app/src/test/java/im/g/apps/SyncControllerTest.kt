package im.g.apps

import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.GimClientManager
import im.g.apps.core.bridge.InMemoryServerUrlStore
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.fake.FakePlatformHooks
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncControllerTest {
    private class RecordingClient(private val inner: FakeGimClient) : GimClient by inner {
        val calls = mutableListOf<String>()

        override suspend fun startSync() {
            calls += "start"
            inner.startSync()
        }

        override suspend fun stopSync() {
            calls += "stop"
            inner.stopSync()
        }

        override fun setNetworkReachable(reachable: Boolean) {
            calls += "reachable=$reachable"
        }
    }

    private fun TestScope.setUp(): Triple<SyncController, GimClientManager, MutableStateFlow<Boolean>> {
        val reachable = MutableStateFlow(true)
        val factory = GimClientFactory { _, _ -> RecordingClient(FakeGimClient.withDemoData(backgroundScope, outboxDelays = null)) }
        val manager = GimClientManager(factory, FakePlatformHooks(), InMemoryServerUrlStore(DemoData.SERVER_URL))
        val controller = SyncController(manager, reachable, backgroundScope)
        controller.start()
        return Triple(controller, manager, reachable)
    }

    @Test
    fun syncs_only_in_the_foreground_after_sign_in_and_forwards_reachability() =
        runTest(UnconfinedTestDispatcher()) {
            val (controller, manager, reachable) = setUp()
            val client = manager.restore() as RecordingClient

            controller.setForeground(true)
            assertEquals(listOf("reachable=true"), client.calls)

            controller.onSignedIn()
            controller.setForeground(false)
            reachable.value = false

            assertEquals(listOf("reachable=true", "start", "stop", "reachable=false"), client.calls)
        }
}
