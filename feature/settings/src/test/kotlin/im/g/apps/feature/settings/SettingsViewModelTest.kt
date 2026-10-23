package im.g.apps.feature.settings

import im.g.apps.core.bridge.fake.FakeGimClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private suspend fun TestScope.signedIn(): FakeGimClient =
        FakeGimClient(backgroundScope, outboxDelays = null).apply { createIdentity("Sofia Costa") }

    private fun TestScope.viewModel(client: FakeGimClient) =
        SettingsViewModel(client, ioDispatcher = main.dispatcher).also { advanceUntilIdle() }

    @Test
    fun loads_the_session_into_the_state() = runTest {
        val client = signedIn()
        client.claimHandle("sofia")

        val state = viewModel(client).state.value

        assertEquals("Sofia Costa", state.displayName)
        assertEquals("sofia", state.username)
        assertEquals(client.userId(), state.accountId)
    }

    @Test
    fun no_username_is_null() = runTest {
        val client = signedIn()

        val state = viewModel(client).state.value

        assertEquals("Sofia Costa", state.displayName)
        assertNull(state.username)
    }

    @Test
    fun refresh_picks_up_a_changed_display_name() = runTest {
        val client = signedIn()
        val vm = viewModel(client)

        client.setDisplayName("Sofia C.")
        vm.refresh()
        advanceUntilIdle()

        assertEquals("Sofia C.", vm.state.value.displayName)
    }
}
