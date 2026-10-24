package im.g.apps.feature.chat.composer

import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.MembershipChangeKind
import im.g.apps.core.bridge.TimelineItem
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.FakeGimClient
import im.g.apps.feature.chat.R
import im.g.apps.feature.chat.timeline.UiText
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerMembershipTest {
    private val dispatcher = StandardTestDispatcher()
    private val chatId = DemoData.GROUP_CHAT_ID

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
        ).also { runCurrent() }

    @Test
    fun leaving_disablesTheComposer_untilTheChatIsLeft() = test {
        val client = fake()
        val vm = viewModel(client)
        assertEquals(ChatMembership.JOINED, vm.uiState.value.membership)

        client.leaveChat(chatId)
        runCurrent()
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
    }

    @Test
    fun aLeavingChat_isLeavingRightAfterARestart() = test {
        val client = fake()
        client.leaveChat(chatId)
        // A new ViewModel (process restart) reads the state from gim-core, which keeps the leave.
        val vm = viewModel(client)
        assertEquals(ChatMembership.LEAVING, vm.uiState.value.membership)
    }

    @Test
    fun removalByAnotherMember_showsRemoved_andTheChatReadOnly() = test {
        val client = fake()
        val vm = viewModel(client)
        val own = client.sendText(chatId, "Ahoy", null)
        client.advanceOutbox()
        runCurrent()

        client.simulateRemovedBy(chatId, by = DemoData.ana.userId)
        runCurrent()
        assertEquals(ChatMembership.REMOVED, vm.uiState.value.membership)

        vm.onMessageLongPress(own)
        with(vm.uiState.value.actions!!) {
            assertEquals("Ahoy", copyText)
            assertFalse(canReply); assertFalse(canEdit); assertFalse(canDelete); assertFalse(canReact)
        }
    }

    @Test
    fun chatNotActive_fromASend_showsTheChatReadOnly_withoutAnError() = test {
        val notActive = object : GimClient by fake() {
            override suspend fun sendText(chatId: String, body: String, replyTo: String?): String =
                throw GimException.ChatNotActive(chatId, ChatState.LEFT)
        }
        val vm = viewModel(notActive)
        vm.onDraftChange("Ahoy")
        vm.send()
        runCurrent()
        assertEquals(ChatMembership.LEFT, vm.uiState.value.membership)
        assertFalse(vm.uiState.value.canSend)
    }

    @Test
    fun rejectedSend_isFailedAtOnce_withRetryAndDelete() = test {
        val client = fake()
        val vm = viewModel(client)
        // gim-core marks a 4xx rejection Failed at once (no endless Pending).
        client.failNextSend("server error 403: not_member")
        val id = client.sendText(chatId, "hello?", null)
        client.advanceOutbox()
        runCurrent()

        vm.onMessageLongPress(id)
        with(vm.uiState.value.actions!!) {
            assertTrue(canRetry)
            assertTrue(canDiscard)
        }
        vm.cancelSend(id)
        runCurrent()
        // Gone from the timeline: no actions any more.
        vm.onMessageLongPress(id)
        assertNull(vm.uiState.value.actions)
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
            ComposerEvent.Error(UiText.Plural(R.plurals.chat_error_retry_later, 30)),
            vm.events.first(),
        )
    }

    @Test
    fun chatMembership_followsTheChatState() {
        val self = "u-me"
        fun change(userId: String, kind: MembershipChangeKind, actor: String?) =
            TimelineItem.MembershipChange("Sofia Costa", kind, 0, userId, actor)
        val removed = change(self, MembershipChangeKind.REMOVED, "u-ana")
        val left = change(self, MembershipChangeKind.LEFT, self)
        // Same display name, another user: never matched by name.
        val namesake = change("u-other", MembershipChangeKind.REMOVED, "u-ana")

        assertEquals(ChatMembership.JOINED, ComposerViewModel.chatMembership(null, listOf(removed), self))
        assertEquals(ChatMembership.JOINED, ComposerViewModel.chatMembership(ChatState.ACTIVE, listOf(removed), self))
        assertEquals(ChatMembership.LEAVING, ComposerViewModel.chatMembership(ChatState.LEAVING, emptyList(), self))
        assertEquals(ChatMembership.LEFT, ComposerViewModel.chatMembership(ChatState.LEFT, listOf(left), self))
        assertEquals(ChatMembership.LEFT, ComposerViewModel.chatMembership(ChatState.LEFT, listOf(namesake), self))
        assertEquals(ChatMembership.LEFT, ComposerViewModel.chatMembership(ChatState.LEFT, emptyList(), self))
        assertEquals(ChatMembership.REMOVED, ComposerViewModel.chatMembership(ChatState.LEFT, listOf(left, removed), self))
    }
}
