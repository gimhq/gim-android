package im.g.apps.feature.chatlist

import im.g.apps.core.bridge.ChatListDiff
import im.g.apps.core.bridge.ChatListListener
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.DemoData.DIRECT_CHAT_ID
import im.g.apps.core.bridge.fake.DemoData.GROUP_CHAT_ID
import im.g.apps.core.bridge.fake.FakeGimClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Athens")
    private val now = ZonedDateTime.of(2026, 7, 12, 10, 0, 0, 0, zone).toInstant()
    private val clock = Clock.fixed(now, zone)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.demo() = FakeGimClient.withDemoData(
        scope = backgroundScope,
        clock = { now.toEpochMilli() + testScheduler.currentTime },
        zone = zone,
        outboxDelays = null,
    )

    private fun TestScope.viewModel(client: GimClient): ChatListViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val thumbDir = tmp.newFolder()
        val vm = ChatListViewModel(
            client = client,
            clock = clock,
            thumbnailPath = { chatId, messageId -> File(thumbDir, "$chatId-$messageId.png").path },
            defaultDispatcher = dispatcher,
            locale = Locale.UK,
        )
        backgroundScope.launch(dispatcher) { vm.uiState.collect {} }
        return vm
    }

    private fun ChatListViewModel.row(id: String) = uiState.value.rows.first { it.id == id }

    @Test
    fun initial_list_maps_demo_chats_most_recent_first() = runTest {
        val vm = viewModel(demo())
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isEmpty)
        assertEquals(listOf(GROUP_CHAT_ID, DIRECT_CHAT_ID), state.rows.map { it.id })
        val group = state.rows[0]
        assertEquals("Aegean Crew", group.title)
        assertEquals(PreviewSender.Member("Nikos"), group.sender)
        assertEquals("Booked a table in Vourkari for the first night.", group.preview)
        assertEquals("09:58", group.time)
        assertEquals(3, group.unreadCount)
        assertNull(group.avatarPath)
        val direct = state.rows[1]
        assertEquals(DemoData.lukas.displayName, direct.title)
        assertEquals(PreviewSender.You, direct.sender)
        assertEquals("See you then!", direct.preview)
        assertEquals(0, direct.unreadCount)
    }

    @Test
    fun incoming_message_moves_chat_to_top_and_mark_read_clears_unread() = runTest {
        val client = demo()
        val vm = viewModel(client)
        advanceUntilIdle()

        val id = client.simulateIncoming(DIRECT_CHAT_ID, "Weather looks good", from = DemoData.lukas.userId)
        advanceUntilIdle()

        assertEquals(listOf(DIRECT_CHAT_ID, GROUP_CHAT_ID), vm.uiState.value.rows.map { it.id })
        val direct = vm.row(DIRECT_CHAT_ID)
        assertNull(direct.sender)
        assertEquals("Weather looks good", direct.preview)
        assertEquals(1, direct.unreadCount)

        client.simulateIncoming(DIRECT_CHAT_ID, "Call me", from = DemoData.lukas.userId)
        advanceUntilIdle()
        assertEquals(2, vm.row(DIRECT_CHAT_ID).unreadCount)

        client.markRead(DIRECT_CHAT_ID, id)
        advanceUntilIdle()
        assertEquals(1, vm.row(DIRECT_CHAT_ID).unreadCount)
    }

    @Test
    fun mark_read_up_to_last_message_clears_badge() = runTest {
        val client = demo()
        val vm = viewModel(client)
        val id = client.simulateIncoming(GROUP_CHAT_ID, "Last one", from = DemoData.ana.userId)
        advanceUntilIdle()
        assertEquals(4, vm.row(GROUP_CHAT_ID).unreadCount)
        assertEquals(PreviewSender.Member("Ana"), vm.row(GROUP_CHAT_ID).sender)

        client.markRead(GROUP_CHAT_ID, id)
        advanceUntilIdle()
        assertEquals(0, vm.row(GROUP_CHAT_ID).unreadCount)
    }

    @Test
    fun preview_formatting_per_content_type() = runTest {
        val client = demo()
        val vm = viewModel(client)

        client.sendMedia(GROUP_CHAT_ID, tmp.newFile("sunset.jpg").path, "image/jpeg", null, null)
        advanceUntilIdle()
        assertEquals(PreviewSender.You, vm.row(GROUP_CHAT_ID).sender)
        assertEquals("Photo", vm.row(GROUP_CHAT_ID).preview)

        val fileId = client.sendMedia(GROUP_CHAT_ID, tmp.newFile("report.pdf").path, "application/pdf", null, null)
        advanceUntilIdle()
        assertEquals("File: report.pdf", vm.row(GROUP_CHAT_ID).preview)

        client.deleteMessage(GROUP_CHAT_ID, fileId)
        advanceUntilIdle()
        assertEquals("Message deleted", vm.row(GROUP_CHAT_ID).preview)

        client.sendText(DIRECT_CHAT_ID, "On my way", null)
        advanceUntilIdle()
        assertEquals(PreviewSender.You, vm.row(DIRECT_CHAT_ID).sender)
        assertEquals("On my way", vm.row(DIRECT_CHAT_ID).preview)
    }

    @Test
    fun connection_banner_follows_connection_state() = runTest {
        val client = demo()
        val vm = viewModel(client)
        advanceUntilIdle()
        assertEquals(ConnectionBannerUi.WAITING_FOR_NETWORK, vm.uiState.value.connection)

        val expected = listOf(
            ConnectionState.CONNECTING to ConnectionBannerUi.CONNECTING,
            ConnectionState.SYNCING to ConnectionBannerUi.UPDATING,
            ConnectionState.CONNECTED to null,
            ConnectionState.OFFLINE to ConnectionBannerUi.WAITING_FOR_NETWORK,
        )
        for ((state, banner) in expected) {
            client.setConnection(state)
            advanceUntilIdle()
            assertEquals(banner, vm.uiState.value.connection)
        }
    }

    @Test
    fun empty_client_reports_empty_state() = runTest {
        val vm = viewModel(FakeGimClient(backgroundScope, outboxDelays = null))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isEmpty)
        assertTrue(vm.uiState.value.rows.isEmpty())
    }

    @Test
    fun rows_are_sorted_even_if_the_client_delivers_them_unsorted() = runTest {
        val fake = demo()
        val reversing = object : GimClient by fake {
            override suspend fun chatList(listener: ChatListListener) = fake.chatList { diffs ->
                listener.onUpdate(diffs.map { if (it is ChatListDiff.Reset) ChatListDiff.Reset(it.values.reversed()) else it })
            }
        }
        val vm = viewModel(reversing)
        advanceUntilIdle()
        assertEquals(listOf(GROUP_CHAT_ID, DIRECT_CHAT_ID), vm.uiState.value.rows.map { it.id })
    }

    @Test
    fun group_avatar_thumbnail_is_fetched_into_the_provided_path() = runTest {
        val client = demo()
        val vm = viewModel(client)
        val avatar = tmp.newFile("crew.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        client.setGroupInfo(GROUP_CHAT_ID, null, avatar.path)
        advanceUntilIdle()

        val path = vm.row(GROUP_CHAT_ID).avatarPath
        assertTrue(path != null && File(path).isFile)
        assertNull(vm.row(DIRECT_CHAT_ID).avatarPath)
    }
}
