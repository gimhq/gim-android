package im.g.apps.feature.chatlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import im.g.apps.core.bridge.ChatListDiff
import im.g.apps.core.bridge.ChatListListener
import im.g.apps.core.bridge.ChatState
import im.g.apps.core.bridge.ConnectionState
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.fake.DemoData
import im.g.apps.core.bridge.fake.DemoData.DIRECT_CHAT_ID
import im.g.apps.core.bridge.fake.DemoData.GROUP_CHAT_ID
import im.g.apps.core.bridge.fake.FakeGimClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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

    /** Clock whose instant tests move by hand. */
    private class MutableClock(var instant: Instant, private val zone: ZoneId) : Clock() {
        override fun instant(): Instant = instant
        override fun getZone(): ZoneId = zone
        override fun withZone(zone: ZoneId): Clock = MutableClock(instant, zone)
    }

    private fun TestScope.viewModel(
        client: GimClient,
        clock: Clock = this@ChatListViewModelTest.clock,
        ticker: Flow<Unit> = flowOf(Unit),
    ): ChatListViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val thumbDir = tmp.newFolder()
        val vm = ChatListViewModel(
            client = client,
            clock = clock,
            thumbnailPath = { chatId, messageId -> File(thumbDir, "$chatId-$messageId.png").path },
            defaultDispatcher = dispatcher,
            locale = Locale.UK,
            ticker = ticker,
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
    fun rows_follow_the_chat_state() = runTest {
        val client = demo()
        val vm = viewModel(client)
        advanceUntilIdle()
        assertEquals(ChatState.ACTIVE, vm.row(GROUP_CHAT_ID).state)

        client.leaveChat(GROUP_CHAT_ID)
        advanceUntilIdle()
        assertEquals(ChatState.LEAVING, vm.row(GROUP_CHAT_ID).state)

        client.commitLeave(GROUP_CHAT_ID)
        advanceUntilIdle()
        assertEquals(ChatState.LEFT, vm.row(GROUP_CHAT_ID).state)
        assertEquals(ChatState.ACTIVE, vm.row(DIRECT_CHAT_ID).state)
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

    @Test
    fun ticker_reformats_times_after_midnight_without_chat_changes() = runTest {
        // Sunday 2026-07-12 23:32 in Athens; the demo group's last message is two minutes earlier.
        val evening = ZonedDateTime.of(2026, 7, 12, 23, 32, 0, 0, zone).toInstant()
        val mutableClock = MutableClock(evening, zone)
        val ticker = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }
        val client = FakeGimClient.withDemoData(backgroundScope, clock = { evening.toEpochMilli() }, zone = zone, outboxDelays = null)
        val vm = viewModel(client, clock = mutableClock, ticker = ticker)
        advanceUntilIdle()
        assertEquals("23:30", vm.row(GROUP_CHAT_ID).time)

        mutableClock.instant = ZonedDateTime.of(2026, 7, 13, 0, 0, 1, 0, zone).toInstant()
        advanceUntilIdle()
        assertEquals("23:30", vm.row(GROUP_CHAT_ID).time)

        ticker.emit(Unit)
        advanceUntilIdle()
        assertEquals("Sun", vm.row(GROUP_CHAT_ID).time)
    }

    @Test
    fun midnight_ticker_fires_on_start_and_at_each_local_midnight() = runTest {
        // Saturday 2026-10-24 23:30 in Athens; DST ends the next night, so that day lasts 25 hours.
        val start = ZonedDateTime.of(2026, 10, 24, 23, 30, 0, 0, zone).toInstant()
        val athens = zone
        val virtualClock = object : Clock() {
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
            override fun getZone(): ZoneId = athens
            override fun withZone(zone: ZoneId): Clock = throw UnsupportedOperationException()
        }
        val ticks = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            midnightTicker(virtualClock).collect { ticks += testScheduler.currentTime }
        }
        runCurrent()
        assertEquals(listOf(0L), ticks)

        val toMidnight = 30 * 60_000L
        advanceTimeBy(toMidnight - 1)
        runCurrent()
        assertEquals(listOf(0L), ticks)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, toMidnight), ticks)

        val dstDay = 25 * 3_600_000L
        advanceTimeBy(dstDay)
        runCurrent()
        assertEquals(listOf(0L, toMidnight, toMidnight + dstDay), ticks)
    }

    @Test
    fun ui_state_is_written_on_main_thread_while_mapping_runs_on_default_dispatcher() {
        val main = Executors.newSingleThreadExecutor { Thread(it, "test-main") }.asCoroutineDispatcher()
        val default = Executors.newSingleThreadExecutor { Thread(it, "test-default") }.asCoroutineDispatcher()
        val clientScope = CoroutineScope(SupervisorJob() + default)
        val store = ViewModelStore()
        var vmJob: Job? = null
        Dispatchers.setMain(main)
        try {
            runBlocking {
                val client = FakeGimClient.withDemoData(clientScope, { now.toEpochMilli() }, zone, outboxDelays = null)
                val thumbDir = tmp.newFolder()
                val vm = store.own(
                    ChatListViewModel(
                        client = client,
                        clock = clock,
                        thumbnailPath = { chatId, messageId -> File(thumbDir, "$chatId-$messageId.png").path },
                        defaultDispatcher = default,
                        locale = Locale.UK,
                        ticker = flowOf(Unit),
                    ),
                )
                vmJob = vm.viewModelScope.coroutineContext.job
                val writers = Collections.synchronizedList(mutableListOf<String>())
                val pingShown = CompletableDeferred<Unit>()
                val suspended = AtomicBoolean(false)
                // Once suspended, an unconfined collector resumes on the thread that wrote the StateFlow.
                val collector = launch(Dispatchers.Unconfined) {
                    vm.uiState.collect { state ->
                        if (!suspended.get()) return@collect
                        writers += Thread.currentThread().name.substringBefore(" @")
                        if (state.rows.any { it.preview == "ping" }) pingShown.complete(Unit)
                    }
                }
                suspended.set(true)
                client.simulateIncoming(DIRECT_CHAT_ID, "ping", from = DemoData.lukas.userId)
                withTimeout(10_000) { pingShown.await() }
                collector.cancel()
                assertEquals(writers.map { "test-main" }, writers.toList())
            }
        } finally {
            store.clear()
            // Let the cancelled viewModelScope finish on this Main before tearDown resets it.
            runBlocking { withTimeout(10_000) { vmJob?.join() } }
            clientScope.cancel()
            main.close()
            default.close()
        }
    }

    /** Registers [vm] in this store so [ViewModelStore.clear] cancels its viewModelScope. */
    private inline fun <reified T : ViewModel> ViewModelStore.own(vm: T): T =
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <V : ViewModel> create(modelClass: Class<V>): V = vm as V
            },
        )[T::class.java]
}
