package im.g.apps.feature.chat.composer

import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.InMemoryPendingLeaveStore
import im.g.apps.core.bridge.MembershipChangeKind
import im.g.apps.core.bridge.Session
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.feature.chat.R
import im.g.apps.feature.chat.timeline.UiText
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerMembershipTest {
    private val dispatcher = StandardTestDispatcher()
    private val chatId = DemoData.GROUP_CHAT_ID
    private val pendingLeaves = InMemoryPendingLeaveStore()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    private fun TestScope.fake() = FakeGimClient.withDemoData(backgroundScope, { testScheduler.currentTime }, outboxDelays = null)

    private fun TestScope.viewModel(client: GimClient) =
        ComposerViewModel(
            client,
            chatId,
            object : UriCopier {
                override fun copy(uri: String, maxBytes: Long): CopyResult = CopyResult.TooLarge
            },
            { testScheduler.currentTime },
            dispatcher,
            dispatcher,
            pendingLeaves,
        ).also { runCurrent() }

    /** What [im.g.apps.feature.contacts.GroupInfoViewModel] does on a successful leave. */
    private suspend fun TestScope.leave(client: FakeGimClient) {
        val requestedAtMs = testScheduler.currentTime
        client.leaveChat(chatId)
        pendingLeaves.add(chatId, requestedAtMs)
        runCurrent()
    }

    @Test
    fun leaving_disablesTheComposer_untilTheOwnLeftChangeArrives() = test {
        val client = fake()
        val vm = viewModel(client)
        assertEquals(ChatMembership.JOINED, vm.uiState.value.membership)

        leave(client)
        vm.onDraftChange("Still here?")
        with(vm.uiState.value) {
            assertEquals(ChatMembership.LEAVING, membership)
            assertFalse(canSend)
            assertFalse(canAttach)
        }
        vm.send()
        runCurrent()
        assertEquals("Still here?", vm.uiState.value.draft)

        client.commitLeave(chatId)
        runCurrent()
        assertEquals(ChatMembership.LEFT, vm.uiState.value.membership)
        assertFalse(vm.uiState.value.canSend)
        assertTrue(pendingLeaves.pending.value.isEmpty())
    }

    @Test
    fun aPendingLeave_survivesARestart() = test {
        val client = fake()
        leave(client)
        // A new ViewModel (process restart) reads the pending leave from the store.
        val vm = viewModel(client)
        assertEquals(ChatMembership.LEAVING, vm.uiState.value.membership)
        assertEquals(setOf(chatId), pendingLeaves.pending.value.keys)
    }

    @Test
    fun sendFailingWithRetryLater_showsTheWait() = test {
        val failing = object : GimClient by fake() {
            override suspend fun sendText(chatId: String, body: String, replyTo: String?): String =
                throw GimException.RetryLater(503, 30, "peer_unavailable")
        }
        val vm = viewModel(failing)
        vm.onDraftChange("Ahoy")
        vm.send()
        runCurrent()
        assertEquals(
            ComposerEvent.Error(UiText.Res(R.string.chat_error_retry_later, persistentListOf("30"))),
            vm.events.first(),
        )
    }

    @Test
    fun chatMembership_followsTheOwnLatestChange() {
        val self = Session("u-me", "d-1", "", "Sofia Costa")
        fun change(name: String, kind: MembershipChangeKind, atMs: Long) = TimelineItem.MembershipChange(name, kind, atMs)
        val left = change("Sofia Costa", MembershipChangeKind.LEFT, 2_000)
        val readded = change("Sofia Costa", MembershipChangeKind.ADDED, 3_000)
        val otherLeft = change("Lukas Weber", MembershipChangeKind.LEFT, 2_500)

        assertEquals(ChatMembership.JOINED, ComposerViewModel.chatMembership(listOf(otherLeft), self, null))
        assertEquals(ChatMembership.LEFT, ComposerViewModel.chatMembership(listOf(left, otherLeft), self, null))
        assertEquals(ChatMembership.JOINED, ComposerViewModel.chatMembership(listOf(left, readded), self, null))
        assertEquals(ChatMembership.LEAVING, ComposerViewModel.chatMembership(listOf(otherLeft), self, 1_000))
        // A `Left` from before the request does not end it.
        assertEquals(ChatMembership.LEAVING, ComposerViewModel.chatMembership(listOf(left), self, 2_500))
        assertEquals(ChatMembership.LEFT, ComposerViewModel.chatMembership(listOf(left), self, 1_000))
        assertEquals(ChatMembership.JOINED, ComposerViewModel.chatMembership(listOf(left), null, 1_000))
    }
}
