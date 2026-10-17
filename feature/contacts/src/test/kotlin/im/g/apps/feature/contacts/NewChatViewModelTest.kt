package im.g.apps.feature.contacts

import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.flow.chatListFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NewChatViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private fun TestScope.newViewModel(): Pair<NewChatViewModel, TestClient> {
        val client = TestClient(demoClient(backgroundScope))
        return NewChatViewModel(client) to client
    }

    @Test
    fun shortQuery_staysIdleWithoutLookup() = runTest {
        val (vm, client) = newViewModel()
        vm.onQueryChange(" @Lu ")
        advanceUntilIdle()
        assertEquals(UserSearchState.Idle, vm.state.value.search)
        assertEquals(" @Lu ", vm.state.value.query)
        assertTrue(client.lookups.isEmpty())
    }

    @Test
    fun search_isDebouncedAndNormalized() = runTest {
        val (vm, client) = newViewModel()
        vm.onQueryChange("@LUK")
        runCurrent()
        assertEquals(UserSearchState.Searching, vm.state.value.search)

        advanceTimeBy(200)
        vm.onQueryChange("  @LuKaS ")
        runCurrent()
        advanceTimeBy(SEARCH_DEBOUNCE_MS - 1)
        runCurrent()
        assertEquals(UserSearchState.Searching, vm.state.value.search)
        assertTrue(client.lookups.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(UserSearchState.Found(DemoData.lukas), vm.state.value.search)
        assertEquals(listOf("lukas"), client.lookups)
        assertTrue(vm.state.value.canStartChat)
    }

    @Test
    fun unknownHandle_isNotFound() = runTest {
        val (vm, _) = newViewModel()
        vm.onQueryChange("nobody")
        advanceUntilIdle()
        assertEquals(UserSearchState.NotFound("nobody"), vm.state.value.search)
        assertFalse(vm.state.value.canStartChat)
    }

    @Test
    fun lookupFailure_isMappedToError() = runTest {
        val (vm, client) = newViewModel()
        client.findUserError = GimException.Network("offline")
        vm.onQueryChange("lukas")
        advanceUntilIdle()
        assertEquals(UserSearchState.Error(ContactsError.Network), vm.state.value.search)

        client.findUserError = GimException.Server(503, "busy")
        vm.onQueryChange("nikos")
        advanceUntilIdle()
        assertEquals(UserSearchState.Error(ContactsError.Server), vm.state.value.search)
    }

    @Test
    fun clearingQuery_returnsToIdle() = runTest {
        val (vm, _) = newViewModel()
        vm.onQueryChange("lukas")
        advanceUntilIdle()
        vm.onQueryChange("")
        advanceUntilIdle()
        assertEquals(UserSearchState.Idle, vm.state.value.search)
    }

    @Test
    fun ownHandle_cannotStartChat() = runTest {
        val (vm, _) = newViewModel()
        vm.onQueryChange(DemoData.me.handle)
        advanceUntilIdle()
        assertEquals(UserSearchState.Found(DemoData.me), vm.state.value.search)
        assertFalse(vm.state.value.canStartChat)
    }

    @Test
    fun startChat_withExistingContact_opensExistingChat() = runTest {
        val (vm, _) = newViewModel()
        vm.onQueryChange("lukas")
        advanceUntilIdle()
        vm.startChat()
        advanceUntilIdle()
        assertEquals(NewChatEvent.OpenChat(DemoData.DIRECT_CHAT_ID), vm.events.first())
        assertFalse(vm.state.value.starting)
    }

    @Test
    fun startChat_withNewContact_createsDirectChat() = runTest {
        val (vm, client) = newViewModel()
        vm.onQueryChange("ana")
        advanceUntilIdle()
        vm.startChat()
        advanceUntilIdle()
        val event = vm.events.first() as NewChatEvent.OpenChat
        assertEquals(event.chatId, client.createDirectChat(DemoData.ana.userId))
        assertEquals(listOf(DemoData.me, DemoData.ana), client.chatMembers(event.chatId))
    }

    @Test
    fun startChat_failure_emitsError() = runTest {
        val (vm, client) = newViewModel()
        client.createDirectChatError = GimException.Network("timeout")
        vm.onQueryChange("ana")
        advanceUntilIdle()
        vm.startChat()
        advanceUntilIdle()
        assertEquals(NewChatEvent.ShowError(ContactsError.Network), vm.events.first())
        assertTrue(vm.state.value.canStartChat)
    }

    @Test
    fun startChat_withUnknownUserId_showsUserNotFound() = runTest {
        val (vm, client) = newViewModel()
        vm.onQueryChange(" ${UNKNOWN_USER_ID.uppercase()} ")
        advanceUntilIdle()
        assertEquals(UserSearchState.Found(UserProfile(UNKNOWN_USER_ID, "", "")), vm.state.value.search)
        assertTrue(client.lookups.isEmpty())
        assertTrue(vm.state.value.canStartChat)

        vm.startChat()
        advanceUntilIdle()
        assertEquals(NewChatEvent.ShowError(ContactsError.UserNotFound), vm.events.first())
        assertFalse(client.chatListFlow().first().any { it.name == UNKNOWN_USER_ID })
    }

    @Test
    fun startChat_suspendedWithOneHub_showsTheGenericSuspendedError() = runTest {
        val (vm, client) = newViewModel()
        client.createDirectChatError = GimException.UserSuspended(client.hubs().single().hubId)
        vm.onQueryChange("ana")
        advanceUntilIdle()
        vm.startChat()
        advanceUntilIdle()
        assertEquals(NewChatEvent.ShowError(ContactsError.Suspended(null)), vm.events.first())
        assertNotNull(client.session())
        assertTrue(client.chatListFlow().first().isNotEmpty())
    }

    @Test
    fun startChat_retryLater_showsTheWait() = runTest {
        val (vm, client) = newViewModel()
        client.createDirectChatError = GimException.RetryLater(503, 12, "peer_unavailable")
        vm.onQueryChange("ana")
        advanceUntilIdle()
        vm.startChat()
        advanceUntilIdle()
        assertEquals(NewChatEvent.ShowError(ContactsError.RetryLater(12)), vm.events.first())
    }

    private companion object {
        /** Well-formed, but no such user on the hub. */
        const val UNKNOWN_USER_ID = "u" + "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz" + "a"
    }
}
