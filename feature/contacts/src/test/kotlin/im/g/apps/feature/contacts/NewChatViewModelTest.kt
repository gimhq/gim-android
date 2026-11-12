package im.g.apps.feature.contacts

import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.UserProfile
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.core.bridge.flow.chatListFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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

    private fun TestScope.newViewModel(setup: (FakeGimClient) -> Unit = {}): Pair<NewChatViewModel, TestClient> {
        val fake = demoClient(backgroundScope)
        fake.addDirectoryUser(SCANNED_USER)
        setup(fake)
        val client = TestClient(fake)
        return NewChatViewModel(client) to client
    }

    @Test
    fun accountId_isTakenFromPastedText() = runTest {
        val (vm, _) = newViewModel()
        vm.onQueryChange("  https://example.com/add?id=${SCANNED_USER.userId.uppercase()} ")
        assertEquals(UserSearchState.Found(UserProfile(SCANNED_USER.userId, "", "")), vm.state.value.search)
        assertTrue(vm.state.value.canStartChat)
    }

    @Test
    fun textWithoutAccountId_isNotAnAccountId() = runTest {
        val (vm, _) = newViewModel()
        vm.onQueryChange("lukas")
        assertEquals(UserSearchState.NotAccountId, vm.state.value.search)
        assertEquals("lukas", vm.state.value.query)
        assertFalse(vm.state.value.canStartChat)
    }

    @Test
    fun clearingQuery_returnsToIdle() = runTest {
        val (vm, _) = newViewModel()
        vm.onQueryChange(SCANNED_USER.userId)
        vm.onQueryChange(" ")
        assertEquals(UserSearchState.Idle, vm.state.value.search)
    }

    @Test
    fun ownAccountId_cannotStartChat() {
        val own = UserProfile(SCANNED_USER.userId, "", "")
        val state = NewChatUiState(query = own.userId, search = UserSearchState.Found(own), selfUserId = own.userId)
        assertFalse(state.canStartChat)
    }

    @Test
    fun startChat_withNewContact_createsDirectChat() = runTest {
        val (vm, client) = newViewModel()
        vm.onQueryChange(SCANNED_USER.userId)
        advanceUntilIdle()
        vm.startChat()
        advanceUntilIdle()
        val event = vm.events.first() as NewChatEvent.OpenChat
        assertEquals(listOf(DemoData.me, SCANNED_USER), client.chatMembers(event.chatId))
    }

    @Test
    fun startChat_failure_emitsError() = runTest {
        val (vm, client) = newViewModel()
        client.createDirectChatError = GimException.Network("timeout")
        vm.onQueryChange(SCANNED_USER.userId)
        advanceUntilIdle()
        vm.startChat()
        advanceUntilIdle()
        assertEquals(NewChatEvent.ShowError(ContactsError.Network), vm.events.first())
        assertTrue(vm.state.value.canStartChat)
    }

    @Test
    fun startChat_withBlockedUser_saysTheyAreBlocked() = runTest {
        val (vm, client) = newViewModel()
        client.blockUser(SCANNED_USER.userId)
        vm.onQueryChange(SCANNED_USER.userId)
        advanceUntilIdle()
        vm.startChat()
        advanceUntilIdle()
        assertEquals(NewChatEvent.ShowError(ContactsError.Blocked), vm.events.first())
        assertTrue(vm.state.value.canStartChat)
    }

    @Test
    fun startChat_withUnknownUserId_showsUserNotFound() = runTest {
        val (vm, client) = newViewModel()
        vm.onQueryChange(" ${UNKNOWN_USER_ID.uppercase()} ")
        advanceUntilIdle()
        assertEquals(UserSearchState.Found(UserProfile(UNKNOWN_USER_ID, "", "")), vm.state.value.search)
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
        vm.onQueryChange(SCANNED_USER.userId)
        advanceUntilIdle()
        vm.startChat()
        advanceUntilIdle()
        assertEquals(NewChatEvent.ShowError(ContactsError.Suspended(null)), vm.events.first())
        assertNotNull(client.session())
        assertTrue(client.chatListFlow().first().isNotEmpty())
    }

    @Test
    fun startChat_contactNotAdmitted_namesTheUserAndCreatesNoChat() = runTest {
        val (vm, client) = newViewModel()
        val before = client.chatListFlow().first().size
        client.createDirectChatError = GimException.ContactNotAdmitted(listOf(SCANNED_USER.userId))
        vm.onQueryChange(SCANNED_USER.userId)
        advanceUntilIdle()
        vm.startChat()
        advanceUntilIdle()
        assertEquals(NewChatEvent.ShowError(ContactsError.ContactNotAdmitted(listOf(SCANNED_USER.userId))), vm.events.first())
        assertEquals(before, client.chatListFlow().first().size)
        assertFalse(vm.state.value.starting)
    }

    @Test
    fun startChat_notAdmitted_showsTheHubRefusalAndKeepsTheSession() = runTest {
        val (vm, client) = newViewModel()
        client.createDirectChatError = GimException.UserNotAdmitted(client.hubs().single().hubId)
        vm.onQueryChange(SCANNED_USER.userId)
        advanceUntilIdle()
        vm.startChat()
        advanceUntilIdle()
        assertEquals(NewChatEvent.ShowError(ContactsError.NotAdmitted(null)), vm.events.first())
        assertNotNull(client.session())
    }

    @Test
    fun startChat_retryLater_showsTheWait() = runTest {
        val (vm, client) = newViewModel()
        client.createDirectChatError = GimException.RetryLater(503, 12, "peer_unavailable")
        vm.onQueryChange(SCANNED_USER.userId)
        advanceUntilIdle()
        vm.startChat()
        advanceUntilIdle()
        assertEquals(NewChatEvent.ShowError(ContactsError.RetryLater(12)), vm.events.first())
    }

    @Test
    fun scanner_opensAndCloses() = runTest {
        val (vm, _) = newViewModel()
        vm.openScanner()
        assertTrue(vm.state.value.scannerOpen)
        vm.closeScanner()
        assertFalse(vm.state.value.scannerOpen)
    }

    @Test
    fun scannedAccountId_createsDirectChatAndOpensIt() = runTest {
        val (vm, client) = newViewModel()
        vm.openScanner()
        vm.onQrScanned("https://example.com/add?id=${SCANNED_USER.userId.uppercase()}")
        assertFalse(vm.state.value.scannerOpen)
        advanceUntilIdle()
        val event = vm.events.first() as NewChatEvent.OpenChat
        assertEquals(listOf(DemoData.me, SCANNED_USER), client.chatMembers(event.chatId))
        assertFalse(vm.state.value.starting)
    }

    @Test
    fun scannedTextWithoutAccountId_showsNoticeAndKeepsScanning() = runTest {
        val (vm, client) = newViewModel()
        vm.openScanner()
        vm.onQrScanned("https://example.com")
        vm.onQrScanned("https://example.com")
        advanceUntilIdle()
        assertEquals(ScanNotice.NOT_ACCOUNT_ID, vm.state.value.scanNotice)
        assertTrue(vm.state.value.scannerOpen)
        assertEquals(0, client.createDirectChatCalls)
    }

    @Test
    fun scannedOwnId_showsOwnCodeNotice() = runTest {
        val (vm, client) = newViewModel()
        client.selfUserId = SCANNED_USER.userId
        vm.openScanner()
        vm.onQrScanned(SCANNED_USER.userId)
        advanceUntilIdle()
        assertEquals(ScanNotice.OWN_CODE, vm.state.value.scanNotice)
        assertTrue(vm.state.value.scannerOpen)
        assertEquals(0, client.createDirectChatCalls)
    }

    @Test
    fun scannedAccountId_failure_emitsError() = runTest {
        val (vm, client) = newViewModel()
        client.createDirectChatError = GimException.Network("timeout")
        vm.openScanner()
        vm.onQrScanned(SCANNED_USER.userId)
        advanceUntilIdle()
        assertEquals(NewChatEvent.ShowError(ContactsError.Network), vm.events.first())
        assertFalse(vm.state.value.starting)
    }

    @Test
    fun duplicateScans_whileCreating_createOneChat() = runTest {
        val (vm, client) = newViewModel()
        vm.openScanner()
        vm.onQrScanned(SCANNED_USER.userId)
        vm.openScanner()
        vm.onQrScanned(SCANNED_USER.userId)
        advanceUntilIdle()
        assertEquals(1, client.createDirectChatCalls)
    }

    @Test
    fun cameraProblems_closeTheScannerWithANotice() = runTest {
        val (vm, _) = newViewModel()
        vm.onCameraDenied()
        assertEquals(ScanNotice.CAMERA_DENIED, vm.state.value.scanNotice)
        vm.openScanner()
        assertEquals(null, vm.state.value.scanNotice)
        vm.onCameraUnavailable()
        assertFalse(vm.state.value.scannerOpen)
        assertEquals(ScanNotice.CAMERA_UNAVAILABLE, vm.state.value.scanNotice)
    }

    private companion object {
        /** Well-formed, but no such user on the hub. */
        const val UNKNOWN_USER_ID = "u" + "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz" + "a"

        /** A well-formed Account ID that exists in the directory. */
        val SCANNED_USER = UserProfile("u" + "b".repeat(54) + "q", "bea", "Bea Lind")
    }
}
