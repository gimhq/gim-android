package im.g.apps.feature.settings

import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.fake.FakeGimClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockedPeopleViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private suspend fun TestScope.signedIn(): FakeGimClient =
        FakeGimClient(backgroundScope, outboxDelays = null).apply {
            createIdentity("Sofia Costa")
            addDirectoryUser(LUKAS)
            addDirectoryUser(NO_NAME)
        }

    private fun TestScope.viewModel(client: FakeGimClient) =
        BlockedPeopleViewModel(client, ioDispatcher = main.dispatcher).also { advanceUntilIdle() }

    @Test
    fun nobody_blocked_is_an_empty_list() = runTest {
        val state = viewModel(signedIn()).state.value

        assertEquals(emptyList<BlockedPerson>(), state.people)
        assertFalse(state.loadFailed)
    }

    @Test
    fun names_come_from_the_direct_chat_else_the_account_id() = runTest {
        val client = signedIn()
        client.createDirectChat(LUKAS.userId)
        client.blockUser(LUKAS.userId)
        client.blockUser(NO_NAME.userId)

        val people = viewModel(client).state.value.people

        assertEquals(listOf(BlockedPerson(LUKAS.userId, "Lukas Weber"), BlockedPerson(NO_NAME.userId, null)), people)
        assertEquals(NO_NAME.userId, people!![1].label)
    }

    @Test
    fun unblock_asks_first_then_unblocks_and_updates_the_list() = runTest {
        val client = signedIn()
        client.blockUser(LUKAS.userId)
        val vm = viewModel(client)
        val lukas = vm.state.value.people!!.single()

        vm.askUnblock(lukas)
        assertEquals(lukas, vm.state.value.confirm)
        vm.unblock()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), client.privacy().blockedUserIds)
        assertEquals(emptyList<BlockedPerson>(), vm.state.value.people)
        assertNull(vm.state.value.confirm)
        assertFalse(vm.state.value.unblocking)
    }

    @Test
    fun cancel_closes_the_dialog_without_unblocking() = runTest {
        val client = signedIn()
        client.blockUser(LUKAS.userId)
        val vm = viewModel(client)

        vm.askUnblock(vm.state.value.people!!.single())
        vm.cancelUnblock()

        assertNull(vm.state.value.confirm)
        assertEquals(listOf(LUKAS.userId), client.privacy().blockedUserIds)
    }

    @Test
    fun a_failed_unblock_keeps_the_person_and_says_so() = runTest {
        val client = signedIn()
        client.blockUser(LUKAS.userId)
        val vm = viewModel(client)
        client.failNext("unblockUser", GimException.Network("offline"))

        vm.askUnblock(vm.state.value.people!!.single())
        vm.unblock()
        advanceUntilIdle()

        assertEquals(1, vm.state.value.people!!.size)
        assertNull(vm.state.value.confirm)
        assertEquals(R.string.blocked_unblock_error, vm.messages.first())
    }

    @Test
    fun a_failed_load_says_so_and_retry_loads_again() = runTest {
        val client = signedIn()
        client.blockUser(LUKAS.userId)
        client.failNext("privacy", GimException.Storage("disk"))
        val vm = viewModel(client)

        assertTrue(vm.state.value.loadFailed)
        assertNull(vm.state.value.people)

        vm.load()
        advanceUntilIdle()

        assertFalse(vm.state.value.loadFailed)
        assertEquals(1, vm.state.value.people!!.size)
    }

    private companion object {
        val LUKAS = UserProfile("u" + "a".repeat(54) + "a", "lukas", "Lukas Weber")
        val NO_NAME = UserProfile("u" + "b".repeat(54) + "a", "", "")
    }
}
