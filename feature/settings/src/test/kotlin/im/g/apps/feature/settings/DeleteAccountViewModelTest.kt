package im.g.apps.feature.settings

import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.fake.FakeGimClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAccountViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private suspend fun TestScope.signedIn(): FakeGimClient =
        FakeGimClient(backgroundScope, outboxDelays = null).apply { createIdentity("Sofia Costa") }

    private fun viewModel(client: FakeGimClient, deleteAccount: suspend () -> List<String> = client::deleteAccount) =
        DeleteAccountViewModel(client, deleteAccount, ioDispatcher = main.dispatcher)

    @Test
    fun delete_needs_the_explicit_confirmation() = runTest {
        val client = signedIn()
        val vm = viewModel(client)

        assertFalse(vm.state.value.canDelete)
        vm.delete()
        advanceUntilIdle()

        assertFalse(vm.state.value.deleting)
        assertFalse(vm.state.value.deleted)
        assertNotNull(client.session())

        vm.setUnderstood(true)
        assertTrue(vm.state.value.canDelete)
    }

    @Test
    fun delete_success_without_unreachable_servers() = runTest {
        val client = signedIn()
        val vm = viewModel(client)

        vm.setUnderstood(true)
        vm.delete()
        assertTrue(vm.state.value.deleting)
        assertFalse(vm.state.value.canDelete)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state.deleted)
        assertNull(state.unreachable)
        assertNull(client.session())
    }

    @Test
    fun delete_success_names_unreachable_servers_by_url() = runTest {
        val client = signedIn()
        val other = client.addHub("https://two.example")
        client.unreachableHubsOnDelete = listOf(other.hubId)
        val vm = viewModel(client)

        vm.setUnderstood(true)
        vm.delete()
        advanceUntilIdle()

        assertTrue(vm.state.value.deleted)
        assertEquals(ServerList(urls = listOf("https://two.example"), count = 1), vm.state.value.unreachable)
    }

    @Test
    fun delete_success_counts_unreachable_servers_without_a_url() = runTest {
        val client = signedIn()
        client.unreachableHubsOnDelete = listOf("hub-a", "hub-b")
        val vm = viewModel(client)

        vm.setUnderstood(true)
        vm.delete()
        advanceUntilIdle()

        assertEquals(ServerList(urls = emptyList(), count = 2), vm.state.value.unreachable)
    }

    @Test
    fun a_failed_delete_keeps_everything_and_allows_retry() = runTest {
        val client = signedIn()
        client.failNext("deleteAccount", GimException.Network("offline"))
        val vm = viewModel(client)

        vm.setUnderstood(true)
        vm.delete()
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.deleted)
        assertFalse(state.deleting)
        assertTrue(state.understood)
        assertEquals(R.string.delete_account_error_network, state.error)
        assertNotNull(client.session())

        vm.delete()
        assertNull(vm.state.value.error)
        advanceUntilIdle()
        assertTrue(vm.state.value.deleted)
        assertNull(client.session())
    }

    @Test
    fun a_busy_server_and_other_errors_have_their_own_messages() = runTest {
        val client = signedIn()
        val vm = viewModel(client)
        vm.setUnderstood(true)

        client.failNext("deleteAccount", GimException.RetryLater(503, 10, "busy"))
        vm.delete()
        advanceUntilIdle()
        assertEquals(R.string.delete_account_error_busy, vm.state.value.error)

        client.failNext("deleteAccount", GimException.Server(500, "boom"))
        vm.delete()
        advanceUntilIdle()
        assertEquals(R.string.delete_account_error_generic, vm.state.value.error)
    }

    @Test
    fun the_confirmation_cannot_change_while_deleting() = runTest {
        val vm = viewModel(signedIn())

        vm.setUnderstood(true)
        vm.delete()
        vm.setUnderstood(false)

        assertTrue(vm.state.value.understood)
        advanceUntilIdle()
    }
}
