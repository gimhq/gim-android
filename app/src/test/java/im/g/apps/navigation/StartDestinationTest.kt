package im.g.apps.navigation

import androidx.test.ext.junit.runners.AndroidJUnit4
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimClientFactory
import im.g.apps.core.bridge.fake.FakeGimClientFactory
import im.g.apps.testContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartDestinationTest {
    private val scope = CoroutineScope(Job())

    @Test
    fun no_stored_server_starts_at_onboarding() = runTest {
        assertEquals(Routes.ONBOARDING, resolveStartDestination(testContainer(storedUrl = null).manager))
    }

    @Test
    fun unregistered_client_starts_at_onboarding() = runTest {
        val container = testContainer(factory = FakeGimClientFactory { _, _ -> FakeGimClient(scope) })

        assertEquals(Routes.ONBOARDING, resolveStartDestination(container.manager))
    }

    @Test
    fun discarded_local_state_starts_at_onboarding() = runTest {
        // A session alongside the reset flag: the flag alone must send the user to onboarding.
        val container = testContainer(
            factory = GimClientFactory { _, _ ->
                object : GimClient by FakeGimClient.withDemoData(scope) {
                    override fun localStateReset() = true
                }
            },
        )

        assertEquals(Routes.ONBOARDING, resolveStartDestination(container.manager))
    }

    @Test
    fun registered_client_starts_at_the_chat_list() = runTest {
        assertEquals(Routes.CHAT_LIST, resolveStartDestination(testContainer().manager))
    }
}
